package com.openvideoclipper.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "video_jobs")
@Check(constraints = "status IN ('UPLOADED','TRANSCRIBING','TRANSCRIPTION_REVIEW','ANALYZING','SUGGESTIONS_READY','CLIPPING','COMPLETED','FAILED')")
public class VideoJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    private String filePath;

    private String audioFilePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobStatus status = JobStatus.UPLOADED;

    @Column(length = 2000)
    private String errorMessage;

    private Double durationSeconds;

    @Column(columnDefinition = "TEXT")
    private String analysisPrompt;

    @Column(length = 500_000)
    private String transcriptionText;

    @Column(columnDefinition = "TEXT")
    private String wordTimestampsJson;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("confidenceScore DESC")
    private List<ClipSuggestion> suggestions = new ArrayList<>();

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Clip> clips = new ArrayList<>();

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("chunkIndex")
    private List<TranscriptionChunk> transcriptionChunks = new ArrayList<>();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getAudioFilePath() { return audioFilePath; }
    public void setAudioFilePath(String audioFilePath) { this.audioFilePath = audioFilePath; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Double getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Double durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getFormattedDuration() {
        if (durationSeconds == null) return "";
        int total = durationSeconds.intValue();
        return String.format("%d:%02d", total / 60, total % 60);
    }

    public String getAnalysisPrompt() { return analysisPrompt; }
    public void setAnalysisPrompt(String analysisPrompt) { this.analysisPrompt = analysisPrompt; }

    public String getTranscriptionText() { return transcriptionText; }
    public void setTranscriptionText(String transcriptionText) { this.transcriptionText = transcriptionText; }

    public String getWordTimestampsJson() { return wordTimestampsJson; }
    public void setWordTimestampsJson(String wordTimestampsJson) { this.wordTimestampsJson = wordTimestampsJson; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public List<ClipSuggestion> getSuggestions() { return suggestions; }
    public void setSuggestions(List<ClipSuggestion> suggestions) { this.suggestions = suggestions; }

    public List<Clip> getClips() { return clips; }
    public void setClips(List<Clip> clips) { this.clips = clips; }

    public List<TranscriptionChunk> getTranscriptionChunks() { return transcriptionChunks; }
    public void setTranscriptionChunks(List<TranscriptionChunk> transcriptionChunks) { this.transcriptionChunks = transcriptionChunks; }
}
