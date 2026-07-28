package com.openvideoclipper.rest;

import com.openvideoclipper.config.OvcConfig;
import com.openvideoclipper.entity.*;
import com.openvideoclipper.processing.JobExecutionManager;
import com.openvideoclipper.repository.ClipRepository;
import com.openvideoclipper.repository.TranscriptionChunkRepository;
import com.openvideoclipper.service.*;
import com.openvideoclipper.service.transcription.TranscriptionProvider.WordTimestamp;
import com.openvideoclipper.service.transcription.TranscriptionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.UUID;

@Path("/")
@Produces(MediaType.TEXT_HTML)
public class OvcController {

    @Inject
    @Location("index.html")
    Template index;

    @Inject
    @Location("setup.html")
    Template setup;

    @Inject
    @Location("upload.html")
    Template upload;

    @Inject
    @Location("suggestions.html")
    Template suggestions;

    @Inject
    @Location("results.html")
    Template results;

    @Inject
    @Location("jobDetail.html")
    Template jobDetail;

    @Inject
    @Location("fragments/prereqCheck.html")
    Template fragmentsPrereqCheck;

    @Inject
    @Location("fragments/statusBar.html")
    Template fragmentsStatusBar;

    @Inject
    @Location("fragments/suggestionCard.html")
    Template fragmentsSuggestionCard;

    @Inject
    OvcConfig config;

    @Inject
    SetupService setupService;

    @Inject
    PrerequisiteService prereqService;

    @Inject
    VideoService videoService;

    @Inject
    VideoClippingService clippingService;

    @Inject
    TranscriptionService transcriptionService;

    @Inject
    JobExecutionManager executionManager;

    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    @Location("storageTreeFragment.html")
    Template storageTreeFragment;

    @Inject
    @Location("transcription.html")
    Template transcription;

    @Inject
    @Location("selectVideo.html")
    Template selectVideo;

    @Inject
    @Location("relocateVideo.html")
    Template relocateVideo;

    @Inject
    @Location("settings.html")
    Template settingsTemplate;

    @Inject
    @Location("fragments/browseFilesFragment.html")
    Template browseFilesFragment;

    @Inject
    ClipRepository clipRepo;

    @Inject
    TranscriptionChunkRepository chunkRepo;

    @Inject
    OllamaModelService ollamaModelService;

    @Inject
    SettingsService settingsService;

    @Inject
    StorageBrowseService storageBrowseService;

    // ─── Routes ───────────────────────────────────────────

    @GET
    public String home() {
        if (!setupService.isConfigured()) {
            return setup
                .data("pageTitle", "Clippy - Setup")
                .data("config", config)
                .data("providerOptions", transcriptionService.getAllProviders())
                .data("prereqs", prereqService.checkAll())
                .render();
        }
        return index
            .data("pageTitle", "Clippy")
            .data("jobs", videoService.getAllJobs())
            .render();
    }

    @GET
    @Path("setup")
    public String setupPage() {
        return setup
            .data("pageTitle", "Clippy - Setup")
            .data("config", config)
            .data("providerOptions", transcriptionService.getAllProviders())
            .data("prereqs", prereqService.checkAll())
            .render();
    }

    @POST
    @Path("setup")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response saveSetup(
        @FormParam("storagePath") String storagePath,
        @FormParam("ollamaUrl") String ollamaUrl,
        @FormParam("ollamaModel") String ollamaModel,
        @FormParam("provider") String provider
    ) {
        if (storagePath == null || storagePath.isBlank()) {
            storagePath = config.getStoragePath().toString();
        }
        setupService.saveConfig(
            storagePath,
            ollamaUrl != null && !ollamaUrl.isBlank() ? ollamaUrl : "http://localhost:11434",
            ollamaModel != null && !ollamaModel.isBlank() ? ollamaModel : "qwen2.5",
            provider != null && !provider.isBlank() ? provider : "parakeet"
        );
        return Response.seeOther(java.net.URI.create("/")).build();
    }

    @GET
    @Path("setup/check/{id}")
    public String checkPrereq(@PathParam("id") String id) {
        PrerequisiteService.Prerequisite result = switch (id) {
            case "python" -> prereqService.checkPython();
            case "ffmpeg" -> prereqService.checkFfmpeg();
            case "torch" -> prereqService.checkTorch();
            case "transformers" -> prereqService.checkTransformers();
            case "soundfile" -> prereqService.checkSoundfile();
            default -> new PrerequisiteService.Prerequisite(id, id, false, null, "Unknown");
        };
        return fragmentsPrereqCheck
            .data("prereq", result)
            .render();
    }

