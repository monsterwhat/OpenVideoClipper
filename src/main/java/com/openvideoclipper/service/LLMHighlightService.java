package com.openvideoclipper.service;

import com.openvideoclipper.config.OvcConfig;
import com.openvideoclipper.processing.LLMProvider;
import com.openvideoclipper.processing.JobExecutionManager;
import com.openvideoclipper.service.transcription.TranscriptionProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class LLMHighlightService implements LLMProvider {

    @Inject
    OvcConfig config;

    @Inject
    JobExecutionManager executionManager;

    private final ThreadLocal<UUID> currentJobId = new ThreadLocal<>();

    @Override
    public void setJobContext(UUID jobId) {
        currentJobId.set(jobId);
    }

    @Override
    public void clearJobContext() {
        currentJobId.remove();
    }

    private UUID jobId() {
        return currentJobId.get();
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    /** JSON Schema for extraction: array of clips with sentence_start/sentence_end. */
    private static final Map<String, Object> EXTRACTION_SCHEMA = Map.of(
        "type", "array",
        "items", Map.of(
            "type", "object",
            "properties", Map.of(
                "sentence_start", Map.of("type", "integer"),
                "sentence_end", Map.of("type", "integer"),
                "title", Map.of("type", "string"),
                "reason", Map.of("type", "string"),
                "confidence", Map.of("type", "number")
            ),
            "required", List.of("sentence_start", "sentence_end", "title", "reason", "confidence")
        )
    );

    /** JSON Schema for refinement: object with finished boolean and clips array. */
    private static final Map<String, Object> REFINEMENT_SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "finished", Map.of("type", "boolean"),
            "clips", Map.of(
                "type", "array",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "start", Map.of("type", "number"),
                        "end", Map.of("type", "number"),
                        "title", Map.of("type", "string"),
                        "reason", Map.of("type", "string"),
                        "confidence", Map.of("type", "number")
                    ),
                    "required", List.of("start", "end", "title", "reason", "confidence")
                )
            )
        ),
        "required", List.of("finished", "clips")
    );

    @Override
    public List<LLMProvider.Suggestion> extractHighlights(TranscriptionProvider.TranscriptionResult transcription) {
        return extractHighlights(transcription, null);
    }

    @Override
    public List<LLMProvider.Suggestion> extractHighlights(TranscriptionProvider.TranscriptionResult transcription, String userPrompt) {
        return extractHighlights(transcription, userPrompt, List.of());
    }

    @Override
    public List<LLMProvider.Suggestion> extractHighlights(TranscriptionProvider.TranscriptionResult transcription, String userPrompt, List<LLMProvider.Suggestion> existingClips) {
        String lockedClipsSummary = null;
        if (existingClips != null && !existingClips.isEmpty()) {
            lockedClipsSummary = buildCarryOver(existingClips);
        }
        return extractHighlightsImpl(transcription, userPrompt, lockedClipsSummary);
    }

    private List<LLMProvider.Suggestion> extractHighlightsImpl(
            TranscriptionProvider.TranscriptionResult transcription, String userPrompt,
            String lockedClipsSummary) {
        try {
            var words = transcription.words();
            if (words.isEmpty()) return List.of();

            int segmentSize = config.getAnalysisSegmentSize();

            var allChunks = groupWordsIntoChunks(words, 0, words.size());

            if (words.size() <= segmentSize) {
                reportPhase("analyzing", 50);
                String prompt = buildPromptSegment(words, 0, words.size(), userPrompt, allChunks, lockedClipsSummary);
                String response = callOllama(config.getAnalysisModel(), prompt, EXTRACTION_SCHEMA);
                return filterAndDeduplicate(parseSuggestions(response, allChunks), 0.4);
            }

            int totalSegments = (int) Math.ceil((double) words.size() / segmentSize);

            int minClips = 3;
            int maxRetries = 2;
            double[] confidenceThresholds = {0.4, 0.25, 0.15};
            String[] retryModifiers = {
                null,
                "\n\nBe MORE GENEROUS: include clips that are interesting even if not the absolute best. Lower your threshold for what qualifies as a clip-worthy moment.",
                "\n\nBe VERY GENEROUS: include anything remotely interesting or entertaining. Prefer to include borderline moments rather than skip them. Maximise the number of clips."
            };

            List<LLMProvider.Suggestion> allClips = new ArrayList<>();
            int lastRetry = 0;

            for (int retry = 0; retry <= maxRetries; retry++) {
                if (retry > 0 && allClips.size() >= minClips) break;
                lastRetry = retry;

                String effectivePrompt = userPrompt;
                if (retryModifiers[retry] != null) {
                    effectivePrompt = userPrompt != null
                        ? userPrompt + retryModifiers[retry]
                        : retryModifiers[retry].trim();
                }

                var segmentClips = runSegmentChain(words, segmentSize, effectivePrompt,
                    allChunks, totalSegments, retry, lockedClipsSummary);

                for (var clip : segmentClips) {
                    if (!containsClip(allClips, clip)) {
                        allClips.add(clip);
                    }
                }
            }

            allClips = filterAndDeduplicate(allClips,
                confidenceThresholds[Math.min(lastRetry, confidenceThresholds.length - 1)]);

            if (allClips.isEmpty()) return allClips;

            int pass = 0;
            while (allClips.size() > 1) {
                pass++;
                int before = allClips.size();
                reportPhase("refining_pass_" + pass, Math.min(pass * 20, 100));
                var result = refineSuggestions(allClips, userPrompt, transcription.durationSeconds(), pass, allChunks);
                allClips = result.suggestions;
                if (result.finished || converged(before, allClips)) break;
            }
            return allClips;

        } catch (Exception e) {
            throw new RuntimeException("LLM highlight extraction failed", e);
        }
    }

    private List<LLMProvider.Suggestion> runSegmentChain(
            List<TranscriptionProvider.WordTimestamp> words,
            int segmentSize, String userPrompt,
            List<WordChunk> globalChunks,
            int totalSegments, int retryIndex,
            String lockedClipsSummary) {

        List<LLMProvider.Suggestion> allClips = new ArrayList<>();
        int overlap = config.getAnalysisOverlapWords();
        String phasePrefix = retryIndex > 0 ? "analyzing_retry" + retryIndex + "_" : "analyzing_";

        for (int i = 0; i < totalSegments; i++) {
            int nominalStart = i * segmentSize;
            int segmentStart = Math.max(0, nominalStart - overlap);
            int segmentEnd = Math.min(words.size(), nominalStart + segmentSize + overlap);
            if (segmentEnd - segmentStart < 100) break;

            int segNum = i + 1;
            reportPhase(phasePrefix + "segment_" + segNum + "_of_" + totalSegments,
                    (int) ((double) segNum / totalSegments * 90 / (retryIndex + 1)));

            String prompt = buildPromptSegment(words, segmentStart, segmentEnd, userPrompt, globalChunks, lockedClipsSummary);
            try {
                String response = callOllama(config.getAnalysisModel(), prompt, EXTRACTION_SCHEMA);
                var clips = parseSegmentResponse(response, globalChunks);

                for (var clip : clips) {
                    if (!containsClip(allClips, clip)) {
                        allClips.add(clip);
                    }
                }
            } catch (Exception e) {
                // If a segment fails, continue with the next one
                continue;
            }
        }

        return allClips;
    }

    private void reportPhase(String phase, int progress) {
        UUID id = jobId();
        if (id != null) {
            executionManager.setPhase(id, phase);
            executionManager.updateProgress(id, Math.min(100, Math.max(0, progress)));
        }
    }

    private String buildPromptSegment(List<TranscriptionProvider.WordTimestamp> allWords,
                                       int fromIndex, int toIndex,
                                       String userPrompt,
                                       List<WordChunk> globalChunks,
                                       String lockedClipsSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are analyzing a video transcript. ")
          .append("The transcript was produced by ASR (speech-to-text) so some words may be misrecognized ")
          .append("and punctuation may be missing. Ignore these transcription artifacts. ")
          .append("Your task: extract clips based on the instructions below.\n\n");

        double segStart = allWords.get(fromIndex).start();
        double segEnd = allWords.get(toIndex - 1).end();
        sb.append("Transcript excerpt from ")
          .append(formatTime(segStart)).append(" to ").append(formatTime(segEnd))
          .append(". Find all clip-worthy moments in this excerpt independently.\n\n");

        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("CLIP CRITERIA:\n")
              .append(userPrompt.trim())
              .append("\n\n");
        }

        if (lockedClipsSummary != null && !lockedClipsSummary.isBlank()) {
            sb.append("These clips are already locked (do NOT re-suggest them):\n")
              .append(lockedClipsSummary).append("\n\n");
        }

        sb.append("OUTPUT FORMAT:\n")
          .append("Return a JSON array of clip objects. ")
          .append("Use sentence_start and sentence_end (inclusive) to identify clip boundaries:\n")
          .append("[{\"sentence_start\":int, \"sentence_end\":int, \"title\":\"short title\", \"reason\":\"why clip-worthy\", \"confidence\":0.0-1.0}]\n\n")
          .append("Each clip should be approximately 15-90 seconds worth of content (roughly 3-20 sentences).\n")
          .append("If nothing clip-worthy in this segment, return [].\n")
          .append("Return ONLY the JSON array, no other text.\n\n");

        sb.append("Transcript (sentences numbered for easy reference):\n");

        for (int i = 0; i < globalChunks.size(); i++) {
            var c = globalChunks.get(i);
            if (c.end() < segStart) continue;
            if (c.start() > segEnd) break;
            sb.append("[SENTENCE ").append(i).append("] ").append(c.text()).append("\n");
        }

        return sb.toString();
    }

    /** Groups consecutive words into sentence-like chunks for compact transcript display. */
    private record WordChunk(double start, double end, String text) {}

    private List<WordChunk> groupWordsIntoChunks(List<TranscriptionProvider.WordTimestamp> words,
                                                  int fromIndex, int toIndex) {
        List<WordChunk> result = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        double chunkStart = words.get(fromIndex).start();

        for (int i = fromIndex; i < toIndex; i++) {
            var w = words.get(i);
            String word = w.word();

            if (i > fromIndex) {
                var prev = words.get(i - 1);
                double gap = w.start() - prev.end();

                // Start a new chunk on: time gap > 1s, or sentence end with enough context
                boolean gapBreak = gap > 1.0 && currentText.length() > 20;
                boolean sentenceBreak = currentText.length() > 40
                    && (word.endsWith(".") || word.endsWith("!") || word.endsWith("?")
                        || word.endsWith(",") || word.startsWith("I") || word.startsWith("So"));
                boolean hardBreak = currentText.length() > 250;

                if (gapBreak || sentenceBreak || hardBreak) {
                    result.add(new WordChunk(chunkStart, prev.end(), currentText.toString().trim()));
                    currentText = new StringBuilder();
                    chunkStart = w.start();
                }
            }

            if (!currentText.isEmpty()) currentText.append(' ');
            currentText.append(word);
        }

        // Last chunk
        if (!currentText.isEmpty()) {
            result.add(new WordChunk(chunkStart, words.get(toIndex - 1).end(), currentText.toString().trim()));
        }

        return result;
    }

    private List<LLMProvider.Suggestion> parseSegmentResponse(String responseText, List<WordChunk> globalChunks) {
        String arrayJson = extractJsonArray(responseText);
        if (arrayJson != null) {
            return parseSuggestions(arrayJson, globalChunks);
        }
        String objJson = extractJsonObject(responseText);
        if (objJson == null) return List.of();
        try {
            Map<String, Object> obj = mapper.readValue(objJson.trim(), new TypeReference<>() {});
            if (obj.containsKey("clips")) {
                Object clipsRaw = obj.get("clips");
                if (clipsRaw instanceof List<?> clipsList && !clipsList.isEmpty()) {
                    return parseSuggestions(mapper.writeValueAsString(clipsList), globalChunks);
                }
                return List.of();
            }
            return parseSuggestions("[" + objJson + "]", globalChunks);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Tolerance-based dedup: checks if a clip matching title + start/end within 1s already exists. */
    private boolean containsClip(List<LLMProvider.Suggestion> clips, LLMProvider.Suggestion clip) {
        for (var c : clips) {
            if (c.title().equals(clip.title())
                    && Math.abs(c.start() - clip.start()) < 1.0
                    && Math.abs(c.end() - clip.end()) < 1.0) {
                return true;
            }
        }
        return false;
    }

    /** Build a dedup-summary string from clips found so far (used when LLM returns simple arrays). */
    private String buildCarryOver(List<LLMProvider.Suggestion> clips) {
        if (clips == null || clips.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int n = Math.min(clips.size(), 10);
        for (int i = 0; i < n; i++) {
            var c = clips.get(i);
            if (i > 0) sb.append("; ");
            sb.append(c.title()).append(" [").append(formatTime(c.start())).append("-").append(formatTime(c.end())).append("]");
        }
        if (clips.size() > 10) sb.append("; ... and ").append(clips.size() - 10).append(" more");
        return sb.toString();
    }

    private List<LLMProvider.Suggestion> filterAndDeduplicate(List<LLMProvider.Suggestion> suggestions, double minConfidence) {
        if (suggestions == null || suggestions.isEmpty()) return suggestions;

        // Heuristic: filter out transcript-editing suggestions by keyword
        List<String> bannedTitles = List.of(
            "remove", "add ", "fix ", "improve", "correct", "adjust", "modify", "reduce",
            "punctuat", "grammar", "tense", "pronoun", "clarity", "readability",
            "capitaliz", "spelling", "rephrase", "reword", "simplif",
            "transcript", "clean up", "cleanup"
        );
        List<LLMProvider.Suggestion> heuristicFiltered = new ArrayList<>();
        for (var s : suggestions) {
            String combined = (s.title() + " " + s.reason()).toLowerCase(Locale.ROOT);
            boolean banned = false;
            for (String kw : bannedTitles) {
                if (combined.contains(kw)) {
                    banned = true;
                    break;
                }
            }
            if (!banned) {
                heuristicFiltered.add(s);
            }
        }

        // Duration + confidence gate
        List<LLMProvider.Suggestion> filtered = new ArrayList<>();
        for (var s : heuristicFiltered) {
            double dur = s.end() - s.start();
            if (dur >= 10.0 && s.confidence() >= minConfidence) {
                filtered.add(s);
            }
        }
        if (filtered.isEmpty()) return filtered;

        // Sort by confidence desc, then dedup exact matches, cap at max
        filtered.sort(Comparator.comparingDouble(LLMProvider.Suggestion::confidence).reversed());

        int maxSuggestions = config.getAnalysisMaxSuggestions();
        List<LLMProvider.Suggestion> kept = new ArrayList<>();
        for (var s : filtered) {
            boolean exactDup = false;
            for (var k : kept) {
                if (Math.abs(k.start() - s.start()) < 0.1
                        && Math.abs(k.end() - s.end()) < 0.1
                        && k.title().equals(s.title())) {
                    exactDup = true;
                    break;
                }
            }
            if (!exactDup) {
                kept.add(s);
                if (kept.size() >= maxSuggestions) break;
            }
        }

        kept.sort(Comparator.comparingDouble(LLMProvider.Suggestion::start));
        return kept;
    }

    private record RefineResult(List<LLMProvider.Suggestion> suggestions, boolean finished) {}

    private RefineResult refineSuggestions(List<LLMProvider.Suggestion> suggestions,
                                            String userPrompt, Double totalDuration,
                                            int passNumber, List<WordChunk> allChunks) {
        if (suggestions == null || suggestions.size() <= 1)
            return new RefineResult(suggestions, true);

        StringBuilder sb = new StringBuilder();
        sb.append("You are a clip-curation assistant. Below are candidate clips extracted from a video. ")
          .append("Refine them into a clean, non-overlapping, ranked list.\n\n");

        if (totalDuration != null) {
            sb.append("Full video duration: ").append(formatTime(totalDuration)).append(".\n");
        }
        sb.append("Refinement pass: ").append(passNumber).append(".\n\n");

        sb.append("Current clips:\n");
        for (int i = 0; i < suggestions.size(); i++) {
            var s = suggestions.get(i);
            sb.append("[").append(i + 1).append("] ")
              .append(formatTime(s.start())).append(" - ").append(formatTime(s.end()))
              .append(" | \"").append(s.title())
              .append("\" | ").append(s.reason())
              .append(" | confidence=").append(String.format(Locale.ROOT, "%.2f", s.confidence()))
              .append("\n");

            // Include transcript text covering this clip's time range for boundary snapping
            if (allChunks != null) {
                var ctx = getTranscriptContext(allChunks, s.start(), s.end());
                if (ctx != null) {
                    sb.append("     Transcript:\n");
                    for (var line : ctx) {
                        sb.append("     [").append(formatTime(line.start)).append(" - ").append(formatTime(line.end))
                          .append("] ").append(line.text).append("\n");
                    }
                }
            }
        }

        sb.append("\nTASKS:\n");
        sb.append("1. Merge overlapping or adjacent clips that capture the same moment.\n");
        sb.append("2. Remove near-duplicates (same moment, slightly different boundaries).\n");
        sb.append("3. Adjust start/end times to natural boundaries — use the transcript text to snap to sentence boundaries.\n");
        sb.append("4. Re-rank: best, most interesting clip first.\n");
        sb.append("5. Each clip must be 15-90 seconds duration.\n");
        sb.append("6. Keep only clips that match the user's criteria.\n\n");

        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("User's clip criteria:\n").append(userPrompt.trim()).append("\n\n");
        }

        int maxOut = config.getAnalysisMaxSuggestions();
        sb.append("OUTPUT FORMAT:\n");
        sb.append("Return a JSON object with two fields:\n");
        sb.append("  \"finished\": boolean — set true ONLY if the clip list is already optimal and no more refinement would help.\n");
        sb.append("  \"clips\": array of up to ").append(maxOut)
          .append(" clip objects. If none survive, set clips to [].\n");
        sb.append("Clip format: {\"start\":float, \"end\":float, \"title\":string, \"reason\":string, \"confidence\":float}\n");
        sb.append("Return ONLY the JSON object, no other text.\n\n");
        sb.append("If the list is already clean and you wouldn't change anything, set finished: true.\n");
        sb.append("Otherwise set finished: false so another pass can improve it.\n");

        try {
            String refModel = config.getRefinementModel();
            if (refModel == null || refModel.isBlank()) refModel = config.getAnalysisModel();
            String response = callOllama(refModel, sb.toString(), REFINEMENT_SCHEMA);
            String json = extractJsonObject(response);
            if (json == null) return new RefineResult(suggestions, false);

            Map<String, Object> obj = mapper.readValue(json, new TypeReference<>() {});
            boolean finished = obj.get("finished") instanceof Boolean b && b;

            Object clipsRaw = obj.get("clips");
            if (clipsRaw instanceof List<?> clipsList && !clipsList.isEmpty()) {
                String clipsJson = mapper.writeValueAsString(clipsList);
                List<LLMProvider.Suggestion> refined = parseSuggestions(clipsJson);
                if (!refined.isEmpty()) {
                    return new RefineResult(filterAndDeduplicate(refined, 0.4), finished);
                }
            }
            return new RefineResult(suggestions, false);
        } catch (Exception e) {
            return new RefineResult(suggestions, false);
        }
    }

    /** Returns the transcript chunks overlapping a [start, end] time range, plus one chunk of context on each side. */
    private List<WordChunk> getTranscriptContext(List<WordChunk> chunks, double start, double end) {
        List<WordChunk> result = new ArrayList<>();
        int firstIdx = -1, lastIdx = -1;
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            if (c.start() <= end && c.end() >= start) {
                if (firstIdx < 0) firstIdx = i;
                lastIdx = i;
            }
        }
        if (firstIdx < 0) return result;
        // Include one extra chunk before and after for context
        int from = Math.max(0, firstIdx - 1);
        int to = Math.min(chunks.size(), lastIdx + 2);
        for (int i = from; i < to; i++) {
            result.add(chunks.get(i));
        }
        return result;
    }

    private boolean converged(int sizeBefore, List<LLMProvider.Suggestion> current) {
        int after = current.size();
        if (after == sizeBefore || after <= 1) return true;
        return after > sizeBefore * 0.8;
    }

    private String callOllama(String prompt) throws Exception {
        return callOllama(config.getAnalysisModel(), prompt, null);
    }

    private String callOllama(String model, String prompt) throws Exception {
        return callOllama(model, prompt, null);
    }

    private String callOllama(String model, String prompt, Object format) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("options", Map.of("temperature", config.getAnalysisTemperature()));
        if (format != null) {
            body.put("format", format);
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

        Map<String, Object> result = mapper.readValue(resp.body(), new TypeReference<>() {});
        return (String) result.getOrDefault("response", "");
    }

    private List<LLMProvider.Suggestion> parseSuggestions(String responseText) {
        return parseSuggestions(responseText, null);
    }

    private List<LLMProvider.Suggestion> parseSuggestions(String responseText, List<WordChunk> globalChunks) {
        String json = extractJsonArray(responseText);
        if (json == null) return List.of();
        try {
            List<Map<String, Object>> items = mapper.readValue(json, new TypeReference<>() {});
            List<LLMProvider.Suggestion> result = new ArrayList<>();
            for (Map<String, Object> item : items) {
                Object ss = item.get("sentence_start");
                Object se = item.get("sentence_end");
                if (ss instanceof Number sNum && se instanceof Number eNum && globalChunks != null) {
                    int startIdx = sNum.intValue();
                    int endIdx = eNum.intValue();
                    if (startIdx >= 0 && startIdx < globalChunks.size()
                            && endIdx >= 0 && endIdx < globalChunks.size()
                            && endIdx >= startIdx) {
                        double start = globalChunks.get(startIdx).start();
                        double end = globalChunks.get(endIdx).end();
                        String title = (String) item.getOrDefault("title", "Clip");
                        String reason = (String) item.getOrDefault("reason", "");
                        double confidence = toDouble(item.getOrDefault("confidence", 0.5));
                        result.add(new LLMProvider.Suggestion(start, end, title, reason, confidence));
                        continue;
                    }
                }
                double start = toDouble(item.get("start"));
                double end = toDouble(item.get("end"));
                String title = (String) item.getOrDefault("title", "Clip");
                String reason = (String) item.getOrDefault("reason", "");
                double confidence = toDouble(item.getOrDefault("confidence", 0.5));
                result.add(new LLMProvider.Suggestion(start, end, title, reason, confidence));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractJsonArray(String text) {
        if (text == null) return null;
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private String formatTime(double seconds) {
        int total = (int) Math.round(seconds);
        int mins = total / 60;
        int secs = total % 60;
        return String.format("%d:%02d", mins, secs);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
