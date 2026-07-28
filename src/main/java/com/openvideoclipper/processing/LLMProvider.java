package com.openvideoclipper.processing;

import com.openvideoclipper.service.transcription.TranscriptionProvider;
import java.util.List;
import java.util.UUID;

public interface LLMProvider {

    List<Suggestion> extractHighlights(TranscriptionProvider.TranscriptionResult transcription);

    List<Suggestion> extractHighlights(TranscriptionProvider.TranscriptionResult transcription, String userPrompt);

    /** Extract highlights while avoiding duplicates of existing clips (e.g. locked suggestions from a previous run). */
    default List<Suggestion> extractHighlights(TranscriptionProvider.TranscriptionResult transcription, String userPrompt, List<Suggestion> existingClips) {
        return extractHighlights(transcription, userPrompt);
    }

    /** Set job context for progress reporting during analysis. No-op default. */
    default void setJobContext(UUID jobId) {}

    /** Clear job context after analysis completes. */
    default void clearJobContext() {}

    record Suggestion(double start, double end, String title, String reason, double confidence) {}
}
