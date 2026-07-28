package com.openvideoclipper.service.analysis;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class SceneCutAnalysisProvider implements AnalysisProvider {

    @Override
    public String id() {
        return "scenedetect";
    }

    @Override
    public String displayName() {
        return "PySceneDetect";
    }

    @Override
    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("scenedetect", "--version").start();
            return p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public AnalysisResult analyze(Path videoPath, Path audioPath) throws AnalysisException {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "python", "scripts/detect_scenes.py", videoPath.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            if (!p.waitFor(1, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new AnalysisException("PySceneDetect timed out", null);
            }

            if (p.exitValue() != 0) {
                throw new AnalysisException("PySceneDetect failed: " + output.toString(), null);
            }

            // The script outputs the JSON at the end. Let's find it.
            String outStr = output.toString().trim();
            int jsonStart = outStr.lastIndexOf("{");
            if (jsonStart == -1) {
                throw new AnalysisException("Could not find JSON output from PySceneDetect", null);
            }
            String jsonPart = outStr.substring(jsonStart);

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(jsonPart);
            var scenesNode = root.get("scenes");

            List<AnalysisEvent> events = new ArrayList<>();
            if (scenesNode != null && scenesNode.isArray()) {
                for (var node : scenesNode) {
                    events.add(new AnalysisEvent(
                        node.get("start").asDouble(),
                        node.get("end").asDouble(),
                        "scene_cut",
                        1.0,
                        Map.of()
                    ));
                }
            }

            return new AnalysisResult("scene_cut", events);

        } catch (Exception e) {
            throw new AnalysisException("Failed to run scene detection", e);
        }
    }
}
