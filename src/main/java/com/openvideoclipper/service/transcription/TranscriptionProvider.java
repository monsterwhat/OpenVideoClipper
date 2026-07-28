package com.openvideoclipper.service.transcription;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public interface TranscriptionProvider {
    String id();
    String displayName();
    boolean isAvailable();
    TranscriptionResult transcribe(Path audioFile) throws TranscriptionException;

    default TranscriptionResult transcribe(Path audioFile, Consumer<Integer> progressCallback) throws TranscriptionException {
        return transcribe(audioFile);
    }

    record TranscriptionResult(
        String fullText,
        List<WordTimestamp> words,
        double durationSeconds
    ) {}

    record WordTimestamp(String word, double start, double end) {}

    class TranscriptionException extends RuntimeException {
        public TranscriptionException(String message, Throwable cause) {
            super(message, cause);
        }
        public TranscriptionException(String message) {
            super(message);
        }
    }
}
