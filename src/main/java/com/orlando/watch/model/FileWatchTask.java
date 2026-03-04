package com.orlando.watch.model;

import java.nio.file.Path;

/**
 * 文件系统监听器产生的不可变任务对象。
 */
public record FileWatchTask(Path watchBaseDirectory, String relativeFileName, int retryCount) {

    public Path absolutePath() {
        return watchBaseDirectory.resolve(relativeFileName);
    }

    public FileWatchTask nextRetry() {
        return new FileWatchTask(watchBaseDirectory, relativeFileName, retryCount + 1);
    }
}
