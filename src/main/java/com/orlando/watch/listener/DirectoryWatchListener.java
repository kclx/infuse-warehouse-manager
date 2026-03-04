package com.orlando.watch.listener;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

import com.orlando.watch.config.WatchProcessingConfig;
import com.orlando.watch.filter.FileAllowListMatcher;
import com.orlando.watch.model.FileWatchTask;
import com.orlando.watch.queue.FileWatchTaskQueue;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动守护线程监听目录，并将文件创建事件转换为队列任务。
 */
@ApplicationScoped
@Startup
@Slf4j
public class DirectoryWatchListener {

    @Inject
    WatchProcessingConfig watchProcessingConfig;

    @Inject
    FileAllowListMatcher allowListMatcher;

    @Inject
    FileWatchTaskQueue taskQueue;

    @PostConstruct
    void start() {
        Thread watcherThread = new Thread(this::watchLoop, "directory-watch-listener");
        watcherThread.setDaemon(true);
        watcherThread.start();
        log.info("文件监听器启动");
    }

    void watchLoop() {
        try {
            Path watchDirectory = Paths.get(watchProcessingConfig.path());
            Files.createDirectories(watchDirectory);
            log.info("监听路径: {}", watchDirectory);

            WatchService watchService = FileSystems.getDefault().newWatchService();
            watchDirectory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            while (true) {
                WatchKey watchKey = watchService.take();
                for (WatchEvent<?> event : watchKey.pollEvents()) {
                    handleEvent(event, watchDirectory);
                }
                watchKey.reset();
            }
        } catch (Exception ex) {
            log.error("文件监听线程异常退出", ex);
        }
    }

    private void handleEvent(WatchEvent<?> event, Path watchDirectory) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            return;
        }

        Path relativePath = (Path) event.context();
        if (relativePath == null) {
            return;
        }

        if (!allowListMatcher.isAllowed(relativePath)) {
            log.debug("不在白名单内，跳过文件: {}", relativePath);
            return;
        }

        FileWatchTask task = new FileWatchTask(watchDirectory, relativePath.toString(), 0);
        taskQueue.offer(task);
        log.info("文件变化入队: {}, queueSize={}", task.absolutePath(), taskQueue.size());
    }
}
