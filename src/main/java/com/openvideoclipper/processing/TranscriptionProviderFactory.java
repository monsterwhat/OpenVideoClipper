package com.openvideoclipper.processing;

import com.openvideoclipper.service.transcription.TranscriptionProvider;
import com.openvideoclipper.service.transcription.TranscriptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class TranscriptionProviderFactory {

    @Inject
    TranscriptionService transcriptionService;

    public TranscriptionProvider getProvider() {
        return transcriptionService.getActiveProvider();
    }
}