    @POST
    @Path("setup/test-ollama")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public String testOllama(@FormParam("ollamaUrl") String ollamaUrl) {
        List<String> models = ollamaModelService.fetchModels(ollamaUrl);
        StringBuilder sb = new StringBuilder("<option value=\"\">-- Select model --</option>");
        for (String m : models) {
            sb.append("<option value=\"").append(m).append("\" ")
              .append(m.equals(config.getOllamaModel()) ? "selected " : "")
              .append(">").append(m).append("</option>");
        }
        if (models.isEmpty()) {
            return "<option value=\"\" disabled>No models found - check URL</option>";
        }
        return sb.toString();
    }

    @GET
    @Path("setup/browse")
    @Produces(MediaType.TEXT_HTML)
    public String browseStorage(
            @QueryParam("path") String path,
            @QueryParam("currentPath") String currentPath) {
        
        String resolvedPath = path;
        if ("..".equals(path) && currentPath != null) {
            resolvedPath = Paths.get(currentPath).getParent().toString();
        } else if ("~".equals(path)) {
            resolvedPath = System.getProperty("user.home");
        }

        List<DirEntry> entries = storageBrowseService.browse(resolvedPath);
        return storageTreeFragment
            .data("entries", entries)
            .data("currentPath", resolvedPath)
            .render();
    }

    @POST
    @Path("setup/select-storage")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public String selectStorage(@FormParam("path") String path) {
        java.nio.file.Path p = Paths.get(path).toAbsolutePath().normalize();
        if (!storageBrowseService.isAllowed(p)) {
            return "<input class=\"input is-danger\" type=\"text\" name=\"storagePath\" value=\"Access denied\" readonly>";
        }
        return "<input class=\"input\" type=\"text\" name=\"storagePath\" value=\"" + p + "\" id=\"storagePathInput\" readonly>";
    }

    // ─── Settings ─────────────────────────────────────────

    @GET
    @Path("settings")
    public String settingsPage() {
        return settingsTemplate
            .data("pageTitle", "Settings")
            .data("config", config)
            .data("providerOptions", settingsService.getAllTranscriptionProviders())
            .data("ollamaModels", settingsService.fetchOllamaModels())
            .render();
    }

    @POST
    @Path("settings")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response saveSettings(
            @FormParam("ollamaUrl") String ollamaUrl,
            @FormParam("ollamaModel") String ollamaModel,
            @FormParam("provider") String provider,
            @FormParam("transcriptionModel") String transcriptionModel,
            @FormParam("transcriptionBatchSize") String transcriptionBatchSize,
            @FormParam("analysisModel") String analysisModel,
            @FormParam("refinementModel") String refinementModel,
            @FormParam("analysisTemperature") String analysisTemperature,
            @FormParam("analysisSegmentSize") String analysisSegmentSize,
            @FormParam("analysisOverlapWords") String analysisOverlapWords,
            @FormParam("analysisTimeoutMinutes") String analysisTimeoutMinutes,
            @FormParam("analysisMaxSuggestions") String analysisMaxSuggestions,
            @FormParam("analysisPrompt") String analysisPrompt,
            @FormParam("clipCodec") String clipCodec,
            @FormParam("clipFormat") String clipFormat
    ) {
        settingsService.saveSettings(
            ollamaUrl,
            ollamaModel,
            provider,
            transcriptionModel,
            parseOrDefault(transcriptionBatchSize, 10),
            analysisModel,
            refinementModel,
            parseOrDefault(analysisTemperature, 0.3),
            parseOrDefault(analysisSegmentSize, 3000),
            parseOrDefault(analysisOverlapWords, 300),
            parseOrDefault(analysisTimeoutMinutes, 5),
            parseOrDefault(analysisMaxSuggestions, 15),
            analysisPrompt,
            clipCodec,
            clipFormat
        );
        return Response.seeOther(java.net.URI.create("/settings?saved=true")).build();
    }

    private int parseOrDefault(String val, int def) {
        if (val == null || val.isBlank()) return def;
        try { return Integer.parseInt(val.trim()); } catch (NumberFormatException e) { return def; }
    }

    private double parseOrDefault(String val, double def) {
        if (val == null || val.isBlank()) return def;
        try { return Double.parseDouble(val.trim()); } catch (NumberFormatException e) { return def; }
    }

    // ─── Upload ───────────────────────────────────────────

    @GET
    @Path("upload")
    public String uploadPage() {
        return upload
            .data("pageTitle", "Clippy - Upload Video")
            .render();
    }

