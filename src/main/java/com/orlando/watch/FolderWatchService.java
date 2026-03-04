package com.orlando.watch;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.*;

import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;

@ApplicationScoped
@Startup
public class FolderWatchService {
    private static final Logger LOG = Logger.getLogger(FolderWatchService.class);
    private static final Path WATCH_PATH = Paths.get("/Users/orlando/Documents/Program/warehouse-manager/data");

    @Inject
    FileJobQueue jobQueue;

    @PostConstruct
    void start() {
        LOG.info("文件监听器启动");
        Thread watcherThread = new Thread(this::watch, "folder-watch-thread");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    void watch() {

        try {
            Files.createDirectories(WATCH_PATH);

            LOG.infov("监听路径: {0}", WATCH_PATH);

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
                    LOG.infov("文件变化入队: {0}, queueSize={1}", job.fullPath(), jobQueue.size());
                }

                key.reset();
            }

        } catch (Exception e) {
            LOG.error("文件监听线程异常退出", e);
        }
    }
}
