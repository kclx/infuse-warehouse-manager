package com.orlando.watch.model;

import java.nio.file.Path;

/**
 * 文件系统监听器产生的不可变任务对象。
 */
public record FileWatchTask(
        Path sourceRootDirectory,
        Path targetRootDirectory,
        boolean singleEpisodeOnly,
        String relativeFileName,
        int retryCount) {

    public Path absolutePath() {
        return sourceRootDirectory.resolve(relativeFileName);
    }

    public FileWatchTask nextRetry() {
        return new FileWatchTask(sourceRootDirectory, targetRootDirectory, singleEpisodeOnly, relativeFileName, retryCount + 1);
    }
}
