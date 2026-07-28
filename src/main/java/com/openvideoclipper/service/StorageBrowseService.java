package com.openvideoclipper.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class StorageBrowseService {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
        ".mp4", ".avi", ".mov", ".mkv", ".webm", ".flv", ".wmv", ".m4v"
    );



    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return (bytes / 1024) + " KB";
        return (bytes / 1048576) + " MB";
    }

    public List<DirEntry> browse(String pathStr) {
        try {
            if (pathStr == null) return Collections.emptyList();
            
            String expandedPath = pathStr;
            if (pathStr.startsWith("~")) {
                String home = System.getProperty("user.home");
                if (home != null) {
                    expandedPath = pathStr.replaceFirst("^~", home);
                }
            }

            Path target = Paths.get(expandedPath).toAbsolutePath().normalize();
            
            if (!isAllowed(target)) {
                throw new SecurityException("Access to path is not allowed: " + pathStr);
            }

            if (!Files.exists(target) || !Files.isDirectory(target)) {
                return Collections.emptyList();
            }

            try (Stream<Path> stream = Files.list(target)) {
                return stream
                    .filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .map(p -> new DirEntry(
                        p.getFileName().toString(), 
                        p.toAbsolutePath().toString(), 
                        true
                    ))
                    .sorted(Comparator.comparing(DirEntry::name).thenComparing(DirEntry::isDir).reversed())
                    .collect(Collectors.toList());
            }
        } catch (IOException | SecurityException e) {
            return Collections.emptyList();
        }
    }

    public List<FileEntry> browseFiles(String pathStr) {
        try {
            if (pathStr == null) return Collections.emptyList();

            String expandedPath = pathStr;
            if (pathStr.startsWith("~")) {
                String home = System.getProperty("user.home");
                if (home != null) {
                    expandedPath = pathStr.replaceFirst("^~", home);
                }
            }

            Path target = Paths.get(expandedPath).toAbsolutePath().normalize();

            if (!isAllowed(target)) {
                throw new SecurityException("Access to path is not allowed: " + pathStr);
            }

            if (!Files.exists(target) || !Files.isDirectory(target)) {
                return Collections.emptyList();
            }

            try (Stream<Path> stream = Files.list(target)) {
                return stream
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .map(p -> {
                        boolean isDir = Files.isDirectory(p);
                        String name = p.getFileName().toString();
                        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";
                        boolean isVideo = !isDir && VIDEO_EXTENSIONS.contains(ext);
                        long size = isDir ? 0 : p.toFile().length();
                        String modified = "";
                        try {
                            modified = Files.getLastModifiedTime(p).toString().substring(0, 19);
                        } catch (IOException ignored) {}
                        return new FileEntry(name, p.toAbsolutePath().toString(), isDir, isVideo, size, modified, formatSize(size));
                    })
                    .sorted((a, b) -> {
                        if (a.isDir() && !b.isDir()) return -1;
                        if (!a.isDir() && b.isDir()) return 1;
                        return a.name().compareToIgnoreCase(b.name());
                    })
                    .collect(Collectors.toList());
            }
        } catch (IOException | SecurityException e) {
            return Collections.emptyList();
        }
    }

    public boolean isAllowed(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return getAllowedRoots().stream().anyMatch(root -> normalized.startsWith(root));
    }

    private List<Path> getAllowedRoots() {
        List<Path> roots = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();
        String home = System.getProperty("user.home");

        if (home != null) {
            roots.add(Paths.get(home).toAbsolutePath());
        }

        if (os.contains("win")) {
            // Windows: Add all existing drive letters
            for (char c = 'C'; c <= 'Z'; c++) {
                Path drive = Paths.get(c + ":\\");
                if (Files.exists(drive)) {
                    roots.add(drive);
                }
            }
        } else {
            // Linux/macOS: Common mount points
            List<String> mounts = List.of("/mnt", "/media", "/Volumes", "/run/media");
            for (String m : mounts) {
                Path p = Paths.get(m);
                if (Files.exists(p) && Files.isDirectory(p)) {
                    roots.add(p);
                }
            }
        }
        return roots;
    }
}
