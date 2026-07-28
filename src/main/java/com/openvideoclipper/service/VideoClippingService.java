package com.openvideoclipper.service;

import com.openvideoclipper.config.OvcConfig;
import com.openvideoclipper.entity.*;
import com.openvideoclipper.processing.JobExecutionManager;
import com.openvideoclipper.repository.ClipRepository;
import com.openvideoclipper.repository.VideoJobRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class VideoClippingService {

    @Inject
    OvcConfig config;

    @Inject
    VideoJobRepository jobRepo;

    @Inject
    ClipRepository clipRepo;

    @Inject
    JobExecutionManager executionManager;

    private void trackProcess(UUID jobId, Process p) {
        if (jobId != null && p != null) {
            executionManager.trackProcess(jobId, p);
        }
    }

    @Transactional
    public void generateClips(UUID jobId) {
        VideoJob job = jobRepo.findById(jobId);
        if (job == null) throw new IllegalArgumentException("Job not found: " + jobId);

        job.setStatus(JobStatus.CLIPPING);
        jobRepo.persist(job);

        try {
            Path inputVideo = Path.of(job.getFilePath());
            int clipIndex = 0;

            for (ClipSuggestion suggestion : job.getSuggestions()) {
                if (!Boolean.TRUE.equals(suggestion.getIsSelected())) continue;

                String clipName = String.format("clip_%02d.mp4", clipIndex++);
                Path outputPath = config.getProjectClipsPath(jobId).resolve(clipName);

                runFfmpeg(jobId, inputVideo, outputPath,
                    suggestion.getStartTimeSeconds(),
                    suggestion.getEndTimeSeconds());

                Clip clip = new Clip();
                clip.setJob(job);
                clip.setSuggestion(suggestion);
                clip.setFilePath(outputPath.toAbsolutePath().toString());
                clip.setFileSize(Files.size(outputPath));
                clipRepo.persist(clip);
            }

            job.setStatus(JobStatus.COMPLETED);
        } catch (Exception e) {
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Clipping failed: " + e.getMessage());
        }
        jobRepo.persist(job);
    }

    private static final Pattern TIME_PATTERN = Pattern.compile("time=(\\d+):(\\d+):(\\d+)\\.(\\d+)");

    public Path extractAudio(UUID jobId, Path videoPath, Path destDir) {
        return extractAudio(jobId, videoPath, destDir, 0, null);
    }

    public Path extractAudio(UUID jobId, Path videoPath, Path destDir, double durationSeconds, Consumer<Integer> progressCallback) {
        Process p = null;
        try {
            String audioName = "audio.wav";
            Path outputPath = destDir.resolve(audioName);

            if (outputPath.toFile().exists()) {
                if (progressCallback != null) progressCallback.accept(100);
                return outputPath;
            }

            List<String> cmd = List.of(
                findFfmpeg(), "-i", videoPath.toAbsolutePath().toString(),
                "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1",
                "-y", outputPath.toAbsolutePath().toString()
            );

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            trackProcess(jobId, p);

            StringBuilder log = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.append(line).append("\n");
                    if (progressCallback != null && durationSeconds > 0) {
                        Matcher m = TIME_PATTERN.matcher(line);
                        if (m.find()) {
                            double elapsed = Integer.parseInt(m.group(1)) * 3600
                                           + Integer.parseInt(m.group(2)) * 60
                                           + Integer.parseInt(m.group(3))
                                           + Integer.parseInt(m.group(4)) / 100.0;
                            int pct = Math.min(99, (int) (elapsed / durationSeconds * 100));
                            progressCallback.accept(pct);
                        }
                    }
                }
            }

            boolean done = p.waitFor(5, TimeUnit.MINUTES);
            if (!done || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new IOException("FFmpeg audio extraction failed:\n" + log);
            }

            if (progressCallback != null) progressCallback.accept(100);
            return outputPath;
        } catch (IOException | InterruptedException e) {
            if (p != null && p.isAlive()) p.destroyForcibly();
            throw new RuntimeException("Failed to extract audio", e);
        }
    }

    public double getVideoDuration(Path videoPath) {
        try {
            List<String> cmd = List.of(
                findFfmpeg(), "-i", videoPath.toAbsolutePath().toString(),
                "-f", "null", "-"
            );
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder output = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            p.waitFor(30, TimeUnit.SECONDS);

            String log = output.toString();
            int idx = log.indexOf("Duration: ");
            if (idx >= 0) {
                String durStr = log.substring(idx + 10, idx + 21).trim();
                String[] parts = durStr.split(":");
                double hours = Double.parseDouble(parts[0]);
                double mins = Double.parseDouble(parts[1]);
                double secs = Double.parseDouble(parts[2]);
                return hours * 3600 + mins * 60 + secs;
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void runFfmpeg(UUID jobId, Path input, Path output, double start, double end) throws IOException, InterruptedException {
        double duration = end - start;

        List<String> cmd = List.of(
            findFfmpeg(), "-ss", formatTime(start),
            "-i", input.toAbsolutePath().toString(),
            "-t", formatTime(duration),
            "-c", "copy",
            "-avoid_negative_ts", "make_zero",
            "-y", output.toAbsolutePath().toString()
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        trackProcess(jobId, p);

        StringBuilder log = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.append(line).append("\n");
            }
        }

        boolean done = p.waitFor(5, TimeUnit.MINUTES);
        if (!done || p.exitValue() != 0) {
            if (p.isAlive()) p.destroyForcibly();
            throw new IOException("FFmpeg clipping failed:\n" + log);
        }
    }

    private String formatTime(double seconds) {
        int h = (int) seconds / 3600;
        int m = ((int) seconds % 3600) / 60;
        double s = seconds % 60;
        return String.format("%02d:%02d:%06.3f", h, m, s);
    }

    private String findFfmpeg() {
        return "ffmpeg";
    }
}
