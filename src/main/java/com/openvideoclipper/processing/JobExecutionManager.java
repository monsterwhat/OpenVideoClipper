package com.openvideoclipper.processing;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class JobExecutionManager {

    private static final int MAX_CONCURRENT_JOBS = 4;

    private final Set<UUID> activeJobs = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Process> activeProcesses = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_JOBS, new ThreadFactory() {
        private final AtomicLong counter = new AtomicLong(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "job-worker-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    public boolean isActive(UUID jobId) {
        return activeJobs.contains(jobId);
    }

    public CompletableFuture<Void> submit(UUID jobId, Runnable task) {
        if (!activeJobs.add(jobId)) {
            throw new IllegalStateException("Job " + jobId + " is already being processed");
        }
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } finally {
                activeJobs.remove(jobId);
                progress.remove(jobId);
                phases.remove(jobId);
                activeProcesses.remove(jobId);
            }
        }, executor);
    }

    public boolean trySubmit(UUID jobId, Runnable task) {
        if (!activeJobs.add(jobId)) {
            return false;
        }
        CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } finally {
                activeJobs.remove(jobId);
                progress.remove(jobId);
                activeProcesses.remove(jobId);
            }
        }, executor);
        return true;
    }

    private final ConcurrentHashMap<UUID, Integer> progress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> phases = new ConcurrentHashMap<>();

    public void updateProgress(UUID jobId, int percent) {
        if (percent >= 0 && percent <= 100) {
            progress.put(jobId, percent);
        }
    }

    public int getProgress(UUID jobId) {
        return progress.getOrDefault(jobId, 0);
    }

    public void setPhase(UUID jobId, String phase) {
        phases.put(jobId, phase);
    }

    public String getPhase(UUID jobId) {
        return phases.getOrDefault(jobId, "");
    }

    public void clearProgress(UUID jobId) {
        progress.remove(jobId);
        phases.remove(jobId);
    }

    public void trackProcess(UUID jobId, Process process) {
        activeProcesses.put(jobId, process);
    }

    public void cancelActive(UUID jobId) {
        activeJobs.remove(jobId);
        progress.remove(jobId);
        phases.remove(jobId);
        Process p = activeProcesses.remove(jobId);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    @PreDestroy
    void shutdown() {
        for (Process p : activeProcesses.values()) {
            if (p.isAlive()) p.destroyForcibly();
        }
        activeProcesses.clear();
        executor.shutdownNow();
    }
}
