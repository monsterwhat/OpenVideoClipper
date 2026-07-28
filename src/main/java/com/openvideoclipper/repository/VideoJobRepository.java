package com.openvideoclipper.repository;

import com.openvideoclipper.entity.JobStatus;
import com.openvideoclipper.entity.VideoJob;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class VideoJobRepository implements PanacheRepositoryBase<VideoJob, UUID> {

    public List<VideoJob> findAllOrdered() {
        return list("ORDER BY createdAt DESC");
    }

    public List<VideoJob> findByStatusIn(List<JobStatus> statuses) {
        return list("status IN ?1", statuses);
    }
}
