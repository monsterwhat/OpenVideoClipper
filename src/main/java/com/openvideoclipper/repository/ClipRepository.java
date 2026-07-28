package com.openvideoclipper.repository;

import com.openvideoclipper.entity.Clip;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ClipRepository implements PanacheRepositoryBase<Clip, UUID> {

    public List<Clip> findByJobId(UUID jobId) {
        return list("job.id = ?1 ORDER BY createdAt", jobId);
    }
}
