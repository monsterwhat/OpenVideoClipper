package com.openvideoclipper.service.transcription;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@Named("parakeet")
public class ParakeetTranscriptionProvider implements TranscriptionProvider {

    @Inject
    JobExecutionManager executionManager;

    private final ObjectMapper mapper = new ObjectMapper();
    private Path scriptPath;

    @PostConstruct
    void init() {
        String userDir = System.getProperty("user.dir", ".");
        scriptPath = Path.of(userDir, "scripts", "transcribe_parakeet.py");
        if (!scriptPath.toFile().exists()) {
            scriptPath = Path.of(userDir, "..", "scripts", "transcribe_parakeet.py");
        }
    }

    @Override
    public String id() { return "parakeet"; }

    @Override
    public String displayName() { return "NVIDIA Parakeet TDT 0.6B v3"; }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(findPython(), "-c", "import transformers, torch, soundfile; print('ok')");
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

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("HF_HUB_DISABLE_PROGRESS_BARS", "1");
            pb.environment().put("HF_HUB_DISABLE_SYMLINKS_WARNING", "1");
            pb.environment().put("TRANSFORMERS_VERBOSITY", "error");
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
                            } else if ("error".equals(type) || node.has("error")) {
                                errorBuilder.append(node.has("error") ? node.get("error").asText() : line).append("\n");
                                if (node.has("traceback")) {
                                    errorBuilder.append(node.get("traceback").asText()).append("\n");
                                }
                            }
                        } catch (Exception ignored) {
                            // Not JSON, could be progress line
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

            boolean done = p.waitFor(30, TimeUnit.MINUTES);
            if (!done || p.exitValue() != 0) {
                p.destroyForcibly();
                String stderr = errorBuilder.toString().trim();
                String detail = !stderr.isEmpty() ? stderr : "transcription process failed";
                throw new TranscriptionException("Parakeet failed: " + detail);
            }

            if (completeReceived && !completeJsonBuilder.isEmpty()) {
                return parseCompleteResult(completeJsonBuilder.toString().trim());
            }

            // Fallback: assemble from chunks if complete not received
            throw new TranscriptionException("Transcription incomplete: no complete signal received");

        } catch (IOException | InterruptedException e) {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
            throw new TranscriptionException("Failed to run Parakeet transcription", e);
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
            throw new TranscriptionException("Failed to parse Parakeet complete result: " + json, e);
        }
    }

    @SuppressWarnings("unchecked")
    private TranscriptionResult parseResult(String json) {
        try {
            Map<String, Object> root = mapper.readValue(json, new TypeReference<>() {});

            String text = (String) root.getOrDefault("text", "");
            List<WordTimestamp> words = new ArrayList<>();

            // Prefer new "words" format (word-level timestamps)
            Object wordsObj = root.get("words");
            if (wordsObj instanceof List) {
                for (Object w : (List<Object>) wordsObj) {
                    if (w instanceof Map) {
                        Map<String, Object> wm = (Map<String, Object>) w;
                        String word = (String) wm.getOrDefault("word", "");
                        double start = toDouble(wm.get("start"));
                        double end = toDouble(wm.get("end"));
                        if (!word.isEmpty()) {
                            words.add(new WordTimestamp(word, start, end));
                        }
                    }
                }
            }

            // Fallback to old "chunks" format
            if (words.isEmpty()) {
                Object chunks = root.get("chunks");
                if (chunks instanceof List) {
                    for (Object chunk : (List<Object>) chunks) {
                        if (chunk instanceof Map) {
                            Map<String, Object> c = (Map<String, Object>) chunk;
                            Object timestamps = c.get("timestamp");
                            if (timestamps instanceof List) {
                                List<Object> ts = (List<Object>) timestamps;
                                double start = ts.size() > 0 ? toDouble(ts.get(0)) : 0;
                                double end = ts.size() > 1 ? toDouble(ts.get(1)) : start;
                                words.add(new WordTimestamp(
                                    (String) c.getOrDefault("text", ""),
                                    start, end
                                ));
                            }
                        }
                    }
                }
            }

            double duration = words.isEmpty() ? 0 : words.get(words.size() - 1).end();

            return new TranscriptionResult(text, words, duration);

        } catch (Exception e) {
            throw new TranscriptionException("Failed to parse Parakeet output: " + json, e);
        }
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0;
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
