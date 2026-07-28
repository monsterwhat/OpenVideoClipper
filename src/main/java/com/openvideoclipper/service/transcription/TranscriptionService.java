package com.openvideoclipper.service.transcription;

import com.openvideoclipper.config.OvcConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@ApplicationScoped
public class TranscriptionService {

    @Inject
    Instance<TranscriptionProvider> providers;

    @Inject
    OvcConfig config;

    public TranscriptionProvider getActiveProvider() {
        String id = config.getTranscriptionProvider();
        for (TranscriptionProvider p : providers) {
            if (p.id().equals(id)) return p;
        }
        for (TranscriptionProvider p : providers) {
            return p;
        }
        throw new IllegalStateException("No transcription providers available");
    }

    public List<TranscriptionProvider> getAllProviders() {
        List<TranscriptionProvider> list = new ArrayList<>();
        for (TranscriptionProvider p : providers) {
            list.add(p);
        }
        return list;
    }

    public TranscriptionProvider.TranscriptionResult transcribe(Path audioFile) {
        return getActiveProvider().transcribe(audioFile);
    }

    public TranscriptionProvider.TranscriptionResult transcribe(Path audioFile, Consumer<Integer> progressCallback) {
        return getActiveProvider().transcribe(audioFile, progressCallback);
    }
}
