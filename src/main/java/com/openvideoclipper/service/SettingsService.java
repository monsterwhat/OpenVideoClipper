package com.openvideoclipper.service;

import com.openvideoclipper.config.OvcConfig;
import com.openvideoclipper.service.transcription.TranscriptionProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SettingsService {

    @Inject
    OvcConfig config;

    @Inject
    Instance<TranscriptionProvider> transcriptionProviders;

    @Inject
    OllamaModelService ollamaModelService;

    public void saveSettings(
            String ollamaUrl,
            String ollamaModel,
            String provider,
            String transcriptionModel,
            int transcriptionBatchSize,
            String analysisModel,
            String refinementModel,
            double analysisTemperature,
            int analysisSegmentSize,
            int analysisOverlapWords,
            int analysisTimeoutMinutes,
            int analysisMaxSuggestions,
            String analysisPrompt,
            String clipCodec,
            String clipFormat
    ) {
        if (ollamaUrl != null && !ollamaUrl.isBlank()) {
            config.setOllamaUrl(ollamaUrl.trim());
        }
        if (ollamaModel != null && !ollamaModel.isBlank()) {
            config.setOllamaModel(ollamaModel.trim());
        }
        if (provider != null && !provider.isBlank()) {
            config.setTranscriptionProvider(provider.trim());
        }
        if (transcriptionModel != null && !transcriptionModel.isBlank()) {
            config.setTranscriptionModel(transcriptionModel.trim());
        }
        config.setTranscriptionBatchSize(transcriptionBatchSize);
        if (analysisModel != null && !analysisModel.isBlank()) {
            config.setAnalysisModel(analysisModel.trim());
        }
        if (refinementModel != null) {
            config.setRefinementModel(refinementModel.trim());
        }
        config.setAnalysisTemperature(analysisTemperature);
        config.setAnalysisSegmentSize(analysisSegmentSize);
        config.setAnalysisOverlapWords(analysisOverlapWords);
        config.setAnalysisTimeoutMinutes(analysisTimeoutMinutes);
        config.setAnalysisMaxSuggestions(analysisMaxSuggestions);
        if (analysisPrompt != null) {
            config.setAnalysisPrompt(analysisPrompt.trim());
        }
        if (clipCodec != null && !clipCodec.isBlank()) {
            config.setClipCodec(clipCodec.trim());
        }
        if (clipFormat != null && !clipFormat.isBlank()) {
            config.setClipFormat(clipFormat.trim());
        }
        config.save();
    }

    public List<TranscriptionProvider> getAllTranscriptionProviders() {
        List<TranscriptionProvider> list = new ArrayList<>();
        for (TranscriptionProvider p : transcriptionProviders) {
            list.add(p);
        }
        return list;
    }

    public List<String> fetchOllamaModels() {
        return ollamaModelService.fetchModels(config.getOllamaUrl());
    }
}
