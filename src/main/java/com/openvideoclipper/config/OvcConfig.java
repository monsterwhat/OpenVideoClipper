package com.openvideoclipper.config;

import com.openvideoclipper.utils.StorageUtils;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static com.openvideoclipper.utils.LogUtil.error;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;

@ApplicationScoped
@Named("OvcConfig")
public class OvcConfig {

    @ConfigProperty(name = "ovc.storage.path", defaultValue = "DEFAULT")
    String storagePathStr;

    @ConfigProperty(name = "ovc.ollama.url", defaultValue = "http://localhost:11434")
    String ollamaUrl;

    @ConfigProperty(name = "ovc.ollama.model", defaultValue = "qwen2.5")
    String ollamaModel;

    @ConfigProperty(name = "ovc.ollama.num-gpu", defaultValue = "-1")
    int ollamaNumGpu;

    @ConfigProperty(name = "ovc.ollama.num-ctx", defaultValue = "0")
    int ollamaNumCtx;

    @ConfigProperty(name = "ovc.ollama.keep-alive", defaultValue = "")
    String ollamaKeepAlive;

    @ConfigProperty(name = "ovc.transcription.provider", defaultValue = "parakeet")
    String transcriptionProvider;

    @ConfigProperty(name = "ovc.transcription.batch-size", defaultValue = "10")
    int transcriptionBatchSize;

    @ConfigProperty(name = "ovc.transcription.model", defaultValue = "large-v3")
    String transcriptionModel;

    @ConfigProperty(name = "ovc.transcription.cache-path", defaultValue = "")
    String transcriptionCachePath;

    @ConfigProperty(name = "ovc.analysis.model", defaultValue = "qwen2.5")
    String analysisModel;

    String refinementModel = "";

    @ConfigProperty(name = "ovc.analysis.temperature", defaultValue = "0.0")
    double analysisTemperature;

    @ConfigProperty(name = "ovc.analysis.segment-size", defaultValue = "500")
    int analysisSegmentSize;

    @ConfigProperty(name = "ovc.analysis.overlap-words", defaultValue = "0")
    int analysisOverlapWords;

    @ConfigProperty(name = "ovc.analysis.timeout-minutes", defaultValue = "5")
    int analysisTimeoutMinutes;

    @ConfigProperty(name = "ovc.analysis.max-suggestions", defaultValue = "15")
    int analysisMaxSuggestions;

    String analysisPrompt = "Identify the most interesting, exciting, funny, or key sections that would make good standalone video clips. Each suggestion MUST represent a complete, clippable moment \u2014 aim for 15\u201390 seconds per clip. Avoid very short snippets (under 10 seconds) unless the moment is extremely punchy. Prioritize segments with natural start and end boundaries (complete sentences, topic transitions).";

    @ConfigProperty(name = "ovc.clip.codec", defaultValue = "copy")
    String clipCodec;

    @ConfigProperty(name = "ovc.clip.format", defaultValue = "mp4")
    String clipFormat;

    private Path storagePath;
    private boolean configured;
    private Path configFilePath;

    @PostConstruct
    void init() {
        String userHome = System.getProperty("user.home", ".");
        Path configDir = Paths.get(userHome, ".ovc");
        configFilePath = configDir.resolve("config.properties");
        if (Files.exists(configFilePath)) {
            loadFromFile();
        } else {
            if ("DEFAULT".equals(storagePathStr)) {
                this.storagePath = StorageUtils.getDefaultStoragePath();
            } else {
                this.storagePath = Paths.get(storagePathStr).toAbsolutePath().normalize();
            }
            this.configured = false;
        }

        try {
            Files.createDirectories(storagePath);
        } catch (IOException e) {
            error("Failed to create storage directory: " + storagePath, e);
        }
    }

