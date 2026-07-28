package com.openvideoclipper.utils;

import static com.openvideoclipper.utils.LogUtil.error;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StorageUtils {

    public static Path getDefaultStoragePath() {
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                return Paths.get(appData, "ovc");
            }
            return Paths.get(userHome, "AppData", "Roaming", "ovc");
        } else if (os.contains("mac")) {
            return Paths.get(userHome, "Library", "Application Support", "ovc");
        } else {
            // Linux/Unix
            String xdgDataHome = System.getenv("XDG_DATA_HOME");
            if (xdgDataHome != null) {
                return Paths.get(xdgDataHome, "ovc");
            }
            return Paths.get(userHome, ".local", "share", "ovc");
        }
    }

    public static void ensureDefaultStoragePathExists() {
        Path path = getDefaultStoragePath();
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            error("Failed to create default storage directory: " + path, e);
        }
    }
}
