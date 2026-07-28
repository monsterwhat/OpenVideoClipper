package com.openvideoclipper.repository;

import com.openvideoclipper.entity.ClipSuggestion;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ClipSuggestionRepository implements PanacheRepositoryBase<ClipSuggestion, UUID> {

    public List<ClipSuggestion> findByJobId(UUID jobId) {
        return list("job.id = ?1 ORDER BY confidenceScore DESC", jobId);
    }

    public List<ClipSuggestion> findSelectedByJobId(UUID jobId) {
        return list("job.id = ?1 AND isSelected = true ORDER BY startTimeSeconds", jobId);
    }

    public long countSelectedByJobId(UUID jobId) {
        return count("job.id = ?1 AND isSelected = true", jobId);
    }
}