    private void loadFromFile() {
        Properties props = new Properties();
        try (var in = Files.newInputStream(configFilePath)) {
            props.load(in);
            String pathFromProps = props.getProperty("storage.path");
            if (pathFromProps != null) {
                this.storagePath = Paths.get(pathFromProps).toAbsolutePath().normalize();
            } else if ("DEFAULT".equals(storagePathStr)) {
                this.storagePath = StorageUtils.getDefaultStoragePath();
            } else {
                this.storagePath = Paths.get(storagePathStr).toAbsolutePath().normalize();
            }
            this.ollamaUrl = props.getProperty("ollama.url", ollamaUrl);
            this.ollamaModel = props.getProperty("ollama.model", ollamaModel);
            this.ollamaNumGpu = Integer.parseInt(props.getProperty("ollama.num-gpu", String.valueOf(ollamaNumGpu)));
            this.ollamaNumCtx = Integer.parseInt(props.getProperty("ollama.num-ctx", String.valueOf(ollamaNumCtx)));
            this.ollamaKeepAlive = props.getProperty("ollama.keep-alive", ollamaKeepAlive);
            this.transcriptionProvider = props.getProperty("transcription.provider", transcriptionProvider);
            this.transcriptionModel = props.getProperty("transcription.model", transcriptionModel);
            this.transcriptionCachePath = props.getProperty("transcription.cache-path", transcriptionCachePath);
            this.transcriptionBatchSize = Integer.parseInt(props.getProperty("transcription.batch-size", String.valueOf(transcriptionBatchSize)));
            this.analysisModel = props.getProperty("analysis.model", analysisModel);
            this.refinementModel = props.getProperty("analysis.refinement-model", refinementModel);
            this.analysisTemperature = Double.parseDouble(props.getProperty("analysis.temperature", String.valueOf(analysisTemperature)));
            this.analysisSegmentSize = Integer.parseInt(props.getProperty("analysis.segment-size", String.valueOf(analysisSegmentSize)));
            this.analysisOverlapWords = Integer.parseInt(props.getProperty("analysis.overlap-words", String.valueOf(analysisOverlapWords)));
            this.analysisTimeoutMinutes = Integer.parseInt(props.getProperty("analysis.timeout-minutes", String.valueOf(analysisTimeoutMinutes)));
            this.analysisMaxSuggestions = Integer.parseInt(props.getProperty("analysis.max-suggestions", String.valueOf(analysisMaxSuggestions)));
            this.analysisPrompt = props.getProperty("analysis.prompt", analysisPrompt);
            this.clipCodec = props.getProperty("clip.codec", clipCodec);
            this.clipFormat = props.getProperty("clip.format", clipFormat);
            this.configured = Boolean.parseBoolean(props.getProperty("configured", "false"));

            try {
                Files.createDirectories(storagePath);
            } catch (IOException e) {
                error("Failed to create storage directory from config: " + storagePath, e);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(configFilePath.getParent());
            Properties props = new Properties();
            props.setProperty("storage.path", storagePath.toString());
            props.setProperty("ollama.url", ollamaUrl);
            props.setProperty("ollama.model", ollamaModel);
            props.setProperty("ollama.num-gpu", String.valueOf(ollamaNumGpu));
            props.setProperty("ollama.num-ctx", String.valueOf(ollamaNumCtx));
            props.setProperty("ollama.keep-alive", ollamaKeepAlive);
            props.setProperty("transcription.provider", transcriptionProvider);
            props.setProperty("transcription.model", transcriptionModel);
            props.setProperty("transcription.cache-path", transcriptionCachePath);
            props.setProperty("transcription.batch-size", String.valueOf(transcriptionBatchSize));
            props.setProperty("analysis.model", analysisModel);
            if (refinementModel != null && !refinementModel.isBlank()) {
                props.setProperty("analysis.refinement-model", refinementModel);
            }
            props.setProperty("analysis.temperature", String.valueOf(analysisTemperature));
            props.setProperty("analysis.segment-size", String.valueOf(analysisSegmentSize));
            props.setProperty("analysis.overlap-words", String.valueOf(analysisOverlapWords));
            props.setProperty("analysis.timeout-minutes", String.valueOf(analysisTimeoutMinutes));
            props.setProperty("analysis.max-suggestions", String.valueOf(analysisMaxSuggestions));
            if (analysisPrompt != null) {
                props.setProperty("analysis.prompt", analysisPrompt);
            }
            props.setProperty("clip.codec", clipCodec);
            props.setProperty("clip.format", clipFormat);
            props.setProperty("configured", String.valueOf(configured));
            try (var out = Files.newOutputStream(configFilePath)) {
                props.store(out, "OVC Configuration");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config", e);
        }
    }

    public Path getStoragePath() { return storagePath; }
    public void setStoragePath(Path storagePath) { this.storagePath = storagePath; }

    public String getOllamaUrl() { return ollamaUrl; }
    public void setOllamaUrl(String ollamaUrl) { this.ollamaUrl = ollamaUrl; }

    public String getOllamaModel() { return ollamaModel; }
    public void setOllamaModel(String ollamaModel) { this.ollamaModel = ollamaModel; }

    public int getOllamaNumGpu() { return ollamaNumGpu; }
    public void setOllamaNumGpu(int ollamaNumGpu) { this.ollamaNumGpu = ollamaNumGpu; }

    public int getOllamaNumCtx() { return ollamaNumCtx; }
    public void setOllamaNumCtx(int ollamaNumCtx) { this.ollamaNumCtx = ollamaNumCtx; }

    public String getOllamaKeepAlive() { return ollamaKeepAlive; }
    public void setOllamaKeepAlive(String ollamaKeepAlive) { this.ollamaKeepAlive = ollamaKeepAlive; }

    public String getTranscriptionProvider() { return transcriptionProvider; }
    public void setTranscriptionProvider(String transcriptionProvider) { this.transcriptionProvider = transcriptionProvider; }

    public int getTranscriptionBatchSize() { return transcriptionBatchSize; }
    public void setTranscriptionBatchSize(int transcriptionBatchSize) { this.transcriptionBatchSize = transcriptionBatchSize; }

    public String getTranscriptionModel() { return transcriptionModel; }
    public void setTranscriptionModel(String transcriptionModel) { this.transcriptionModel = transcriptionModel; }

    public String getTranscriptionCachePath() { return transcriptionCachePath; }
    public void setTranscriptionCachePath(String transcriptionCachePath) { this.transcriptionCachePath = transcriptionCachePath; }

    public String getAnalysisModel() { return analysisModel; }
    public void setAnalysisModel(String analysisModel) { this.analysisModel = analysisModel; }

    public String getRefinementModel() { return refinementModel; }
    public void setRefinementModel(String refinementModel) { this.refinementModel = refinementModel; }

    public double getAnalysisTemperature() { return analysisTemperature; }
    public void setAnalysisTemperature(double analysisTemperature) { this.analysisTemperature = analysisTemperature; }

    public int getAnalysisSegmentSize() { return analysisSegmentSize; }
    public void setAnalysisSegmentSize(int analysisSegmentSize) { this.analysisSegmentSize = analysisSegmentSize; }

    public int getAnalysisOverlapWords() { return analysisOverlapWords; }
    public void setAnalysisOverlapWords(int analysisOverlapWords) { this.analysisOverlapWords = analysisOverlapWords; }

    public int getAnalysisTimeoutMinutes() { return analysisTimeoutMinutes; }
    public void setAnalysisTimeoutMinutes(int analysisTimeoutMinutes) { this.analysisTimeoutMinutes = analysisTimeoutMinutes; }

    public int getAnalysisMaxSuggestions() { return analysisMaxSuggestions; }
    public void setAnalysisMaxSuggestions(int analysisMaxSuggestions) { this.analysisMaxSuggestions = analysisMaxSuggestions; }

    public String getAnalysisPrompt() { return analysisPrompt; }
    public void setAnalysisPrompt(String analysisPrompt) { this.analysisPrompt = analysisPrompt; }

    public String getClipCodec() { return clipCodec; }
    public void setClipCodec(String clipCodec) { this.clipCodec = clipCodec; }

    public String getClipFormat() { return clipFormat; }
    public void setClipFormat(String clipFormat) { this.clipFormat = clipFormat; }

    public boolean isConfigured() { return configured; }
    public void setConfigured(boolean configured) { this.configured = configured; }

    public Path getProjectVideoPath(UUID jobId) {
        Path p = storagePath.resolve(jobId.toString()).resolve("video");
        try { Files.createDirectories(p); } catch (IOException ignored) {}
        return p;
    }

    public Path getProjectTranscriptionPath(UUID jobId) {
        Path p = storagePath.resolve(jobId.toString()).resolve("transcription");
        try { Files.createDirectories(p); } catch (IOException ignored) {}
        return p;
    }

    public Path getProjectClipsPath(UUID jobId) {
        Path p = storagePath.resolve(jobId.toString()).resolve("clips");
        try { Files.createDirectories(p); } catch (IOException ignored) {}
        return p;
    }
}
