package com.openvideoclipper.repository;

import com.openvideoclipper.entity.SceneBoundary;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SceneBoundaryRepository implements PanacheRepositoryBase<SceneBoundary, UUID> {

    public List<SceneBoundary> findByJobIdOrderBySceneIndex(UUID jobId) {
        return list("job.id = ?1 ORDER BY sceneIndex", jobId);
    }

    public void deleteByJobId(UUID jobId) {
        delete("job.id = ?1", jobId);
    }
}
