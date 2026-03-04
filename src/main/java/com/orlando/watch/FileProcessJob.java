package com.orlando.watch;

import java.nio.file.Path;

public record FileProcessJob(Path baseDir, String relativeName, int attempts) {
    public Path fullPath() {
        return baseDir.resolve(relativeName);
    }

    public FileProcessJob nextAttempt() {
        return new FileProcessJob(baseDir, relativeName, attempts + 1);
    }
}
