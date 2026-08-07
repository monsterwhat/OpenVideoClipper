package com.openvideoclipper.service.analysis;

import static com.openvideoclipper.utils.LogUtil.error;
import static com.openvideoclipper.utils.LogUtil.info;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvideoclipper.config.OvcConfig;
import com.openvideoclipper.processing.JobExecutionManager;
import com.openvideoclipper.service.VideoClippingService;
import com.openvideoclipper.service.VideoClippingService.FrameSample;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Vision-based analysis provider. Samples frames from a video via
 * {@link VideoClippingService#extractFrames} and asks an Ollama multimodal
 * model (Gemma 4 vision family) to identify clip-worthy visual moments.
 *
 * <p>Progress reporting follows the same mechanism as {@code LLMHighlightService}:
 * the job id is carried in a thread-local set through {@link #setJobContext(UUID)}
 * before analysis runs, and reported via the injected {@link JobExecutionManager}.
 */
@ApplicationScoped
public class VisionAnalysisProvider implements AnalysisProvider {

    private final OvcConfig config;
    private final JobExecutionManager executionManager;
    private final VideoClippingService clippingService;

    private final ThreadLocal<UUID> currentJobId = new ThreadLocal<>();

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    /** JSON Schema for moment extraction: object with a moments array. */
    private static final Map<String, Object> MOMENT_SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "moments", Map.of(
                "type", "array",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "start_seconds", Map.of("type", "number"),
                        "end_seconds", Map.of("type", "number"),
                        "label", Map.of("type", "string"),
                        "confidence", Map.of("type", "number")
                    ),
                    "required", List.of("start_seconds", "end_seconds", "label", "confidence")
                )
            )
        ),
        "required", List.of("moments")
    );

    /** JSON Schema for refinement: object with finished boolean and clips array. */
    private static final Map<String, Object> REFINE_SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "finished", Map.of("type", "boolean"),
            "clips", Map.of(
                "type", "array",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "start_seconds", Map.of("type", "number"),
                        "end_seconds", Map.of("type", "number"),
                        "title", Map.of("type", "string"),
                        "confidence", Map.of("type", "number")
                    ),
                    "required", List.of("start_seconds", "end_seconds", "title", "confidence")
                )
            )
        ),
        "required", List.of("finished", "clips")
    );

    @Inject
    public VisionAnalysisProvider(OvcConfig config, JobExecutionManager executionManager,
                                  VideoClippingService clippingService) {
        this.config = config;
        this.executionManager = executionManager;
        this.clippingService = clippingService;
    }

    /** Set job context for progress reporting during analysis. */
    public void setJobContext(UUID jobId) {
        currentJobId.set(jobId);
    }

    /** Clear job context after analysis completes. */
    public void clearJobContext() {
        currentJobId.remove();
    }

    private UUID jobId() {
        return currentJobId.get();
    }

    @Override
    public String id() {
        return "vision";
    }

    @Override
    public String displayName() {
        return "Gemma 4 Vision";
    }

    @Override
    public boolean isAvailable() {
        if (!config.isVisionEnabled()) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.getOllamaUrl() + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return false;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode models = root.get("models");
            String prefix = config.getVisionModel();
            if (models != null && models.isArray() && prefix != null) {
                for (JsonNode m : models) {
                    JsonNode name = m.get("name");
                    if (name != null && name.isTextual() && name.asText().startsWith(prefix)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public AnalysisResult analyze(Path videoPath, Path audioPath) throws AnalysisException {
        try {
            if (!isAvailable()) {
                return new AnalysisResult("vision", List.of());
            }

            UUID jobId = jobId();
            if (jobId == null) {
                jobId = jobIdFromParent(videoPath);
            }

            Path destDir;
            if (jobId != null) {
                destDir = config.getProjectFramesPath(jobId);
            } else {
                Path parent = videoPath != null && videoPath.getParent() != null
                    ? videoPath.toAbsolutePath().getParent()
                    : Path.of(".");
                destDir = parent.resolve("frames");
            }

            List<FrameSample> frames = clippingService.extractFrames(jobId, videoPath, destDir,
                config.getVisionFrameInterval(), config.getVisionMaxFrames(),
                progress -> reportPhase("vision_frames", progress));

            if (frames.isEmpty()) {
                info("[VisionAnalysisProvider] No frames extracted for " + videoPath);
                return new AnalysisResult("vision", List.of());
            }
            info("[VisionAnalysisProvider] Extracted " + frames.size() + " frames for " + videoPath);

            List<AnalysisEvent> events = new ArrayList<>();
            int batchSize = config.getVisionBatchSize();
            int frameCount = frames.size();
            for (int i = 0; i < frameCount; i += batchSize) {
                int batchNum = i / batchSize + 1;
                int end = Math.min(frameCount, i + batchSize);
                List<FrameSample> batch = frames.subList(i, end);
                reportPhase("vision_batch_" + batchNum, (int) ((double) i / frameCount * 100));
                try {
                    events.addAll(analyzeBatch(batch));
                } catch (Exception e) {
                    error("[VisionAnalysisProvider] Batch " + batchNum + " failed, skipping: " + e.getMessage(), e);
                }
            }

            return new AnalysisResult("vision", postProcess(events));
        } catch (AnalysisException e) {
            throw e;
        } catch (Exception e) {
            throw new AnalysisException("Vision analysis failed", e);
        }
    }

    /** Process one batch of frames through the Ollama vision model. */
    private List<AnalysisEvent> analyzeBatch(List<FrameSample> batch) throws Exception {
        double interval = config.getVisionFrameInterval();
        StringBuilder sb = new StringBuilder();
        sb.append("These are video frames sampled every ")
          .append(interval)
          .append(" seconds from a video, in chronological order.\n");
        sb.append("Frame timestamps (absolute seconds into the video):\n");
        for (FrameSample f : batch) {
            sb.append("- ").append(String.format(Locale.ROOT, "%.1f", f.timestampSeconds())).append("s\n");
        }
        double windowStart = batch.get(0).timestampSeconds();
        double windowEnd = batch.get(batch.size() - 1).timestampSeconds() + interval;
        sb.append("\nIdentify visually interesting, clip-worthy moments in this video: funny, exciting, dramatic, or visually notable events.\n");
        sb.append("Return moments as JSON with ABSOLUTE start and end seconds within the batch window [")
          .append(String.format(Locale.ROOT, "%.1f", windowStart)).append("s, ")
          .append(String.format(Locale.ROOT, "%.1f", windowEnd)).append("s], a short label, and a confidence between 0 and 1.\n");
        sb.append("Return ONLY JSON.");

        List<String> images = new ArrayList<>();
        for (FrameSample f : batch) {
            images.add(Base64.getEncoder().encodeToString(Files.readAllBytes(f.imagePath())));
        }
        String responseText = callVision(sb.toString(), MOMENT_SCHEMA, images);
        return parseMoments(responseText);
    }

    /** Tolerant parse of the moments object from the model's response text. */
    private List<AnalysisEvent> parseMoments(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return List.of();
        }
        try {
            String json = extractJson(responseText);
            if (json == null) {
                return List.of();
            }
            JsonNode root = mapper.readTree(json);
            JsonNode moments = root.get("moments");
            if (moments == null || !moments.isArray()) {
                return List.of();
            }
            List<AnalysisEvent> events = new ArrayList<>();
            for (JsonNode m : moments) {
                double start = m.path("start_seconds").asDouble();
                double end = m.path("end_seconds").asDouble();
                String label = m.path("label").asText("moment");
                double confidence = m.path("confidence").asDouble(0.5);
                events.add(new AnalysisEvent(start, end, label, confidence, Map.of("source", "vision")));
            }
            return events;
        } catch (Exception e) {
            error("[VisionAnalysisProvider] Failed to parse moments response: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Refine a set of candidate events into a clean, non-overlapping, ranked list.
     * Not part of the {@link AnalysisProvider} interface; used by the analysis wiring.
     */
    public List<AnalysisEvent> refineEvents(List<AnalysisEvent> candidates, Double totalDurationSeconds) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        if (!config.isVisionRefineEnabled()) {
            return heuristicRefine(candidates, 0.0);
        }

        try {
            reportPhase("vision_refine", 10);
            List<AnalysisEvent> refined = refineWithOllama(candidates, totalDurationSeconds);
            if (refined != null && !refined.isEmpty()) {
                return refined;
            }
        } catch (Exception e) {
            error("[VisionAnalysisProvider] Vision refine call failed, falling back to heuristic: " + e.getMessage(), e);
        }
        return heuristicRefine(candidates, config.getVisionRefineThreshold());
    }

    /** One Ollama call that ranks and deduplicates the candidate moments. */
    private List<AnalysisEvent> refineWithOllama(List<AnalysisEvent> candidates, Double totalDurationSeconds) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a clip-curation assistant. Below are candidate clip-worthy moments from a video.\n");
        if (totalDurationSeconds != null) {
            sb.append("Full video duration: ").append(String.format(Locale.ROOT, "%.1f", totalDurationSeconds)).append(" seconds.\n");
        }
        sb.append("Refine them into a clean, non-overlapping, ranked list.\n");
        sb.append("Candidate moments (absolute seconds):\n");
        for (int i = 0; i < candidates.size(); i++) {
            AnalysisEvent c = candidates.get(i);
            sb.append("[").append(i + 1).append("] ")
              .append(String.format(Locale.ROOT, "%.1f", c.start()))
              .append("-").append(String.format(Locale.ROOT, "%.1f", c.end()))
              .append(" \"").append(c.label())
              .append("\" confidence=").append(String.format(Locale.ROOT, "%.2f", c.confidence()))
              .append("\n");
        }
        sb.append("\nKeep only the best, most distinct moments. Remove overlaps and near-duplicates. Rank by importance.\n");
        sb.append("Return a JSON object: {\"finished\": boolean, \"clips\": [{\"start_seconds\": number, \"end_seconds\": number, \"title\": string, \"confidence\": number}]}\n");
        sb.append("Return ONLY JSON.");

        String responseText = callVision(sb.toString(), REFINE_SCHEMA, null);
        List<AnalysisEvent> parsed = parseRefinedClips(responseText);
        double threshold = config.getVisionRefineThreshold();
        List<AnalysisEvent> kept = new ArrayList<>();
        for (AnalysisEvent ev : parsed) {
            if (ev.confidence() >= threshold) {
                kept.add(ev);
            }
        }
        int max = config.getAnalysisMaxSuggestions();
        if (kept.size() > max) {
            kept = new ArrayList<>(kept.subList(0, max));
        }
        kept.sort(Comparator.comparingDouble(AnalysisEvent::start));
        return kept;
    }

    /** Tolerant parse of the refined clips array from the model's response text. */
    private List<AnalysisEvent> parseRefinedClips(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return List.of();
        }
        try {
            String json = extractJson(responseText);
            if (json == null) {
                return List.of();
            }
            JsonNode root = mapper.readTree(json);
            JsonNode clips = root.get("clips");
            if (clips == null || !clips.isArray()) {
                return List.of();
            }
            List<AnalysisEvent> events = new ArrayList<>();
            for (JsonNode c : clips) {
                double start = c.path("start_seconds").asDouble();
                double end = c.path("end_seconds").asDouble();
                String title = c.path("title").asText("Moment");
                double confidence = c.path("confidence").asDouble(0.5);
                events.add(new AnalysisEvent(start, end, title, confidence, Map.of("source", "vision_refine")));
            }
            return events;
        } catch (Exception e) {
            error("[VisionAnalysisProvider] Failed to parse refine response: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Heuristic pass: sort by confidence desc, greedily keep events whose
     * IoU overlap vs already-kept events is below 0.5, cap at max suggestions.
     */
    private List<AnalysisEvent> heuristicRefine(List<AnalysisEvent> candidates, double minConfidence) {
        List<AnalysisEvent> sorted = new ArrayList<>();
        for (AnalysisEvent c : candidates) {
            if (c.confidence() >= minConfidence) {
                sorted.add(c);
            }
        }
        sorted.sort(Comparator.comparingDouble(AnalysisEvent::confidence).reversed());

        List<AnalysisEvent> kept = new ArrayList<>();
        for (AnalysisEvent c : sorted) {
            boolean overlaps = false;
            for (AnalysisEvent k : kept) {
                if (iou(c, k) >= 0.5) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                kept.add(c);
                if (kept.size() >= config.getAnalysisMaxSuggestions()) {
                    break;
                }
            }
        }
        kept.sort(Comparator.comparingDouble(AnalysisEvent::start));
        return kept;
    }

    /** Drop short/low-confidence events, merge >70% overlapping ones, cap at max suggestions. */
    private List<AnalysisEvent> postProcess(List<AnalysisEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        List<AnalysisEvent> filtered = new ArrayList<>();
        for (AnalysisEvent ev : events) {
            double duration = ev.end() - ev.start();
            if (duration >= 3.0 && ev.confidence() >= 0.4) {
                filtered.add(ev);
            }
        }
        if (filtered.isEmpty()) {
            return filtered;
        }

        // Merge: sort by confidence desc, keep events that do not overlap kept by >70%.
        filtered.sort(Comparator.comparingDouble(AnalysisEvent::confidence).reversed());
        List<AnalysisEvent> merged = new ArrayList<>();
        for (AnalysisEvent ev : filtered) {
            boolean overlaps = false;
            for (AnalysisEvent k : merged) {
                if (iou(ev, k) > 0.7) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                merged.add(ev);
            }
        }

        int max = config.getAnalysisMaxSuggestions();
        if (merged.size() > max) {
            merged = new ArrayList<>(merged.subList(0, max));
        }
        merged.sort(Comparator.comparingDouble(AnalysisEvent::start));
        return merged;
    }

    /** Intersection-over-union of two event time windows. */
    private double iou(AnalysisEvent a, AnalysisEvent b) {
        double intersection = Math.min(a.end(), b.end()) - Math.max(a.start(), b.start());
        if (intersection <= 0) {
            return 0;
        }
        double union = (a.end() - a.start()) + (b.end() - b.start()) - intersection;
        if (union <= 0) {
            return 0;
        }
        return intersection / union;
    }

    /** Fallback: derive the job id from the video's parent directory name when it is a UUID. */
    private UUID jobIdFromParent(Path videoPath) {
        if (videoPath == null) {
            return null;
        }
        Path parent = videoPath.toAbsolutePath().getParent();
        if (parent == null || parent.getFileName() == null) {
            return null;
        }
        String name = parent.getFileName().toString();
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void reportPhase(String phase, int progress) {
        UUID id = jobId();
        if (id != null) {
            executionManager.setPhase(id, phase);
            executionManager.updateProgress(id, Math.min(100, Math.max(0, progress)));
        }
    }

    /**
     * Call Ollama {@code /api/generate} with the vision model, JSON schema and
     * optional base64 frame images. Returns the model's {@code response} text.
     */
    private String callVision(String prompt, Map<String, Object> schema, List<String> images) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getVisionModel());
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("format", schema);
        Map<String, Object> options = new HashMap<>();
        options.put("num_ctx", 8192);
        options.put("temperature", 0.2);
        body.put("options", options);
        if (images != null && !images.isEmpty()) {
            body.put("images", images);
        }

        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(config.getOllamaUrl() + "/api/generate"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(config.getAnalysisTimeoutMinutes()))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Ollama returned " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode respNode = root.get("response");
        return respNode != null && respNode.isTextual() ? respNode.asText() : "";
    }

    /** Tolerant JSON extraction: strip markdown fences, take the first {…} (or […]). */
    private String extractJson(String text) {
        if (text == null) {
            return null;
        }
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int fenceEnd = text.indexOf("```", fence + 3);
            if (fenceEnd >= 0) {
                text = text.substring(fence + 3, fenceEnd);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        int arrStart = text.indexOf('[');
        int arrEnd = text.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) {
            return text.substring(arrStart, arrEnd + 1);
        }
        return null;
    }
}