    @POST
    @Path("upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response handleUpload(@RestForm("file") FileUpload fileUpload) {
        if (fileUpload == null || fileUpload.filePath() == null || fileUpload.fileName() == null || fileUpload.fileName().isBlank()) {
            return Response.seeOther(java.net.URI.create("/upload?error=No file selected")).build();
        }

        try {
            java.nio.file.Path uploadedPath = fileUpload.filePath();
            String filename = fileUpload.fileName();

            VideoJob job;
            try (InputStream is = Files.newInputStream(uploadedPath)) {
                job = videoService.uploadVideo(filename, is, Files.size(uploadedPath));
            }

            UUID jobId = job.getId();
            videoService.processVideo(jobId);
            return Response.seeOther(java.net.URI.create("/job/" + jobId)).build();
        } catch (IOException e) {
            return Response.seeOther(java.net.URI.create("/upload?error=Upload failed: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("api/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleUploadApi(@RestForm("file") FileUpload fileUpload) {
        if (fileUpload == null || fileUpload.filePath() == null || fileUpload.fileName() == null || fileUpload.fileName().isBlank()) {
            return Response.status(400).entity(Map.of("error", "No file selected")).build();
        }

        try {
            java.nio.file.Path uploadedPath = fileUpload.filePath();
            String filename = fileUpload.fileName();

            VideoJob job;
            try (InputStream is = Files.newInputStream(uploadedPath)) {
                job = videoService.uploadVideo(filename, is, Files.size(uploadedPath));
            }

            UUID jobId = job.getId();
            videoService.processVideo(jobId);
            return Response.ok(Map.of(
                "jobId", jobId.toString(),
                "redirect", "/job/" + jobId
            )).build();
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", "Upload failed: " + e.getMessage())).build();
        }
    }

    // ─── Local Video Selection ───────────────────────────

    @GET
    @Path("job/select-video")
    public String selectVideoPage() {
        String startPath = config.getStoragePath().toString();
        List<FileEntry> entries = storageBrowseService.browseFiles(startPath);
        return selectVideo
            .data("pageTitle", "Select Local Video")
            .data("entries", entries)
            .data("currentPath", startPath)
            .render();
    }

    @GET
    @Path("browse-videos")
    @Produces(MediaType.TEXT_HTML)
    public String browseVideos(
            @QueryParam("path") String path,
            @QueryParam("currentPath") String currentPath) {
        String resolvedPath = path;
        if ("..".equals(path) && currentPath != null) {
            resolvedPath = Paths.get(currentPath).getParent().toString();
        } else if ("~".equals(path)) {
            resolvedPath = System.getProperty("user.home");
        }

        List<FileEntry> entries = storageBrowseService.browseFiles(resolvedPath);
        return browseFilesFragment
            .data("entries", entries)
            .data("currentPath", resolvedPath)
            .render();
    }

    @POST
    @Path("job/create-from-file")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createFromFile(@FormParam("filePath") String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Response.seeOther(java.net.URI.create("/job/select-video?error=No file selected")).build();
        }
        java.nio.file.Path p = Paths.get(filePath).toAbsolutePath().normalize();
        if (!Files.exists(p) || !Files.isRegularFile(p)) {
            return Response.seeOther(java.net.URI.create("/job/select-video?error=File not found")).build();
        }
        if (!storageBrowseService.isAllowed(p)) {
            return Response.seeOther(java.net.URI.create("/job/select-video?error=Access denied")).build();
        }
        VideoJob job = videoService.createJobFromLocalFile(filePath);
        UUID jobId = job.getId();
        videoService.processVideo(jobId);
        return Response.seeOther(java.net.URI.create("/job/" + jobId)).build();
    }

    // ─── Job ──────────────────────────────────────────────

    @GET
    @Path("job/{id}")
    public String jobPage(@PathParam("id") UUID id) {
        VideoJob job = videoService.getJob(id);
        if (job == null) return notFound();

        String formattedDuration = "";
        if (job.getDurationSeconds() != null) {
            int totalSec = job.getDurationSeconds().intValue();
            formattedDuration = String.format("%d:%02d", totalSec / 60, totalSec % 60);
        }
        int progressPct = videoService.getTranscriptionProgress(id);
        List<TranscriptionChunk> chunks = chunkRepo.findByJobIdOrderByChunkIndex(id);
        List<Map<String, Object>> chunkData = new ArrayList<>();
        int lastChunkIndex = -1;
        for (TranscriptionChunk chunk : chunks) {
            String startFmt = formatTime(chunk.getStartTime());
            String endFmt = formatTime(chunk.getEndTime());
            chunkData.add(Map.of(
                "index", chunk.getChunkIndex(),
                "text", chunk.getText(),
                "start", chunk.getStartTime(),
                "end", chunk.getEndTime(),
                "startFmt", startFmt,
                "endFmt", endFmt
            ));
            lastChunkIndex = Math.max(lastChunkIndex, chunk.getChunkIndex());
        }
        String rawPhase = executionManager.getPhase(id);
        String processingPhase = rawPhase != null ? rawPhase : "";
        PhaseInfo phaseInfo = computePhaseInfo(rawPhase);
        return jobDetail
            .data("pageTitle", "Job: " + job.getOriginalFilename())
            .data("job", job)
            .data("jobId", id.toString())
            .data("lastChunkIndex", lastChunkIndex)
            .data("suggestionCount", videoService.getSuggestions(id).size())
            .data("formattedDuration", formattedDuration)
            .data("progressSteps", computeProgressSteps(job.getStatus().name()))
            .data("transcriptionProgress", progressPct)
            .data("processingPhase", processingPhase)
            .data("processingPhaseLabel", phaseInfo.label())
            .data("chunks", chunkData)
            .render();
    }

    @Location("fragments/jobProgressFragment.html")
    Template jobProgressFragment;

    @GET
    @Path("job/{id}/status")
    public String jobStatus(@PathParam("id") UUID id) {
        VideoJob job = videoService.getJob(id);
        if (job == null) return fragmentsStatusBar.data("job", null).render();
        return fragmentsStatusBar
            .data("job", job)
            .render();
    }

    @GET
    @Path("job/{id}/progress")
    public String jobProgress(@PathParam("id") UUID id) {
        VideoJob job = videoService.getJob(id);
        if (job == null) return "<div>Job not found</div>";
        int progressPct = videoService.getTranscriptionProgress(id);
        String rawPhase = executionManager.getPhase(id);
        String processingPhase = rawPhase != null ? rawPhase : "";
        PhaseInfo phaseInfo = computePhaseInfo(processingPhase);

        return jobProgressFragment
            .data("job", job)
            .data("progressSteps", computeProgressSteps(job.getStatus().name()))
            .data("suggestionCount", videoService.getSuggestions(id).size())
            .data("transcriptionProgress", progressPct)
            .data("processingPhase", processingPhase)
            .data("processingPhaseLabel", phaseInfo.label())
            .data("phaseDetailLabel", phaseInfo.detailLabel())
            .data("phaseSubLabel", phaseInfo.subLabel())
            .render();
    }

    // ─── SSE Events ───────────────────────────────────────

    @GET
    @Path("job/{id}/events")
    @Produces("text/event-stream")
    public Response streamEvents(@PathParam("id") UUID id) {
        StreamingOutput stream = output -> {
            var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            int lastChunkIndex = -1;
            String lastPhase = "";
            String lastStatus = "";
            int lastProgress = -1;
            int lastSuggestionCount = -1;

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    VideoJob job = videoService.getJob(id);
                    if (job == null) {
                        writeSseEvent(writer, "done", Map.of("type", "done", "status", "FAILED", "error", "Job not found"));
                        writer.flush();
                        return;
                    }

                    String status = job.getStatus().name();
                    String phase = executionManager.getPhase(id);
                    if (phase == null) phase = "";
                    int progress = executionManager.getProgress(id);
                    List<ClipSuggestion> suggestions = videoService.getSuggestions(id);
                    int suggestionCount = suggestions.size();

                    boolean stateChanged = !phase.equals(lastPhase)
                        || !status.equals(lastStatus)
                        || progress != lastProgress
                        || suggestionCount != lastSuggestionCount;

                    if (stateChanged) {
                        PhaseInfo phaseInfo = computePhaseInfo(phase);
                        Map<String, Object> data = new HashMap<>();
                        data.put("type", "state");
                        data.put("status", status);
                        data.put("phase", phase);
                        data.put("progress", progress);
                        data.put("phaseLabel", phaseInfo.label());
                        data.put("phaseDetailLabel", phaseInfo.detailLabel());
                        data.put("phaseSubLabel", phaseInfo.subLabel());
                        data.put("suggestionCount", suggestionCount);
                        writeSseEvent(writer, "state", data);
                        writer.flush();

                        lastPhase = phase;
                        lastStatus = status;
                        lastProgress = progress;
                        lastSuggestionCount = suggestionCount;
                    }

                    List<TranscriptionChunk> chunks = chunkRepo.findByJobIdOrderByChunkIndex(id);
                    for (TranscriptionChunk chunk : chunks) {
                        if (chunk.getChunkIndex() > lastChunkIndex) {
                            if (chunk.getText() != null && !chunk.getText().isBlank()) {
                                List<Map<String, Object>> words = computeWordTimestamps(
                                    chunk.getText(), chunk.getStartTime(), chunk.getEndTime());
                                Map<String, Object> chunkData = new HashMap<>();
                                chunkData.put("type", "chunk");
                                chunkData.put("index", chunk.getChunkIndex());
                                chunkData.put("text", chunk.getText());
                                chunkData.put("start", chunk.getStartTime());
                                chunkData.put("end", chunk.getEndTime());
                                chunkData.put("words", words);
                                writeSseEvent(writer, "chunk", chunkData);
                                writer.flush();
                            }
                            lastChunkIndex = chunk.getChunkIndex();
                        }
                    }

                    if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                        writeSseEvent(writer, "done", Map.of("type", "done", "status", status));
                        writer.flush();
                        return;
                    }

                    Thread.sleep(1000);
                }
            } catch (IOException e) {
                // Client disconnected — expected, not an error
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        return Response.ok(stream).build();
    }

    private void writeSseEvent(BufferedWriter writer, String eventName, Map<String, Object> data) throws IOException {
        String json = mapper.writeValueAsString(data);
        writer.write("event: " + eventName + "\n");
        writer.write("data: " + json + "\n\n");
    }

    @POST
    @Path("job/{id}/process")
    public Response startProcessing(@PathParam("id") UUID id) {
        videoService.processVideo(id);
        return Response.seeOther(java.net.URI.create("/job/" + id)).build();
    }

    @POST
    @Path("job/{id}/delete")
    public Response deleteJob(@PathParam("id") UUID id) {
        videoService.deleteJob(id);
        return Response.seeOther(java.net.URI.create("/")).build();
    }

    @POST
    @Path("job/{id}/retry")
    public Response retryJob(@PathParam("id") UUID id) {
        try {
            videoService.retryJob(id);
        } catch (IllegalStateException e) {
            return Response.seeOther(java.net.URI.create("/job/" + id + "/relocate")).build();
        }
        return Response.seeOther(java.net.URI.create("/job/" + id)).build();
    }

    @GET
    @Path("job/{id}/relocate")
    public String relocatePage(@PathParam("id") UUID id) {
        VideoJob job = videoService.getJob(id);
        if (job == null) return notFound();
        String startPath = config.getStoragePath().toString();
        List<FileEntry> entries = storageBrowseService.browseFiles(startPath);
        return relocateVideo
            .data("pageTitle", "Locate File - " + job.getOriginalFilename())
            .data("job", job)
            .data("entries", entries)
            .data("currentPath", startPath)
            .render();
    }

    @POST
    @Path("job/{id}/relocate")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response handleRelocate(@PathParam("id") UUID id, @FormParam("filePath") String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Response.seeOther(java.net.URI.create("/job/" + id + "/relocate?error=No file selected")).build();
        }
        java.nio.file.Path p = java.nio.file.Paths.get(filePath).toAbsolutePath().normalize();
        if (!java.nio.file.Files.exists(p) || !java.nio.file.Files.isRegularFile(p)) {
            return Response.seeOther(java.net.URI.create("/job/" + id + "/relocate?error=File not found")).build();
        }
        if (!storageBrowseService.isAllowed(p)) {
            return Response.seeOther(java.net.URI.create("/job/" + id + "/relocate?error=Access denied")).build();
        }
        try {
            videoService.relocateJobVideo(id, filePath);
        } catch (Exception e) {
            return Response.seeOther(java.net.URI.create("/job/" + id + "/relocate?error=" + e.getMessage())).build();
        }
        return Response.seeOther(java.net.URI.create("/job/" + id)).build();
    }

    @POST
    @Path("job/{id}/transcription-review")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateTranscription(@PathParam("id") UUID id, @FormParam("text") String text) {
        videoService.updateTranscription(id, text);
        return Response.seeOther(java.net.URI.create("/job/" + id)).build();
    }

    @PATCH
    @Path("job/{id}/transcription/chunk/{chunkIndex}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateTranscriptionChunk(@PathParam("id") UUID id, @PathParam("chunkIndex") int chunkIndex, String body) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(body);
            String text = node.has("text") ? node.get("text").asText() : "";
            videoService.updateTranscriptionChunk(id, chunkIndex, text);
            return Response.ok(Map.of("success", true)).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("job/{id}/transcription/rebuild")
    @Produces(MediaType.APPLICATION_JSON)
    public Response rebuildTranscription(@PathParam("id") UUID id) {
        videoService.rebuildTranscriptionFromChunks(id);
        return Response.ok(Map.of("success", true)).build();
    }

    @POST
    @Path("job/{id}/transcription/finalize")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response finalizeTranscription(@PathParam("id") UUID id, Map<String, Object> body) {
        String prompt = body != null ? (String) body.get("prompt") : null;
        videoService.finalizeTranscription(id, prompt);
        return Response.ok(Map.of("success", true)).build();
    }

    // ─── Suggestions ──────────────────────────────────────

    @GET
    @Path("job/{id}/suggestions")
    public String suggestionsPage(@PathParam("id") UUID id) {
        VideoJob job = videoService.getJob(id);
        if (job == null) return notFound();

        List<WordTimestamp> words = parseWordTimestamps(job.getWordTimestampsJson());
        List<ClipSuggestion> clips = videoService.getSuggestions(id);
        List<TranscriptionSpan> spans = buildTranscriptionSpans(words, clips);

        return suggestions
            .data("pageTitle", "Select Clips - " + job.getOriginalFilename())
            .data("job", job)
            .data("suggestions", clips)
            .data("selectedCount", videoService.countSelected(id))
            .data("spans", spans)
            .render();
    }

    @POST
    @Path("job/{id}/suggestions/{sid}/toggle")
    public String toggleSuggestion(
        @PathParam("id") UUID jobId,
        @PathParam("sid") UUID suggestionId
    ) {
        videoService.toggleSuggestion(jobId, suggestionId);
        ClipSuggestion s = null;
        for (var cs : videoService.getSuggestions(jobId)) {
            if (cs.getId().equals(suggestionId)) { s = cs; break; }
        }
        if (s == null) return fragmentsSuggestionCard.data("s", null).render();
        return fragmentsSuggestionCard
            .data("s", s)
            .render();
    }

    @GET
    @Path("job/{id}/suggestions/count")
    @Produces(MediaType.APPLICATION_JSON)
    public Response suggestionsCount(@PathParam("id") UUID id) {
        long count = videoService.countSelected(id);
        return Response.ok(Map.of("count", count)).build();
    }

    @POST
    @Path("job/{id}/suggestions/select-all")
    public Response selectAll(@PathParam("id") UUID jobId) {
        videoService.selectAllSuggestions(jobId);
        return Response.seeOther(java.net.URI.create("/job/" + jobId + "/suggestions")).build();
    }

    @POST
    @Path("job/{id}/suggestions/{sid}/lock")
    @Produces(MediaType.TEXT_HTML)
    public String toggleSuggestionLock(@PathParam("id") UUID jobId, @PathParam("sid") UUID suggestionId) {
        videoService.toggleSuggestionLock(jobId, suggestionId);
        ClipSuggestion s = null;
        for (var cs : videoService.getSuggestions(jobId)) {
            if (cs.getId().equals(suggestionId)) { s = cs; break; }
        }
        if (s == null) return fragmentsSuggestionCard.data("s", null).render();
        return fragmentsSuggestionCard.data("s", s).render();
    }

    @PATCH
    @Path("job/{id}/suggestions/{sid}/time")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateSuggestionTime(@PathParam("id") UUID jobId, @PathParam("sid") UUID sid, Map<String, Object> body) {
        double start = body != null ? toDouble(body.get("start")) : 0;
        double end = body != null ? toDouble(body.get("end")) : 0;
        videoService.updateSuggestionTime(jobId, sid, start, end);
        return Response.ok(Map.of("success", true)).build();
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    @POST
    @Path("job/{id}/clip")
    public Response generateClips(@PathParam("id") UUID jobId) {
        clippingService.generateClips(jobId);
        return Response.seeOther(java.net.URI.create("/job/" + jobId + "/results")).build();
    }

    @POST
    @Path("job/{id}/re-analyze")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response reanalyzeVideo(@PathParam("id") UUID jobId, @FormParam("prompt") String prompt) {
        videoService.reanalyzeVideo(jobId, prompt);
        return Response.ok(Map.of("success", true))
            .header("HX-Redirect", "/job/" + jobId + "/suggestions")
            .build();
    }

    // ─── Transcription ────────────────────────────────────

    @GET
    @Path("job/{id}/transcription")
    public String transcriptionPage(@PathParam("id") UUID id) {
        VideoJob job = videoService.getJob(id);
        if (job == null) return notFound();

        List<WordTimestamp> words = parseWordTimestamps(job.getWordTimestampsJson());
        List<ClipSuggestion> suggestions = videoService.getSuggestions(id);
        List<TranscriptionSpan> spans = buildTranscriptionSpans(words, suggestions);

        return transcription
            .data("pageTitle", "Transcription - " + job.getOriginalFilename())
            .data("job", job)
            .data("spans", spans)
            .data("suggestions", suggestions)
            .data("selectedCount", videoService.countSelected(id))
            .render();
    }

    @GET
    @Path("job/{id}/transcription/chunks")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTranscriptionChunks(@PathParam("id") UUID id, @QueryParam("since") Integer sinceChunk) {
        VideoJob job = videoService.getJob(id);
        if (job == null) return Response.status(404).build();

        int since = sinceChunk != null ? sinceChunk : -1;
        List<TranscriptionChunk> chunks = chunkRepo.findByJobIdOrderByChunkIndex(id);
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (TranscriptionChunk chunk : chunks) {
            if (chunk.getChunkIndex() > since) {
                // Skip empty chunks (silent segments with no transcribed text)
                if (chunk.getText() == null || chunk.getText().isBlank()) continue;
                List<Map<String, Object>> words = computeWordTimestamps(
                    chunk.getText(), chunk.getStartTime(), chunk.getEndTime());
                result.add(Map.of(
                    "index", chunk.getChunkIndex(),
                    "text", chunk.getText(),
                    "start", chunk.getStartTime(),
                    "end", chunk.getEndTime(),
                    "words", words
                ));
            }
        }

        return Response.ok(result).build();
    }

    private List<Map<String, Object>> computeWordTimestamps(String text, double start, double end) {
        if (text == null || text.isBlank()) return List.of();
        String[] parts = text.trim().split("\\s+");
        double duration = end - start;
        double wordDur = duration / parts.length;
        List<Map<String, Object>> words = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            double ws = start + i * wordDur;
            double we = ws + wordDur;
            words.add(Map.of("word", parts[i], "start", ws, "end", we));
        }
        return words;
    }

    public record TranscriptionSpan(
        String text,
        double startSeconds,
        double endSeconds,
        boolean highlighted,
        UUID suggestionId,
        String suggestionTitle,
        String formattedStart,
        String formattedEnd,
        boolean isSelected
    ) {}

    private List<TranscriptionSpan> buildTranscriptionSpans(List<WordTimestamp> words, List<ClipSuggestion> suggestions) {
        if (words == null || words.isEmpty()) return List.of();

        List<TranscriptionSpan> spans = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        double currentStart = 0;
        double currentEnd = 0;
        UUID currentSuggestionId = null;
        String currentTitle = null;
        boolean currentHighlighted = false;
        boolean currentSelected = false;
        boolean hasContent = false;

        for (int i = 0; i < words.size(); i++) {
            WordTimestamp w = words.get(i);
            ClipSuggestion match = findSuggestionForWord(w, suggestions);
            UUID wordSuggestionId = match != null ? match.getId() : null;
            boolean wordHighlighted = match != null;

            boolean transition = hasContent && (
                (currentHighlighted != wordHighlighted) ||
                (currentHighlighted && wordHighlighted && !currentSuggestionId.equals(wordSuggestionId))
            );

            if (transition) {
                spans.add(makeSpan(currentText.toString(), currentStart, currentEnd,
                    currentHighlighted, currentSuggestionId, currentTitle, currentSelected));
                currentText = new StringBuilder();
                hasContent = false;
            }

            if (!hasContent) {
                currentStart = w.start();
                currentHighlighted = wordHighlighted;
                currentSuggestionId = wordSuggestionId;
                currentTitle = match != null ? match.getTitle() : null;
                currentSelected = match != null && Boolean.TRUE.equals(match.getIsSelected());
            }
            currentEnd = w.end();
            if (currentText.length() > 0) currentText.append(" ");
            currentText.append(w.word());
            hasContent = true;
        }

        if (hasContent) {
            spans.add(makeSpan(currentText.toString(), currentStart, currentEnd,
                currentHighlighted, currentSuggestionId, currentTitle, currentSelected));
        }

        return spans;
    }

    private ClipSuggestion findSuggestionForWord(WordTimestamp word, List<ClipSuggestion> suggestions) {
        double mid = (word.start() + word.end()) / 2.0;
        for (ClipSuggestion s : suggestions) {
            if (mid >= s.getStartTimeSeconds() && mid <= s.getEndTimeSeconds()) {
                return s;
            }
        }
        return null;
    }

    private TranscriptionSpan makeSpan(String text, double start, double end,
                                        boolean highlighted, UUID suggestionId,
                                        String title, boolean isSelected) {
        String fmtStart = formatTime(start);
        String fmtEnd = formatTime(end);
        return new TranscriptionSpan(text, start, end, highlighted, suggestionId,
            title, fmtStart, fmtEnd, isSelected);
    }

    private String formatTime(double seconds) {
        int total = (int) Math.round(seconds);
        return String.format("%d:%02d", total / 60, total % 60);
    }

    public record PhaseInfo(String label, String detailLabel, String subLabel) {}

    private PhaseInfo computePhaseInfo(String processingPhase) {
        if (processingPhase == null || processingPhase.isBlank()) {
            return new PhaseInfo("", "", "");
        }
        if (processingPhase.startsWith("analyzing_segment_")) {
            String[] parts = processingPhase.replace("analyzing_segment_", "").split("_of_");
            String detail = parts.length == 2 ? "Segment " + parts[0] + " of " + parts[1] : "";
            return new PhaseInfo("Analyzing content with AI...", detail, "Scanning transcript chunk for highlights...");
        } else if (processingPhase.startsWith("refining_pass_")) {
            String passNum = processingPhase.replace("refining_pass_", "");
            return new PhaseInfo("Refining clip suggestions...", "Refinement pass " + passNum, "Merging overlaps and re-ranking suggestions...");
        }
        String label = switch (processingPhase) {
            case "extracting_audio" -> "Converting video to audio...";
            case "transcribing" -> "Transcribing audio...";
            case "analyzing" -> "Analyzing content with AI...";
            default -> "";
        };
        return new PhaseInfo(label, "", "");
    }

    private List<WordTimestamp> parseWordTimestamps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // ─── Results ──────────────────────────────────────────

    @GET
    @Path("job/{id}/results")
    public String resultsPage(@PathParam("id") UUID id) {
        VideoJob job = videoService.getJob(id);
        if (job == null) return notFound();

        return results
            .data("pageTitle", "Results - " + job.getOriginalFilename())
            .data("job", job)
            .data("clips", videoService.getClips(id))
            .render();
    }

    // ─── Clip API ─────────────────────────────────────────

    @GET
    @Path("api/clips/{id}/video")
    @Produces("video/mp4")
    public Response downloadClip(@PathParam("id") UUID clipId) {
        Clip clip = clipRepo.findById(clipId);
        if (clip == null) return Response.status(404).build();

        java.nio.file.Path file = Paths.get(clip.getFilePath());
        if (!Files.exists(file)) return Response.status(404).build();

        StreamingOutput stream = output -> Files.copy(file, output);
        return Response.ok(stream)
            .header("Content-Disposition", "attachment; filename=\"" + clip.getFilename() + "\"")
            .header("Content-Length", clip.getFileSize())
            .build();
    }

    @GET
    @Path("api/clips/{id}/preview")
    @Produces("video/mp4")
    public Response previewClip(@PathParam("id") UUID clipId) {
        Clip clip = clipRepo.findById(clipId);
        if (clip == null) return Response.status(404).build();

        java.nio.file.Path file = Paths.get(clip.getFilePath());
        if (!Files.exists(file)) return Response.status(404).build();

        StreamingOutput stream = output -> Files.copy(file, output);
        return Response.ok(stream)
            .header("Content-Disposition", "inline")
            .build();
    }

    // ─── Job Video Endpoint ───────────────────────────────

    @GET
    @Path("job/{id}/video")
    public Response getJobVideo(@PathParam("id") UUID id, @jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers) {
        VideoJob job = videoService.getJob(id);
        if (job == null) return Response.status(404).build();

        java.nio.file.Path file = Paths.get(job.getFilePath());
        if (!Files.exists(file)) return Response.status(404).build();

        long fileSize = file.toFile().length();
        String rangeHeader = headers.getHeaderString("Range");

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] ranges = rangeHeader.substring(6).split("-");
            long start = Long.parseLong(ranges[0]);
            long end = ranges.length > 1 && !ranges[1].isEmpty() ? Long.parseLong(ranges[1]) : fileSize - 1;
            if (end >= fileSize) end = fileSize - 1;
            long contentLength = end - start + 1;

            try (var raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
                raf.seek(start);
                byte[] buffer = new byte[(int) contentLength];
                raf.readFully(buffer);
                return Response.status(206)
                    .header("Content-Type", "video/mp4")
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Range", "bytes " + start + "-" + end + "/" + fileSize)
                    .header("Content-Length", contentLength)
                    .entity(buffer)
                    .build();
            } catch (IOException e) {
                return Response.serverError().build();
            }
        }

        StreamingOutput stream = output -> Files.copy(file, output);
        return Response.ok(stream)
            .header("Content-Type", "video/mp4")
            .header("Accept-Ranges", "bytes")
            .header("Content-Length", fileSize)
            .header("Content-Disposition", "inline")
            .build();
    }

