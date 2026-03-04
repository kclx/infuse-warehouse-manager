package com.orlando.watch.util;

import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * 用于探测文件是否仍在写入，避免过早处理。
 */
@ApplicationScoped
public class FileReadinessProbe {

    public boolean isStable(Path file) {
        try {
            long firstSize = Files.size(file);
            Thread.sleep(500);
            long secondSize = Files.size(file);
            return firstSize == secondSize;
        } catch (Exception ex) {
            return false;
        }
    }
}
