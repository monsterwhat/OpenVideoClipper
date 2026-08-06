package com.openvideoclipper.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "scene_boundaries")
public class SceneBoundary {

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

    @Column(nullable = false)
    private Integer sceneIndex;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public VideoJob getJob() { return job; }
    public void setJob(VideoJob job) { this.job = job; }

    public Double getStartTimeSeconds() { return startTimeSeconds; }
    public void setStartTimeSeconds(Double startTimeSeconds) { this.startTimeSeconds = startTimeSeconds; }

    public Double getEndTimeSeconds() { return endTimeSeconds; }
    public void setEndTimeSeconds(Double endTimeSeconds) { this.endTimeSeconds = endTimeSeconds; }

    public Integer getSceneIndex() { return sceneIndex; }
    public void setSceneIndex(Integer sceneIndex) { this.sceneIndex = sceneIndex; }
}
