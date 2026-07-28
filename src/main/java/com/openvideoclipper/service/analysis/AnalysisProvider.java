package com.openvideoclipper.service.analysis;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface AnalysisProvider {
    String id();
    String displayName();
    boolean isAvailable();
    AnalysisResult analyze(Path videoPath, Path audioPath) throws AnalysisException;

    record AnalysisResult(
        String type,
        List<AnalysisEvent> events
    ) {}

    record AnalysisEvent(
        double start,
        double end,
        String label,
        double confidence,
        Map<String, Object> metadata
    ) {}

    class AnalysisException extends RuntimeException {
        public AnalysisException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
