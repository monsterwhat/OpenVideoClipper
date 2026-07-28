package com.openvideoclipper.service;

import com.openvideoclipper.config.OvcConfig;
import com.openvideoclipper.entity.*;
import com.openvideoclipper.processing.JobExecutionManager;
import com.openvideoclipper.processing.LLMProvider;
import com.openvideoclipper.processing.LLMProviderFactory;
import com.openvideoclipper.processing.TranscriptionProviderFactory;
import com.openvideoclipper.repository.ClipSuggestionRepository;
import com.openvideoclipper.repository.TranscriptionChunkRepository;
import com.openvideoclipper.repository.VideoJobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvideoclipper.service.transcription.TranscriptionProvider;
import static com.openvideoclipper.utils.LogUtil.info;
import static com.openvideoclipper.utils.LogUtil.error;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.UserTransaction;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@ApplicationScoped
public class VideoService {

    @Inject
    VideoJobRepository jobRepo;

    @Inject
    ClipSuggestionRepository suggestionRepo;

    @Inject
    TranscriptionChunkRepository chunkRepo;

    @Inject
    TransactionSynchronizationRegistry txSync;

    @Inject
    JobExecutionManager executionManager;

    @Inject
    TranscriptionProviderFactory transcriptionFactory;

    @Inject
    LLMProviderFactory llmFactory;

    @Inject
    VideoClippingService clippingService;

    @Inject
    TranscriptionChunkBatchService chunkBatchService;

    @Inject
    OvcConfig config;

    @Inject
    UserTransaction transaction;

    private final ObjectMapper mapper = new ObjectMapper();

    void resetStuckJobs(@Observes StartupEvent ev) {
        boolean ownTx = false;
        try {
            if (transaction.getStatus() == jakarta.transaction.Status.STATUS_NO_TRANSACTION) {
                transaction.begin();
                ownTx = true;
            }
            List<VideoJob> stuck = jobRepo.findByStatusIn(List.of(
                JobStatus.TRANSCRIBING, JobStatus.ANALYZING, JobStatus.CLIPPING
            ));
            info("[VideoService] Startup: resetting " + stuck.size() + " stuck jobs");
            for (VideoJob job : stuck) {
                info("[VideoService]   -> " + job.getId() + " (" + job.getOriginalFilename() + ") was " + job.getStatus() + " -> FAILED");
                job.setStatus(JobStatus.FAILED);
                job.setErrorMessage("Processing interrupted by application restart");
            }
            if (ownTx) {
                transaction.commit();
                info("[VideoService] Startup: reset complete, tx committed");
            }
        } catch (Exception e) {
            try { if (ownTx) transaction.rollback(); } catch (Exception ignored) {}
            info("[VideoService] Startup: failed to reset stuck jobs: " + e.getMessage());
        }
    }

    public VideoJob uploadVideo(String filename, InputStream data, long size) {
        info("[VideoService] uploadVideo: filename=" + filename + ", size=" + size);
        try {
            UUID jobId;
            transaction.begin();
            VideoJob job = new VideoJob();
            job.setOriginalFilename(filename);
            job.setStatus(JobStatus.UPLOADED);
            job.setFilePath("");
            jobRepo.persist(job);
            transaction.commit();
            jobId = job.getId();
            info("[VideoService] uploadVideo: job created, id=" + jobId);

            Path targetPath = config.getProjectVideoPath(jobId).resolve(filename);
            Files.copy(data, targetPath, StandardCopyOption.REPLACE_EXISTING);
            info("[VideoService] uploadVideo: file copied to " + targetPath);

            // Set file path immediately; duration is extracted async in continueProcessingImpl
            transaction.begin();
            VideoJob updated = jobRepo.findById(jobId);
            updated.setFilePath(targetPath.toAbsolutePath().toString());
            jobRepo.persist(updated);
            transaction.commit();
            info("[VideoService] uploadVideo: complete for job " + jobId);
            return updated;
        } catch (Exception e) {
            try { transaction.rollback(); } catch (Exception ignored) {}
            info("[VideoService] uploadVideo: failed - " + e.getMessage());
            VideoJob failed = new VideoJob();
            failed.setOriginalFilename(filename);
            failed.setStatus(JobStatus.FAILED);
            failed.setErrorMessage("Upload failed: " + e.getMessage());
            return failed;
        }
    }

