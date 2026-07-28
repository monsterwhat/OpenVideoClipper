package com.openvideoclipper.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clips")
public class Clip {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private VideoJob job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suggestion_id", nullable = false)
    private ClipSuggestion suggestion;

    @Column(nullable = false)
    private String filePath;

    private Long fileSize;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public VideoJob getJob() { return job; }
    public void setJob(VideoJob job) { this.job = job; }

    public ClipSuggestion getSuggestion() { return suggestion; }
    public void setSuggestion(ClipSuggestion suggestion) { this.suggestion = suggestion; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getFilename() {
        String name = java.nio.file.Paths.get(filePath).getFileName().toString();
        return name;
    }
}
