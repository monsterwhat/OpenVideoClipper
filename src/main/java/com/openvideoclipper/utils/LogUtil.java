package com.openvideoclipper.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogUtil {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private LogUtil() {}

    public static void info(String msg) {
        System.out.println(LocalDateTime.now().format(FMT) + " " + msg);
    }

    public static void error(String msg) {
        System.err.println(LocalDateTime.now().format(FMT) + " ERROR: " + msg);
    }

    public static void error(String msg, Throwable t) {
        System.err.println(LocalDateTime.now().format(FMT) + " ERROR: " + msg);
        t.printStackTrace(System.err);
    }
}