    public VideoJob createJobFromLocalFile(String filePath) {
        info("[VideoService] createJobFromLocalFile: filePath=" + filePath);
        VideoJob job = new VideoJob();
        Path videoPath = Path.of(filePath).toAbsolutePath().normalize();
        job.setOriginalFilename(videoPath.getFileName().toString());
        job.setStatus(JobStatus.UPLOADED);
        job.setFilePath(videoPath.toString());
        try {
            double duration = clippingService.getVideoDuration(videoPath);
            info("[VideoService] createJobFromLocalFile: duration=" + duration + "s");
            transaction.begin();
            job.setDurationSeconds(duration);
            jobRepo.persist(job);
            transaction.commit();
            info("[VideoService] createJobFromLocalFile: job created, id=" + job.getId());
        } catch (Exception e) {
            try { transaction.rollback(); } catch (Exception ignored) {}
            info("[VideoService] createJobFromLocalFile: failed - " + e.getMessage());
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Failed to read video duration: " + e.getMessage());
        }
        return job;
    }

    public void processVideo(UUID jobId) {
        info("[VideoService] processVideo: submitting job " + jobId + " to executor");
        executionManager.trySubmit(jobId, () -> continueProcessingImpl(jobId));
    }

    public void deleteJob(UUID jobId) {
        info("[VideoService] deleteJob: deleting job " + jobId);
        executionManager.cancelActive(jobId);
        try {
            transaction.begin();
            VideoJob job = jobRepo.findById(jobId);
            if (job == null) { transaction.rollback(); info("[VideoService] deleteJob: job not found"); return; }
            jobRepo.delete(job);
            transaction.commit();
            info("[VideoService] deleteJob: DB record deleted");
        } catch (Exception e) {
            try { transaction.rollback(); } catch (Exception ignored) {}
            info("[VideoService] deleteJob: DB delete failed - " + e.getMessage());
            throw new RuntimeException("Failed to delete job: " + e.getMessage(), e);
        }
        try {
            Path projectDir = config.getStoragePath().resolve(jobId.toString());
            if (Files.exists(projectDir)) {
                try (Stream<Path> walk = Files.walk(projectDir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                }
                info("[VideoService] deleteJob: project directory deleted");
            } else {
                info("[VideoService] deleteJob: project directory not found on disk");
            }
        } catch (IOException e) {
            info("[VideoService] deleteJob: failed to delete project directory - " + e.getMessage());
        }
    }

    public void retryJob(UUID jobId) {
        info("[VideoService] retryJob: resetting job " + jobId + " for retry");
        try {
            transaction.begin();
            VideoJob job = jobRepo.findById(jobId);
            if (job == null) { transaction.rollback(); throw new IllegalArgumentException("Job not found"); }

            Path videoPath = Path.of(job.getFilePath());
            if (!Files.exists(videoPath)) {
                transaction.rollback();
                info("[VideoService] retryJob: video file not found at " + job.getFilePath());
                throw new IllegalStateException("Video file not found at: " + job.getFilePath());
            }

            job.setStatus(JobStatus.UPLOADED);
            job.setErrorMessage(null);
            jobRepo.persist(job);
            transaction.commit();
            info("[VideoService] retryJob: job reset to UPLOADED");
        } catch (IllegalStateException e) {
            try { transaction.rollback(); } catch (Exception ignored) {}
            throw e;
        } catch (Exception e) {
            try { transaction.rollback(); } catch (Exception ignored) {}
            info("[VideoService] retryJob: reset failed - " + e.getMessage());
            throw new RuntimeException("Failed to reset job for retry", e);
        }

        executionManager.cancelActive(jobId);
        info("[VideoService] retryJob: submitting continueProcessing for job " + jobId);
        executionManager.trySubmit(jobId, () -> continueProcessingImpl(jobId));
    }

    private void continueProcessingImpl(UUID jobId) {
        info("[VideoService] continueProcessingImpl: starting for job " + jobId);
        // Activate request context for background thread to use Panache/UserTransaction
        var ctx = Arc.container().requestContext().activate();
        try {
            transaction.begin();
            VideoJob job = jobRepo.findById(jobId);
            if (job == null) { transaction.rollback(); throw new IllegalArgumentException("Job not found"); }
            info("[VideoService] continueProcessingImpl: current status=" + job.getStatus() + ", audio=" + (job.getAudioFilePath() != null ? "exists" : "null") + ", transcription=" + (job.getTranscriptionText() != null ? job.getTranscriptionText().length() + " chars" : "null") + ", suggestions=" + (job.getSuggestions() != null ? job.getSuggestions().size() : "null"));

            // Extract duration if deferred from upload (file copied, async processing starts it now)
            if (job.getDurationSeconds() == null || job.getDurationSeconds() <= 0) {
                Path vp = Path.of(job.getFilePath());
                if (Files.exists(vp)) {
                    try {
                        double dur = clippingService.getVideoDuration(vp);
                        job.setDurationSeconds(dur);
                        jobRepo.persist(job);
                        transaction.commit();
                        info("[VideoService] continueProcessingImpl: extracted duration=" + dur + "s");
                        // Re-fetch so entity is managed in the new transaction
                        transaction.begin();
                        job = jobRepo.findById(jobId);
                    } catch (Exception ed) {
                        info("[VideoService] continueProcessingImpl: duration extract failed: " + ed.getMessage());
                    }
                }
            }

            if (job.getAudioFilePath() == null || !Files.exists(Path.of(job.getAudioFilePath()))) {
                    info("[VideoService] continueProcessingImpl: audio missing, extracting + transcribing from scratch");
                    job.setStatus(JobStatus.TRANSCRIBING);
                    job.setErrorMessage(null);
                    jobRepo.persist(job);
                    transaction.commit();

                    Path videoPath = Path.of(job.getFilePath());
                    info("[VideoService] continueProcessingImpl: extracting audio...");
                    executionManager.setPhase(jobId, "extracting_audio");
                    executionManager.updateProgress(jobId, 0);
                    double extractDuration = job.getDurationSeconds() != null ? job.getDurationSeconds() : 0;
                    Path audioPath = clippingService.extractAudio(
                        jobId,
                        videoPath,
                        config.getProjectTranscriptionPath(jobId),
                        extractDuration,
                        pct -> {
                            executionManager.updateProgress(jobId, pct);
                            info("[VideoService] audio extraction progress: " + pct + "%");
                        }
                    );
                    info("[VideoService] continueProcessingImpl: audio extracted to " + audioPath);

                    transaction.begin();
                    VideoJob j2 = jobRepo.findById(jobId);
                    j2.setAudioFilePath(audioPath.toAbsolutePath().toString());
                    jobRepo.persist(j2);
                    transaction.commit();
                    info("[VideoService] continueProcessingImpl: audio path saved");

                    executionManager.setPhase(jobId, "transcribing");
                    executionManager.updateProgress(jobId, 0);
                    TranscriptionProvider.TranscriptionResult transcription = runStreamingTranscription(jobId, audioPath, 0);
                    chunkBatchService.flush();
                    info("[VideoService] continueProcessingImpl: transcription complete (" + (transcription.fullText() != null ? transcription.fullText().length() : 0) + " chars)");

                    transaction.begin();
                    VideoJob j3 = jobRepo.findById(jobId);
                    j3.setTranscriptionText(transcription.fullText());
                    j3.setWordTimestampsJson(mapper.writeValueAsString(transcription.words()));
                    j3.setDurationSeconds(transcription.durationSeconds());
                    j3.setStatus(JobStatus.TRANSCRIPTION_REVIEW);
                    jobRepo.persist(j3);
                    transaction.commit();
                    executionManager.clearProgress(jobId);
                    info("[VideoService] continueProcessingImpl: transcription saved, status=TRANSCRIPTION_REVIEW for job " + jobId);
                } else {
                    transaction.commit();
                    info("[VideoService] continueProcessingImpl: audio exists at " + job.getAudioFilePath());
                    if (job.getTranscriptionText() == null || job.getTranscriptionText().isBlank()) {
                        info("[VideoService] continueProcessingImpl: transcription missing, transcribing...");
                    transaction.begin();
                    VideoJob j2 = jobRepo.findById(jobId);
                    j2.setStatus(JobStatus.TRANSCRIBING);
                    j2.setErrorMessage(null);
                    jobRepo.persist(j2);
                    transaction.commit();

                    executionManager.setPhase(jobId, "transcribing");
                    executionManager.updateProgress(jobId, 0);
                    Path audioPath = Path.of(job.getAudioFilePath());
                    
                    // Check for existing chunks to resume
                    List<TranscriptionChunk> existingChunks = chunkRepo.findByJobIdOrderByChunkIndex(jobId);
                    int startChunk = existingChunks.isEmpty() ? 0 :
                        existingChunks.stream().mapToInt(TranscriptionChunk::getChunkIndex).max().orElse(-1) + 1;
                    info("[VideoService] continueProcessingImpl: resuming from chunk " + startChunk + " (found " + existingChunks.size() + " existing chunks, max index: " + (existingChunks.isEmpty() ? -1 : existingChunks.stream().mapToInt(TranscriptionChunk::getChunkIndex).max().orElse(-1)) + ")");
                    
                    TranscriptionProvider.TranscriptionResult transcription = runStreamingTranscription(jobId, audioPath, startChunk);
                    chunkBatchService.flush();
                    info("[VideoService] continueProcessingImpl: transcription complete (" + (transcription.fullText() != null ? transcription.fullText().length() : 0) + " chars)");

                    transaction.begin();
                    VideoJob j3 = jobRepo.findById(jobId);
                    j3.setTranscriptionText(transcription.fullText());
                    j3.setWordTimestampsJson(mapper.writeValueAsString(transcription.words()));
                    j3.setDurationSeconds(transcription.durationSeconds());
                    j3.setStatus(JobStatus.TRANSCRIPTION_REVIEW);
                    jobRepo.persist(j3);
                    transaction.commit();
                    executionManager.clearProgress(jobId);
                    info("[VideoService] continueProcessingImpl: transcription saved, status=TRANSCRIPTION_REVIEW for job " + jobId);
                } else {
                    info("[VideoService] continueProcessingImpl: transcription already exists, skipping");
                }
            }

            // Start a new transaction for the read (previous one was committed)
            // Keep it open through the lazy suggestions load to avoid LazyInitializationException
            transaction.begin();
            VideoJob refreshed = jobRepo.findById(jobId);

            String transcriptionText = refreshed.getTranscriptionText();
            String wordTimestampsJson = refreshed.getWordTimestampsJson();
            Double durationSeconds = refreshed.getDurationSeconds();
            // Force-load lazy collections while the session is active
            int suggestionCount = refreshed.getSuggestions() != null ? refreshed.getSuggestions().size() : 0;
            boolean hasSuggestions = suggestionCount > 0;
            String analysisPrompt = refreshed.getAnalysisPrompt();
            
            // All reads done — close the read transaction
            transaction.commit();
            info("[VideoService] continueProcessingImpl: after transcription step - transcriptionText=" + (transcriptionText != null ? transcriptionText.length() + " chars" : "null") + ", suggestions=" + suggestionCount);

            if (transcriptionText != null && !transcriptionText.isBlank()) {
                // Always run LLM if status is ANALYZING (re-analysis requested),
                // even if locked suggestions exist from a previous run.
                if (!hasSuggestions || refreshed.getStatus() == JobStatus.ANALYZING) {
                    info("[VideoService] continueProcessingImpl: suggestions missing, running LLM...");
                    TranscriptionProvider.TranscriptionResult transcription;
                    if (wordTimestampsJson != null && !wordTimestampsJson.isBlank()) {
                        List<TranscriptionProvider.WordTimestamp> words = mapper.readValue(wordTimestampsJson, mapper.getTypeFactory().constructCollectionType(List.class, TranscriptionProvider.WordTimestamp.class));
                        transcription = new TranscriptionProvider.TranscriptionResult(transcriptionText, words, durationSeconds != null ? durationSeconds : 0);
                    } else {
                        transcription = new TranscriptionProvider.TranscriptionResult(transcriptionText, List.of(), durationSeconds != null ? durationSeconds : 0);
                    }

                    // Update database status so progress steps render correctly
                    // (audio extraction and transcription were skipped since they already exist)
                    transaction.begin();
                    VideoJob statusJob = jobRepo.findById(jobId);
                    statusJob.setStatus(JobStatus.ANALYZING);
                    jobRepo.persist(statusJob);
                    transaction.commit();

                    executionManager.setPhase(jobId, "analyzing");
                    executionManager.updateProgress(jobId, 0);
                    info("[VideoService] continueProcessingImpl: calling LLM for highlights...");
                    LLMProvider provider = llmFactory.getProvider();
                    provider.setJobContext(jobId);
                    List<LLMProvider.Suggestion> highlights;
                    try {
                        String effectivePrompt = null;
                        if (analysisPrompt != null && !analysisPrompt.isBlank()) {
                            effectivePrompt = analysisPrompt;
                        } else {
                            String defaultPrompt = config.getAnalysisPrompt();
                            if (defaultPrompt != null && !defaultPrompt.isBlank()) {
                                effectivePrompt = defaultPrompt;
                            }
                        }
                        if (effectivePrompt != null) {
                            info("[VideoService] continueProcessingImpl: with prompt=" + effectivePrompt);
                            // Pass existing locked suggestions so the LLM avoids re-finding them
                            List<LLMProvider.Suggestion> existingClips = new ArrayList<>();
                            if (refreshed.getSuggestions() != null) {
                                for (var cs : refreshed.getSuggestions()) {
                                    if (Boolean.TRUE.equals(cs.getIsLocked())) {
                                        existingClips.add(new LLMProvider.Suggestion(
                                            cs.getStartTimeSeconds(), cs.getEndTimeSeconds(),
                                            cs.getTitle(), cs.getReason(), cs.getConfidenceScore()));
                                    }
                                }
                            }
                            if (!existingClips.isEmpty()) {
                                highlights = provider.extractHighlights(transcription, effectivePrompt, existingClips);
                            } else {
                                highlights = provider.extractHighlights(transcription, effectivePrompt);
                            }
                        } else {
                            highlights = provider.extractHighlights(transcription);
                        }
                    } finally {
                        provider.clearJobContext();
                    }
                    info("[VideoService] continueProcessingImpl: LLM returned " + highlights.size() + " suggestions");

                    transaction.begin();
                    VideoJob j4 = jobRepo.findById(jobId);
                    for (LLMProvider.Suggestion s : highlights) {
                        ClipSuggestion cs = new ClipSuggestion();
                        cs.setJob(j4);
                        cs.setStartTimeSeconds(s.start());
                        cs.setEndTimeSeconds(s.end());
                        cs.setTitle(s.title());
                        cs.setReason(s.reason());
                        cs.setConfidenceScore(s.confidence());
                        cs.setIsSelected(false);
                        suggestionRepo.persist(cs);
                        j4.getSuggestions().add(cs);
                    }
                    j4.setStatus(JobStatus.SUGGESTIONS_READY);
                    jobRepo.persist(j4);
                    transaction.commit();
                    info("[VideoService] continueProcessingImpl: suggestions saved, status=SUGGESTIONS_READY for job " + jobId);
                } else {
                    info("[VideoService] continueProcessingImpl: suggestions already exist (" + suggestionCount + "), setting SUGGESTIONS_READY");
                    try { transaction.begin(); } catch (Exception ignored) {}
                    VideoJob j5 = jobRepo.findById(jobId);
                    j5.setStatus(JobStatus.SUGGESTIONS_READY);
                    jobRepo.persist(j5);
                    transaction.commit();
                    info("[VideoService] continueProcessingImpl: status set to SUGGESTIONS_READY");
                }
            } else {
                info("[VideoService] continueProcessingImpl: no transcription text available, skipping suggestions");
            }
        } catch (Exception e) {
            info("[VideoService] continueProcessingImpl: failed");
            e.printStackTrace();
            try { transaction.rollback(); } catch (Exception ignored) {}
            try {
                transaction.begin();
                VideoJob job = jobRepo.findById(jobId);
                if (job != null) {
                    job.setStatus(JobStatus.FAILED);
                    StringBuilder errMsg = new StringBuilder();
                    Throwable cause = e;
                    while (cause != null) {
                        if (errMsg.length() > 0) errMsg.append(" | caused by: ");
                        errMsg.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
                        cause = cause.getCause();
                    }
                    job.setErrorMessage(errMsg.toString());
                    jobRepo.persist(job);
                }
                transaction.commit();
                info("[VideoService] continueProcessingImpl: job set to FAILED");
            } catch (Exception ex) {
                try { transaction.rollback(); } catch (Exception ignored) {}
                info("[VideoService] continueProcessingImpl: error marking job as FAILED - " + ex.getMessage());
            }
        }
    }

    private TranscriptionProvider.TranscriptionResult runStreamingTranscription(UUID jobId, Path audioPath, int startChunk) {
        TranscriptionProvider provider = transcriptionFactory.getProvider();
        if (!(provider instanceof com.openvideoclipper.service.transcription.ParakeetTranscriptionProvider parakeetProvider)) {
            return provider.transcribe(audioPath, pct -> executionManager.updateProgress(jobId, pct));
        }

        return parakeetProvider.transcribe(audioPath,
            pct -> executionManager.updateProgress(jobId, pct),
            chunk -> chunkBatchService.addChunk(
                jobId,
                chunk.getChunkIndex(),
                chunk.getText(),
                chunk.getStartTime(),
                chunk.getEndTime()
            ),
            startChunk,
            jobId
        );
    }

    public void relocateJobVideo(UUID jobId, String newFilePath) {
        info("[VideoService] relocateJobVideo: job=" + jobId + ", newFilePath=" + newFilePath);
        Path videoPath = Path.of(newFilePath).toAbsolutePath().normalize();
        if (!Files.exists(videoPath) || !Files.isRegularFile(videoPath)) {
            info("[VideoService] relocateJobVideo: file not found at " + newFilePath);
            throw new IllegalArgumentException("File not found: " + newFilePath);
        }
        double duration = clippingService.getVideoDuration(videoPath);
        info("[VideoService] relocateJobVideo: new file duration=" + duration + "s");

        try {
            transaction.begin();
            VideoJob job = jobRepo.findById(jobId);
            if (job == null) { transaction.rollback(); throw new IllegalArgumentException("Job not found"); }
            info("[VideoService] relocateJobVideo: relocating " + job.getOriginalFilename() + " from " + job.getFilePath() + " to " + videoPath);
            job.setFilePath(videoPath.toString());
            job.setDurationSeconds(duration);
            job.setAudioFilePath(null);
            job.setTranscriptionText(null);
            job.setWordTimestampsJson(null);
            job.setErrorMessage(null);
            if (job.getSuggestions() != null) job.getSuggestions().clear();
            if (job.getClips() != null) job.getClips().clear();
            if (job.getTranscriptionChunks() != null) job.getTranscriptionChunks().clear();
            job.setStatus(JobStatus.UPLOADED);
            jobRepo.persist(job);
            transaction.commit();
            info("[VideoService] relocateJobVideo: complete, status reset to UPLOADED");
        } catch (Exception e) {
            try { transaction.rollback(); } catch (Exception ignored) {}
            info("[VideoService] relocateJobVideo: failed - " + e.getMessage());
            throw new RuntimeException("Failed to relocate job video", e);
        }
    }

    @Transactional
    public void updateTranscription(UUID jobId, String text) {
        info("[VideoService] updateTranscription: job=" + jobId);
        VideoJob job = jobRepo.findById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Job not found");
        }
        job.setTranscriptionText(text);
        job.setWordTimestampsJson(null);
        if (job.getSuggestions() != null && !job.getSuggestions().isEmpty()) {
            job.getSuggestions().clear();
            info("[VideoService] updateTranscription: cleared " + job.getSuggestions().size() + " old suggestions");
        }
        job.setStatus(JobStatus.ANALYZING);
        jobRepo.persist(job);
        info("[VideoService] updateTranscription: job " + jobId + " updated and status set to ANALYZING");
        txSync.registerInterposedSynchronization(new jakarta.transaction.Synchronization() {
            @Override public void beforeCompletion() {}
            @Override public void afterCompletion(int status) {
                if (status == jakarta.transaction.Status.STATUS_COMMITTED) {
                    processVideo(jobId);
                }
            }
        });
    }

