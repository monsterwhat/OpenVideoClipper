package com.openvideoclipper.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class PrerequisiteService {

    public record Prerequisite(String id, String label, boolean met, String version, String installHint) {}

    public List<Prerequisite> checkAll() {
        return List.of(
            checkPython(),
            checkFfmpeg(),
            checkTorch(),
            checkTransformers(),
            checkSoundfile()
        );
    }

    public Prerequisite checkPython() {
        return checkCmd("python", "--version",
            "Python 3.10+", "Install Python: https://python.org");
    }

    public Prerequisite checkFfmpeg() {
        Prerequisite result = checkCmd("ffmpeg", "-version",
            "FFmpeg", getFfmpegInstallHint());
        if (!result.met()) {
            Prerequisite alt = checkCmd("ffmpeg.exe", "-version",
                "FFmpeg", getFfmpegInstallHint());
            if (alt.met()) return alt;
        }
        return result;
    }

    public Prerequisite checkTorch() {
        return checkPythonPkg("torch", "PyTorch (CUDA)");
    }

    public Prerequisite checkTransformers() {
        return checkPythonPkg("transformers", "HuggingFace Transformers");
    }

    public Prerequisite checkSoundfile() {
        return checkPythonPkg("soundfile", "SoundFile");
    }

    private Prerequisite checkCmd(String cmd, String arg, String label, String hint) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, arg);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line);
                }
            }

            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                String version = out.toString().lines().findFirst().orElse("ok").trim();
                if (version.length() > 80) version = version.substring(0, 80) + "...";
                return new Prerequisite(cmd, label, true, version, null);
            }
        } catch (Exception ignored) {}

        return new Prerequisite(cmd, label, false, null, hint);
    }

    private Prerequisite checkPythonPkg(String pkg, String label) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                findPython(), "-c", "import " + pkg + "; print(getattr(" + pkg + ", '__version__', 'ok'))"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line);
                }
            }

            boolean done = p.waitFor(30, TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                String version = out.toString().trim();
                return new Prerequisite(pkg, label, true, version.length() > 0 ? version : "ok",
                    null);
            }
        } catch (Exception ignored) {}

        return new Prerequisite(pkg, label, false, null,
            "pip install " + pkg);
    }

    private String findPython() {
        try {
            ProcessBuilder pb = new ProcessBuilder("python", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return "python";
        } catch (Exception ignored) {}
        return "python3";
    }

    private String getFfmpegInstallHint() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "Install: choco install ffmpeg  or  https://ffmpeg.org/download.html";
        } else if (os.contains("mac")) {
            return "Install: brew install ffmpeg";
        } else {
            return "Install: sudo apt install ffmpeg  or  sudo dnf install ffmpeg";
        }
    }
}
