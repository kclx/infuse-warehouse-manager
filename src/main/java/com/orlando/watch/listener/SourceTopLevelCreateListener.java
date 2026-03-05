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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * 按分组监听源目录表层文件创建事件，并将任务投递到处理队列。
 */
@ApplicationScoped
@Startup
@Slf4j
public class SourceTopLevelCreateListener {

    @Inject
    WatchProcessingConfig watchProcessingConfig;

    @Inject
    FileAllowListMatcher allowListMatcher;

    @Inject
    FileWatchTaskQueue fileWatchTaskQueue;

    private volatile boolean running = true;
    private final List<SourceWatchWorker> workers = new ArrayList<>();

    @PostConstruct
    void start() {
        for (Map.Entry<String, WatchProcessingConfig.Group> entry : watchProcessingConfig.groups().entrySet()) {
            String groupName = entry.getKey();
            Path sourceRoot = Paths.get(entry.getValue().path());
            Path targetRoot = Paths.get(entry.getValue().targetPath());
            boolean singleEpisodeOnly = entry.getValue().singleEpisodeOnly();
            SourceWatchWorker worker = new SourceWatchWorker(groupName, sourceRoot, targetRoot, singleEpisodeOnly);
            worker.start();
            workers.add(worker);
        }
        log.info("源目录监听线程已启动: groups={}", workers.size());
    }

    @PreDestroy
    void stop() {
        running = false;
        for (SourceWatchWorker worker : workers) {
            worker.close();
        }
        workers.clear();
        log.info("源目录监听线程已停止");
    }

    private void listen(SourceWatchWorker worker) {
        try {
            Files.createDirectories(worker.sourceRoot());
            log.info("源目录监听路径: group={}, source={}, target={}",
                    worker.groupName(), worker.sourceRoot(), worker.targetRoot());

            worker.sourceRoot().register(worker.watchService(), StandardWatchEventKinds.ENTRY_CREATE);

            while (running) {
                WatchKey watchKey = worker.watchService().take();
                for (WatchEvent<?> event : watchKey.pollEvents()) {
                    handleEvent(event, worker);
                }
                if (!watchKey.reset()) {
                    log.warn("源目录监听 key 失效，结束监听线程: group={}", worker.groupName());
                    break;
                }
            }
        } catch (ClosedWatchServiceException ex) {
            log.debug("源目录监听服务已关闭，线程退出: group={}", worker.groupName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("源目录监听线程被中断，线程退出: group={}", worker.groupName());
        } catch (Exception ex) {
            log.error("源目录监听线程异常退出: group={}", worker.groupName(), ex);
        }
    }

    private void handleEvent(WatchEvent<?> event, SourceWatchWorker worker) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            return;
        }

        Path relativePath = (Path) event.context();
        if (relativePath == null || !allowListMatcher.isAllowed(relativePath)) {
            return;
        }

        FileWatchTask task = new FileWatchTask(
                worker.sourceRoot(),
                worker.targetRoot(),
                worker.singleEpisodeOnly(),
                relativePath.toString(),
                0);
        fileWatchTaskQueue.offer(task);
        log.info("源目录创建事件入队: group={}, file={}, queueSize={}",
                worker.groupName(), task.absolutePath(), fileWatchTaskQueue.size());
    }

    private final class SourceWatchWorker {
        private final String groupName;
        private final Path sourceRoot;
        private final Path targetRoot;
        private final boolean singleEpisodeOnly;
        private final WatchService watchService;
        private final Thread thread;

        private SourceWatchWorker(String groupName, Path sourceRoot, Path targetRoot, boolean singleEpisodeOnly) {
            this.groupName = groupName;
            this.sourceRoot = sourceRoot;
            this.targetRoot = targetRoot;
            this.singleEpisodeOnly = singleEpisodeOnly;
            try {
                this.watchService = FileSystems.getDefault().newWatchService();
            } catch (Exception ex) {
                throw new IllegalStateException("创建源目录监听服务失败: group=" + groupName, ex);
            }
            this.thread = new Thread(() -> listen(this), "source-create-listener-" + groupName);
            this.thread.setDaemon(true);
        }

        private void start() {
            this.thread.start();
        }

        private void close() {
            try {
                this.watchService.close();
            } catch (Exception ex) {
                log.warn("关闭源目录监听服务失败: group={}", groupName, ex);
            }
            this.thread.interrupt();
        }

        private String groupName() {
            return groupName;
        }

        private Path sourceRoot() {
            return sourceRoot;
        }

        private Path targetRoot() {
            return targetRoot;
        }

        private boolean singleEpisodeOnly() {
            return singleEpisodeOnly;
        }

        private WatchService watchService() {
            return watchService;
        }
    }
}
