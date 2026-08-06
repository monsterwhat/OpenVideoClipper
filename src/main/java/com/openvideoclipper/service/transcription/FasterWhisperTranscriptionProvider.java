package com.openvideoclipper.service.transcription;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvideoclipper.config.OvcConfig;
import com.openvideoclipper.entity.TranscriptionChunk;
import com.openvideoclipper.processing.JobExecutionManager;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@ApplicationScoped
@Named("faster-whisper")
public class FasterWhisperTranscriptionProvider implements TranscriptionProvider {

    @Inject
    JobExecutionManager executionManager;

    @Inject
    OvcConfig config;

    private final ObjectMapper mapper = new ObjectMapper();
    private Path scriptPath;

    @PostConstruct
    void init() {
        String userDir = System.getProperty("user.dir", ".");
        scriptPath = Path.of(userDir, "scripts", "transcribe_faster_whisper.py");
        if (!scriptPath.toFile().exists()) {
            scriptPath = Path.of(userDir, "..", "scripts", "transcribe_faster_whisper.py");
        }
    }

    @Override
    public String id() { return "faster-whisper"; }

    @Override
    public String displayName() { return "Faster Whisper (large-v3)"; }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(findPython(), "-c", "import faster_whisper, torch; print('ok')");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(15, TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public TranscriptionResult transcribe(Path audioFile) {
        return transcribe(audioFile, null);
    }

    @Override
    public TranscriptionResult transcribe(Path audioFile, Consumer<Integer> progressCallback) {
        return transcribe(audioFile, progressCallback, null, 0, null);
    }

    public TranscriptionResult transcribe(Path audioFile, Consumer<Integer> progressCallback, Consumer<TranscriptionChunk> chunkCallback, int startChunk) {
        return transcribe(audioFile, progressCallback, chunkCallback, startChunk, null);
    }

    public TranscriptionResult transcribe(Path audioFile, Consumer<Integer> progressCallback, Consumer<TranscriptionChunk> chunkCallback, int startChunk, UUID jobId) {
        Process p = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(findPython());
            cmd.add(scriptPath.toString());
            cmd.add(audioFile.toAbsolutePath().toString());
            cmd.add("--stream-chunks");
            cmd.add("--start-chunk");
            cmd.add(String.valueOf(startChunk));
            cmd.add("--chunk-length");
            cmd.add("5");
            cmd.add("--stride");
            cmd.add("5");
            cmd.add("--model");
            String model = config.getTranscriptionModel();
            cmd.add(model == null || model.isBlank() ? "large-v3" : model);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            String cachePath = config.getTranscriptionCachePath();
            if (cachePath != null && !cachePath.isBlank()) {
                pb.environment().put("HF_HOME", cachePath);
                pb.environment().put("TRANSFORMERS_CACHE", cachePath);
            }
            p = pb.start();
            if (jobId != null) {
                executionManager.trackProcess(jobId, p);
            }

            StringBuilder errorBuilder = new StringBuilder();
            StringBuilder completeJsonBuilder = new StringBuilder();
            boolean completeReceived = false;

            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("{")) {
                        try {
                            JsonNode node = mapper.readTree(line);
                            String type = node.has("type") ? node.get("type").asText() : "";

                            if ("progress".equals(type) && progressCallback != null) {
                                int pct = node.has("pct") ? node.get("pct").asInt() : 0;
                                progressCallback.accept(pct);
                            } else if ("chunk".equals(type) && chunkCallback != null) {
                                TranscriptionChunk chunk = parseChunkNode(node);
                                chunkCallback.accept(chunk);
                            } else if ("complete".equals(type)) {
                                completeReceived = true;
                                completeJsonBuilder.append(line);
                            } else if ("error".equals(type)) {
                                errorBuilder.append(node.has("error") ? node.get("error").asText() : line).append("\n");
                                if (node.has("traceback")) {
                                    errorBuilder.append(node.get("traceback").asText()).append("\n");
                                }
                            }
                        } catch (Exception ignored) {
                            if (line.startsWith("PROGRESS:") && progressCallback != null) {
                                try {
                                    int pct = Integer.parseInt(line.substring(9));
                                    progressCallback.accept(pct);
                                } catch (NumberFormatException ignored2) {}
                            }
                        }
                    } else if (!line.startsWith("PROGRESS:")) {
                        errorBuilder.append(line).append("\n");
                    } else if (progressCallback != null) {
                        try {
                            int pct = Integer.parseInt(line.substring(9));
                            progressCallback.accept(pct);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            boolean done = p.waitFor(20, TimeUnit.MINUTES);
            if (!done || p.exitValue() != 0) {
                p.destroyForcibly();
                String stderr = errorBuilder.toString().trim();
                String detail = !stderr.isEmpty() ? stderr : "transcription process failed";
                throw new TranscriptionException("Faster Whisper failed: " + detail);
            }

            if (completeReceived && !completeJsonBuilder.isEmpty()) {
                return parseCompleteResult(completeJsonBuilder.toString().trim());
            }

            throw new TranscriptionException("Transcription incomplete: no complete signal received");

        } catch (IOException | InterruptedException e) {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
            throw new TranscriptionException("Failed to run Faster Whisper transcription", e);
        }
    }

    private TranscriptionChunk parseChunkNode(JsonNode node) {
        TranscriptionChunk chunk = new TranscriptionChunk();
        chunk.setChunkIndex(node.has("index") ? node.get("index").asInt() : 0);
        chunk.setText(node.has("text") ? node.get("text").asText() : "");
        chunk.setStartTime(node.has("start") ? node.get("start").asDouble() : 0);
        chunk.setEndTime(node.has("end") ? node.get("end").asDouble() : 0);
        return chunk;
    }

    private TranscriptionResult parseCompleteResult(String json) {
        try {
            JsonNode root = mapper.readTree(json);

            String text = root.has("text") ? root.get("text").asText() : "";
            List<WordTimestamp> words = new ArrayList<>();

            if (root.has("words") && root.get("words").isArray()) {
                for (JsonNode w : root.get("words")) {
                    String word = w.has("word") ? w.get("word").asText() : "";
                    double start = w.has("start") ? w.get("start").asDouble() : 0;
                    double end = w.has("end") ? w.get("end").asDouble() : 0;
                    if (!word.isEmpty()) {
                        words.add(new WordTimestamp(word, start, end));
                    }
                }
            }

            double duration = root.has("duration") ? root.get("duration").asDouble() :
                (words.isEmpty() ? 0 : words.get(words.size() - 1).end());

            return new TranscriptionResult(text, words, duration);

        } catch (Exception e) {
            throw new TranscriptionException("Failed to parse Faster Whisper complete result: " + json, e);
        }
    }

    private String findPython() {
        for (String cmd : List.of("python", "python3")) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return cmd;
                }
            } catch (Exception ignored) {}
        }
        return "python";
    }
}