package com.openvideoclipper.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@ApplicationScoped
@Named("storageConfig")
public class StorageConfig {

    @Inject
    OvcConfig OvcConfig;

    public String getStoragePath() {
        return OvcConfig.getStoragePath().toString();
    }

    public long getFreeBytes() {
        return OvcConfig.getStoragePath().toFile().getFreeSpace();
    }

    public long getTotalBytes() {
        return OvcConfig.getStoragePath().toFile().getTotalSpace();
    }
}