    // ─── Jobs list ────────────────────────────────────────

    @GET
    @Path("jobs")
    public String jobsList() {
        return index
            .data("pageTitle", "Clippy")
            .data("jobs", videoService.getAllJobs())
            .render();
    }

    public record ProgressStep(String label, String state, boolean isLast) {}

    private List<ProgressStep> computeProgressSteps(String status) {
        String[] labels = {"Upload", "Transcribe", "Analyze", "Clips", "Done"};
        int order = switch (status) {
            case "UPLOADED" -> 1;
            case "TRANSCRIBING" -> 2;
            case "TRANSCRIPTION_REVIEW" -> 3;
            case "ANALYZING" -> 3;
            case "SUGGESTIONS_READY" -> 4;
            case "CLIPPING" -> 5;
            case "COMPLETED" -> 6;
            default -> 0;
        };
        List<ProgressStep> steps = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            String state;
            if (order == 0) {
                state = "failed";
            } else if (i + 1 < order) {
                state = "completed";
            } else if (i + 1 == order) {
                state = "active";
            } else {
                state = "pending";
            }
            steps.add(new ProgressStep(labels[i], state, i == labels.length - 1));
        }
        return steps;
    }

    // ─── Helpers ─────────────────────────────────────────

    private String notFound() {
        return index
            .data("pageTitle", "Not Found")
            .data("jobs", List.of())
            .render();
    }
}
