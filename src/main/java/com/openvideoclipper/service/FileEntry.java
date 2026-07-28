package com.openvideoclipper.service;

public record FileEntry(String name, String path, boolean isDir, boolean isVideo, long size, String modified, String formattedSize) {}
