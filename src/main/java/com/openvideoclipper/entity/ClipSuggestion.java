package com.openvideoclipper.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "clip_suggestions")
public class ClipSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private VideoJob job;

    @Column(nullable = false)
    private Double startTimeSeconds;

    @Column(nullable = false)
    private Double endTimeSeconds;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String reason;

    private Double confidenceScore;

    @Column(nullable = false)
    private Boolean isSelected = false;

    @Column(nullable = false)
    private Boolean isLocked = false;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public VideoJob getJob() { return job; }
    public void setJob(VideoJob job) { this.job = job; }

    public Double getStartTimeSeconds() { return startTimeSeconds; }
    public void setStartTimeSeconds(Double startTimeSeconds) { this.startTimeSeconds = startTimeSeconds; }

    public Double getEndTimeSeconds() { return endTimeSeconds; }
    public void setEndTimeSeconds(Double endTimeSeconds) { this.endTimeSeconds = endTimeSeconds; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public int getConfidencePercent() {
        return confidenceScore != null ? (int) Math.round(confidenceScore * 100) : 0;
    }

    public Boolean getIsSelected() { return isSelected; }
    public void setIsSelected(Boolean isSelected) { this.isSelected = isSelected; }

    public Boolean getIsLocked() { return isLocked; }
    public void setIsLocked(Boolean isLocked) { this.isLocked = isLocked; }

    public String getFormattedStart() {
        return formatTime(startTimeSeconds);
    }

    public String getFormattedEnd() {
        return formatTime(endTimeSeconds);
    }

    public String getFormattedDuration() {
        double dur = endTimeSeconds - startTimeSeconds;
        return formatTime(dur);
    }

    private static String formatTime(Double seconds) {
        if (seconds == null) return "0:00";
        int totalSec = (int) Math.round(seconds);
        int mins = totalSec / 60;
        int secs = totalSec % 60;
        return String.format("%d:%02d", mins, secs);
    }
}
