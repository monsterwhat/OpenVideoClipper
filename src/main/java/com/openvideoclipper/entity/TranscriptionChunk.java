package com.openvideoclipper.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transcription_chunks",
    uniqueConstraints = @UniqueConstraint(columnNames = {"job_id", "chunk_index"}),
    indexes = {
        @Index(name = "idx_tc_job_id", columnList = "job_id"),
        @Index(name = "idx_tc_job_index", columnList = "job_id, chunk_index")
    })
public class TranscriptionChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private VideoJob job;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text = "";

    @Column(name = "start_time", nullable = false)
    private double startTime;

    @Column(name = "end_time", nullable = false)
    private double endTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public VideoJob getJob() { return job; }
    public void setJob(VideoJob job) { this.job = job; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public double getStartTime() { return startTime; }
    public void setStartTime(double startTime) { this.startTime = startTime; }

    public double getEndTime() { return endTime; }
    public void setEndTime(double endTime) { this.endTime = endTime; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}