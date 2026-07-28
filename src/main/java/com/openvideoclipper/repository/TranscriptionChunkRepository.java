package com.openvideoclipper.repository;

import com.openvideoclipper.entity.TranscriptionChunk;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class TranscriptionChunkRepository implements PanacheRepositoryBase<TranscriptionChunk, UUID> {

    public List<TranscriptionChunk> findByJobIdOrderByChunkIndex(UUID jobId) {
        return list("job.id = ?1 ORDER BY chunkIndex", jobId);
    }

    public long countByJobId(UUID jobId) {
        return count("job.id = ?1", jobId);
    }

    public void deleteByJobId(UUID jobId) {
        delete("job.id = ?1", jobId);
    }

    public TranscriptionChunk findByJobIdAndChunkIndex(UUID jobId, int chunkIndex) {
        return find("job.id = ?1 AND chunkIndex = ?2", jobId, chunkIndex).firstResult();
    }
}