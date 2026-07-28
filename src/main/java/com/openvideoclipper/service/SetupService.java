package com.openvideoclipper.service;

import com.openvideoclipper.config.OvcConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;

@ApplicationScoped
public class SetupService {

    @Inject
    OvcConfig config;

    @Inject
    PrerequisiteService prereqService;

    public boolean isConfigured() {
        return config.isConfigured();
    }

    public void saveConfig(String storagePath, String ollamaUrl, String ollamaModel, String provider) {
        config.setStoragePath(Paths.get(storagePath).toAbsolutePath().normalize());
        config.setOllamaUrl(ollamaUrl);
        config.setOllamaModel(ollamaModel);
        config.setTranscriptionProvider(provider);
        config.setConfigured(true);
        config.save();
    }

    public void markNotConfigured() {
        config.setConfigured(false);
    }
}