    @Transactional
    public void reanalyzeVideo(UUID jobId, String analysisPrompt) {
        info("[VideoService] reanalyzeVideo: job=" + jobId + ", prompt=" + analysisPrompt);
        VideoJob job = jobRepo.findById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Job not found");
        }
        if (analysisPrompt != null && !analysisPrompt.isBlank()) {
            job.setAnalysisPrompt(analysisPrompt.trim());
        }
        if (job.getSuggestions() != null) {
            // Explicitly delete from DB first, then clear the collection
            for (var it = job.getSuggestions().iterator(); it.hasNext();) {
                var s = it.next();
                if (!Boolean.TRUE.equals(s.getIsLocked())) {
                    it.remove();
                    suggestionRepo.delete(s);
                }
            }
        }
        if (job.getClips() != null) {
            job.getClips().clear();
        }
        job.setStatus(JobStatus.ANALYZING);
        job.setErrorMessage(null);
        jobRepo.persist(job);
        info("[VideoService] reanalyzeVideo: job " + jobId + " set to ANALYZING for re-analysis");
        txSync.registerInterposedSynchronization(new jakarta.transaction.Synchronization() {
            @Override public void beforeCompletion() {}
            @Override public void afterCompletion(int status) {
                if (status == jakarta.transaction.Status.STATUS_COMMITTED) {
                    processVideo(jobId);
                }
            }
        });
    }

    @Transactional
    public void toggleSuggestionLock(UUID jobId, UUID suggestionId) {
        ClipSuggestion s = suggestionRepo.findById(suggestionId);
        if (s != null && s.getJob().getId().equals(jobId)) {
            s.setIsLocked(!Boolean.TRUE.equals(s.getIsLocked()));
            suggestionRepo.persist(s);
        }
    }

    @Transactional
    public void finalizeTranscription(UUID jobId) {
        finalizeTranscription(jobId, null);
    }

    @Transactional
    public void finalizeTranscription(UUID jobId, String analysisPrompt) {
        info("[VideoService] finalizeTranscription: job=" + jobId);
        rebuildTranscriptionFromChunks(jobId);
        VideoJob job = jobRepo.findById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Job not found");
        }
        if (analysisPrompt != null && !analysisPrompt.isBlank()) {
            job.setAnalysisPrompt(analysisPrompt.trim());
        }
        if (job.getSuggestions() != null && !job.getSuggestions().isEmpty()) {
            job.getSuggestions().clear();
            info("[VideoService] finalizeTranscription: cleared old suggestions");
        }
        job.setStatus(JobStatus.ANALYZING);
        jobRepo.persist(job);
        info("[VideoService] finalizeTranscription: job " + jobId + " set to ANALYZING");
        txSync.registerInterposedSynchronization(new jakarta.transaction.Synchronization() {
            @Override public void beforeCompletion() {}
            @Override public void afterCompletion(int status) {
                if (status == jakarta.transaction.Status.STATUS_COMMITTED) {
                    processVideo(jobId);
                }
            }
        });
    }

    @Transactional
    public void toggleSuggestion(UUID jobId, UUID suggestionId) {
        ClipSuggestion s = suggestionRepo.findById(suggestionId);
        if (s != null && s.getJob().getId().equals(jobId)) {
            s.setIsSelected(!Boolean.TRUE.equals(s.getIsSelected()));
            suggestionRepo.persist(s);
        }
    }

    @Transactional
    public void updateSuggestionTime(UUID jobId, UUID suggestionId, double start, double end) {
        ClipSuggestion s = suggestionRepo.findById(suggestionId);
        if (s != null && s.getJob().getId().equals(jobId)) {
            s.setStartTimeSeconds(start);
            s.setEndTimeSeconds(end);
            suggestionRepo.persist(s);
        }
    }

    @Transactional
    public void selectAllSuggestions(UUID jobId) {
        for (ClipSuggestion s : suggestionRepo.findByJobId(jobId)) {
            s.setIsSelected(true);
            suggestionRepo.persist(s);
        }
    }

    public VideoJob getJob(UUID id) {
        return jobRepo.findById(id);
    }

    public List<VideoJob> getAllJobs() {
        return jobRepo.findAllOrdered();
    }

    public List<ClipSuggestion> getSuggestions(UUID jobId) {
        return suggestionRepo.findByJobId(jobId);
    }

    public List<Clip> getClips(UUID jobId) {
        VideoJob job = getJob(jobId);
        if (job == null) return List.of();
        return job.getClips();
    }

    public long countSelected(UUID jobId) {
        return suggestionRepo.countSelectedByJobId(jobId);
    }

    public int getTranscriptionProgress(UUID jobId) {
        return executionManager.getProgress(jobId);
    }

    @Transactional
    public void updateTranscriptionChunk(UUID jobId, int chunkIndex, String text) {
        TranscriptionChunk chunk = chunkRepo.findByJobIdAndChunkIndex(jobId, chunkIndex);
        if (chunk != null) {
            chunk.setText(text);
            chunkRepo.persist(chunk);
            rebuildTranscriptionFromChunks(jobId);
        }
    }

    @Transactional
    public void rebuildTranscriptionFromChunks(UUID jobId) {
        List<TranscriptionChunk> chunks = chunkRepo.findByJobIdOrderByChunkIndex(jobId);
        if (chunks.isEmpty()) {
            return;
        }

        StringBuilder fullTextBuilder = new StringBuilder();
        List<TranscriptionProvider.WordTimestamp> wordTimestamps = new ArrayList<>();

        for (TranscriptionChunk chunk : chunks) {
            String text = chunk.getText();
            if (text != null && !text.isBlank()) {
                if (fullTextBuilder.length() > 0) fullTextBuilder.append(" ");
                fullTextBuilder.append(text);

                String[] words = text.split("\\s+");
                double chunkDuration = chunk.getEndTime() - chunk.getStartTime();
                double timePerWord = words.length > 0 ? chunkDuration / words.length : 0;

                for (int i = 0; i < words.length; i++) {
                    double wordStart = chunk.getStartTime() + (i * timePerWord);
                    double wordEnd = chunk.getStartTime() + ((i + 1) * timePerWord);
                    wordTimestamps.add(new TranscriptionProvider.WordTimestamp(words[i], wordStart, wordEnd));
                }
            }
        }

        VideoJob job = jobRepo.findById(jobId);
        if (job != null) {
            job.setTranscriptionText(fullTextBuilder.toString());
            try {
                job.setWordTimestampsJson(mapper.writeValueAsString(wordTimestamps));
            } catch (Exception e) {
                error("[VideoService] Failed to serialize word timestamps: " + e.getMessage());
                job.setWordTimestampsJson(null);
            }
            job.setDurationSeconds(chunks.get(chunks.size() - 1).getEndTime());
            jobRepo.persist(job);
        }
    }
}
