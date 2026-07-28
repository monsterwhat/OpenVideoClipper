package com.openvideoclipper;

import static com.openvideoclipper.utils.LogUtil.error;
import com.openvideoclipper.utils.StorageUtils;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import java.nio.file.Files;
import java.nio.file.Path;

@QuarkusMain
public class OvcApplication {
    public static void main(String[] args) {
        Path storagePath = StorageUtils.getDefaultStoragePath();
        try {
            Files.createDirectories(storagePath);
            System.setProperty("ovc.storage.path", storagePath.toString());
        } catch (Exception e) {
            error("Failed to prepare storage directory: " + storagePath, e);
        }
        Quarkus.run(args);
    }
}
