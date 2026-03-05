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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.orlando.watch.config.WatchProcessingConfig;
import com.orlando.watch.processor.FolderRenameBatchProcessor;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 仅监听目标根目录下一层子目录的名称变化，并识别单次目录重命名。
 */
@ApplicationScoped
@Startup
@Slf4j
public class TargetRecursiveChangeListener {

    private static final Duration RENAME_MATCH_WINDOW = Duration.ofSeconds(8);

    @Inject
    WatchProcessingConfig watchProcessingConfig;

    @Inject
    FolderRenameBatchProcessor folderRenameBatchProcessor;

    private volatile boolean running = true;
    private final List<TargetWatchWorker> workers = new ArrayList<>();

    @PostConstruct
    void start() {
        Map<Path, String> uniqueTargetPaths = buildUniqueTargetPaths();
        for (Map.Entry<Path, String> entry : uniqueTargetPaths.entrySet()) {
            TargetWatchWorker worker = new TargetWatchWorker(entry.getValue(), entry.getKey());
            worker.start();
            workers.add(worker);
        }
        log.info("目标目录名称监听线程已启动: targets={}", workers.size());
    }

    @PreDestroy
    void stop() {
        running = false;
        for (TargetWatchWorker worker : workers) {
            worker.close();
        }
        workers.clear();
        log.info("目标目录名称监听线程已停止");
    }

    private void listenRootDirectory(TargetWatchWorker worker) {
        try {
            Files.createDirectories(worker.targetRootDirectory());
            worker.initializeKnownChildDirectories();
            log.info("目标目录名称监听路径: group={}, target={}", worker.groupName(), worker.targetRootDirectory());

            worker.targetRootDirectory().register(
                    worker.watchService(),
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);

            while (running) {
                WatchKey watchKey = worker.watchService().take();
                for (WatchEvent<?> event : watchKey.pollEvents()) {
                    handleRootDirectoryEvent(event, worker);
                }
                if (!watchKey.reset()) {
                    log.warn("目标目录名称监听 key 失效，结束监听线程: group={}", worker.groupName());
                    break;
                }
            }
        } catch (ClosedWatchServiceException ex) {
            log.debug("目标目录名称监听服务已关闭，线程退出: group={}", worker.groupName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("目标目录名称监听线程被中断，线程退出: group={}", worker.groupName());
        } catch (Exception ex) {
            log.error("目标目录名称监听线程异常退出: group={}", worker.groupName(), ex);
        }
    }

    private void handleRootDirectoryEvent(WatchEvent<?> event, TargetWatchWorker worker) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            return;
        }

        Path relativePath = (Path) event.context();
        if (relativePath == null) {
            return;
        }

        worker.expirePendingChangesIfNeeded();

        Path absolutePath = worker.targetRootDirectory().resolve(relativePath);
        String directoryName = relativePath.toString();

        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
            if (worker.knownChildDirectories().remove(directoryName)) {
                worker.pendingDeletedDirectory = new PendingDirectoryChange(absolutePath, Instant.now());
                log.info("检测到子目录删除事件: group={}, path={}", worker.groupName(), absolutePath);
                matchRenameAndProcess(worker);
            }
            return;
        }

        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(absolutePath)) {
            worker.knownChildDirectories().add(directoryName);
            worker.pendingCreatedDirectory = new PendingDirectoryChange(absolutePath, Instant.now());
            log.info("检测到子目录创建事件: group={}, path={}", worker.groupName(), absolutePath);
            matchRenameAndProcess(worker);
        }
    }

    private void matchRenameAndProcess(TargetWatchWorker worker) {
        if (worker.pendingDeletedDirectory == null || worker.pendingCreatedDirectory == null) {
            return;
        }

        Path deletedDirectory = worker.pendingDeletedDirectory.directory();
        Path createdDirectory = worker.pendingCreatedDirectory.directory();

        if (deletedDirectory.getParent() == null || !deletedDirectory.getParent().equals(createdDirectory.getParent())) {
            return;
        }

        if (deletedDirectory.getFileName().equals(createdDirectory.getFileName())) {
            return;
        }

        worker.pendingDeletedDirectory = null;
        worker.pendingCreatedDirectory = null;

        log.info("识别到目录重命名: group={}, {} -> {}",
                worker.groupName(), deletedDirectory.getFileName(), createdDirectory.getFileName());
        folderRenameBatchProcessor.processFolderRename(deletedDirectory, createdDirectory);
    }

    private Map<Path, String> buildUniqueTargetPaths() {
        Map<Path, String> unique = new LinkedHashMap<>();
        for (Map.Entry<String, WatchProcessingConfig.Group> entry : watchProcessingConfig.groups().entrySet()) {
            Path targetPath = Paths.get(entry.getValue().targetPath());
            unique.putIfAbsent(targetPath, entry.getKey());
        }
        return unique;
    }

    private record PendingDirectoryChange(Path directory, Instant changedAt) {
    }

    private final class TargetWatchWorker {
        private final String groupName;
        private final Path targetRootDirectory;
        private final Set<String> knownChildDirectories = new HashSet<>();
        private final WatchService watchService;
        private final Thread thread;

        private PendingDirectoryChange pendingDeletedDirectory;
        private PendingDirectoryChange pendingCreatedDirectory;

        private TargetWatchWorker(String groupName, Path targetRootDirectory) {
            this.groupName = groupName;
            this.targetRootDirectory = targetRootDirectory;
            try {
                this.watchService = FileSystems.getDefault().newWatchService();
            } catch (Exception ex) {
                throw new IllegalStateException("创建目标目录监听服务失败: group=" + groupName, ex);
            }
            this.thread = new Thread(() -> listenRootDirectory(this), "target-rename-listener-" + groupName);
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private void close() {
            try {
                watchService.close();
            } catch (Exception ex) {
                log.warn("关闭目标目录监听服务失败: group={}", groupName, ex);
            }
            thread.interrupt();
        }

        private void initializeKnownChildDirectories() {
            knownChildDirectories.clear();
            try (Stream<Path> pathStream = Files.list(targetRootDirectory)) {
                pathStream
                        .filter(Files::isDirectory)
                        .forEach(path -> knownChildDirectories.add(path.getFileName().toString()));
            } catch (Exception ex) {
                log.warn("初始化目标目录子目录快照失败: group={}, target={}", groupName, targetRootDirectory, ex);
            }
        }

        private void expirePendingChangesIfNeeded() {
            Instant now = Instant.now();
            if (pendingDeletedDirectory != null
                    && Duration.between(pendingDeletedDirectory.changedAt(), now).compareTo(RENAME_MATCH_WINDOW) > 0) {
                pendingDeletedDirectory = null;
            }
            if (pendingCreatedDirectory != null
                    && Duration.between(pendingCreatedDirectory.changedAt(), now).compareTo(RENAME_MATCH_WINDOW) > 0) {
                pendingCreatedDirectory = null;
            }
        }

        private String groupName() {
            return groupName;
        }

        private Path targetRootDirectory() {
            return targetRootDirectory;
        }

        private Set<String> knownChildDirectories() {
            return knownChildDirectories;
        }

        private WatchService watchService() {
            return watchService;
        }
    }
}
