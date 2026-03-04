package com.orlando.watch;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.*;

import io.quarkus.runtime.Startup;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Startup
@Slf4j
public class FolderWatchService {
    private static final Path WATCH_PATH = Paths.get("/Users/orlando/Documents/Program/warehouse-manager/data");

    @Inject
    FileJobQueue jobQueue;

    @PostConstruct
    void start() {
        log.info("文件监听器启动");
        Thread watcherThread = new Thread(this::watch, "folder-watch-thread");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    void watch() {

        try {
            Files.createDirectories(WATCH_PATH);

            log.info("监听路径: {}", WATCH_PATH);

            WatchService watchService = FileSystems.getDefault().newWatchService();

            WATCH_PATH.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE);

            while (true) {
                WatchKey key = watchService.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    Path file = (Path) event.context();
                    if (file == null) {
                        continue;
                    }

                    FileProcessJob job = new FileProcessJob(WATCH_PATH, file.toString(), 0);
                    jobQueue.offer(job);
                    log.info("文件变化入队: {}, queueSize={}", job.fullPath(), jobQueue.size());
                }

                key.reset();
            }

        } catch (Exception e) {
            log.error("文件监听线程异常退出", e);
        }
    }
}
