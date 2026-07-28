package com.openvideoclipper.service;

import static com.openvideoclipper.utils.LogUtil.error;
import com.openvideoclipper.config.OvcConfig;
import com.openvideoclipper.entity.TranscriptionChunk;
import com.openvideoclipper.entity.VideoJob;
import com.openvideoclipper.repository.TranscriptionChunkRepository;
import com.openvideoclipper.repository.VideoJobRepository;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TranscriptionChunkBatchService {

    @Inject
    TranscriptionChunkRepository chunkRepo;

    @Inject
    VideoJobRepository jobRepo;

    @Inject
    OvcConfig config;

    @Inject
    UserTransaction transaction;

    private final Map<UUID, List<TranscriptionChunk>> pendingByJob = new ConcurrentHashMap<>();

    public void addChunk(UUID jobId, int chunkIndex, String text, double startTime, double endTime) {
        List<TranscriptionChunk> chunks = pendingByJob.computeIfAbsent(jobId, k -> new ArrayList<>());

        TranscriptionChunk chunk = new TranscriptionChunk();
        // Don't set job here — no request context on background thread.
        // Job reference is set inside flush() where we have an active request context and transaction.
        chunk.setChunkIndex(chunkIndex);
        chunk.setText(text);
        chunk.setStartTime(startTime);
        chunk.setEndTime(endTime);

        synchronized (chunks) {
            chunks.add(chunk);
            if (chunks.size() >= config.getTranscriptionBatchSize()) {
                flush(jobId, chunks);
            }
        }
    }

    public void flush() {
        for (UUID jobId : pendingByJob.keySet()) {
            List<TranscriptionChunk> chunks = pendingByJob.get(jobId);
            if (chunks != null) {
                synchronized (chunks) {
                    if (!chunks.isEmpty()) {
                        flush(jobId, chunks);
                    }
                }
            }
        }
    }

    private void flush(UUID jobId, List<TranscriptionChunk> chunks) {
        var rc = Arc.container().requestContext();
        rc.activate();
        try {
            transaction.begin();
            // Look up job inside the active request context + transaction
            VideoJob job = jobRepo.findById(jobId);
            if (job == null) {
                error("[TranscriptionChunkBatchService] Job " + jobId + " not found, aborting flush");
                transaction.rollback();
                return;
            }
            for (TranscriptionChunk chunk : chunks) {
                chunk.setJob(job);
                chunkRepo.persist(chunk);
            }
            transaction.commit();
            chunks.clear();
        } catch (Exception e) {
            error("[TranscriptionChunkBatchService] Failed to flush chunks for job " + jobId + ": " + e.getMessage());
            try { transaction.rollback(); } catch (Exception ignored) {}
        } finally {
            rc.deactivate();
        }
    }
}
