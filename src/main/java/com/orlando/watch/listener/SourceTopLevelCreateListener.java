package com.orlando.watch.listener;

import java.nio.file.ClosedWatchServiceException;
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
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 监听源目录表层文件创建事件，并将任务投递到处理队列。
 */
@ApplicationScoped
@Startup
@Slf4j
public class SourceTopLevelCreateListener {

    private static final String THREAD_NAME = "source-top-level-create-listener";

    @Inject
    WatchProcessingConfig watchProcessingConfig;

    @Inject
    FileAllowListMatcher allowListMatcher;

    @Inject
    FileWatchTaskQueue fileWatchTaskQueue;

    private volatile boolean running = true;
    private volatile WatchService watchService;
    private Thread listenerThread;

    @PostConstruct
    void start() {
        listenerThread = new Thread(this::listen, THREAD_NAME);
        listenerThread.setDaemon(true);
        listenerThread.start();
        log.info("源目录监听线程已启动");
    }

    @PreDestroy
    void stop() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception ex) {
                log.warn("关闭源目录监听服务失败", ex);
            }
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        log.info("源目录监听线程已停止");
    }

    private void listen() {
        try {
            Path sourceRoot = Paths.get(watchProcessingConfig.path());
            Files.createDirectories(sourceRoot);
            log.info("源目录监听路径: {}", sourceRoot);

            watchService = FileSystems.getDefault().newWatchService();
            sourceRoot.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            while (running) {
                WatchKey watchKey = watchService.take();
                for (WatchEvent<?> event : watchKey.pollEvents()) {
                    handleEvent(event, sourceRoot);
                }
                if (!watchKey.reset()) {
                    log.warn("源目录监听 key 失效，结束监听线程");
                    break;
                }
            }
        } catch (ClosedWatchServiceException ex) {
            log.debug("源目录监听服务已关闭，线程退出");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("源目录监听线程被中断，线程退出");
        } catch (Exception ex) {
            log.error("源目录监听线程异常退出", ex);
        }
    }

    private void handleEvent(WatchEvent<?> event, Path sourceRoot) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            return;
        }

        Path relativePath = (Path) event.context();
        if (relativePath == null || !allowListMatcher.isAllowed(relativePath)) {
            return;
        }

        FileWatchTask task = new FileWatchTask(sourceRoot, relativePath.toString(), 0);
        fileWatchTaskQueue.offer(task);
        log.info("源目录创建事件入队: {}, queueSize={}", task.absolutePath(), fileWatchTaskQueue.size());
    }
}
