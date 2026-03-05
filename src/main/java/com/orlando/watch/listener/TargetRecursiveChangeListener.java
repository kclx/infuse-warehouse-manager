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
import java.util.HashSet;
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

    private static final String THREAD_NAME = "target-directory-name-listener";
    private static final Duration RENAME_MATCH_WINDOW = Duration.ofSeconds(8);

    @Inject
    WatchProcessingConfig watchProcessingConfig;

    @Inject
    FolderRenameBatchProcessor folderRenameBatchProcessor;

    private final Set<String> knownChildDirectories = new HashSet<>();

    private volatile boolean running = true;
    private volatile WatchService watchService;
    private Thread listenerThread;
    private Path targetRootDirectory;
    private PendingDirectoryChange pendingDeletedDirectory;
    private PendingDirectoryChange pendingCreatedDirectory;

    @PostConstruct
    void start() {
        listenerThread = new Thread(this::listenRootDirectory, THREAD_NAME);
        listenerThread.setDaemon(true);
        listenerThread.start();
        log.info("目标目录名称监听线程已启动");
    }

    @PreDestroy
    void stop() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception ex) {
                log.warn("关闭目标目录名称监听服务失败", ex);
            }
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        log.info("目标目录名称监听线程已停止");
    }

    private void listenRootDirectory() {
        try {
            targetRootDirectory = Paths.get(watchProcessingConfig.targetPath());
            Files.createDirectories(targetRootDirectory);
            initializeKnownChildDirectories();
            log.info("目标目录名称监听路径: {}", targetRootDirectory);

            watchService = FileSystems.getDefault().newWatchService();
            targetRootDirectory.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);

            while (running) {
                WatchKey watchKey = watchService.take();
                for (WatchEvent<?> event : watchKey.pollEvents()) {
                    handleRootDirectoryEvent(event);
                }
                if (!watchKey.reset()) {
                    log.warn("目标目录名称监听 key 失效，结束监听线程");
                    break;
                }
            }
        } catch (ClosedWatchServiceException ex) {
            log.debug("目标目录名称监听服务已关闭，线程退出");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("目标目录名称监听线程被中断，线程退出");
        } catch (Exception ex) {
            log.error("目标目录名称监听线程异常退出", ex);
        }
    }

    private void handleRootDirectoryEvent(WatchEvent<?> event) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            return;
        }

        Path relativePath = (Path) event.context();
        if (relativePath == null) {
            return;
        }

        expirePendingChangesIfNeeded();

        Path absolutePath = targetRootDirectory.resolve(relativePath);
        String directoryName = relativePath.toString();

        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
            if (knownChildDirectories.remove(directoryName)) {
                pendingDeletedDirectory = new PendingDirectoryChange(absolutePath, Instant.now());
                log.info("检测到子目录删除事件: {}", absolutePath);
                matchRenameAndProcess();
            }
            return;
        }

        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(absolutePath)) {
            knownChildDirectories.add(directoryName);
            pendingCreatedDirectory = new PendingDirectoryChange(absolutePath, Instant.now());
            log.info("检测到子目录创建事件: {}", absolutePath);
            matchRenameAndProcess();
        }
    }

    private void matchRenameAndProcess() {
        if (pendingDeletedDirectory == null || pendingCreatedDirectory == null) {
            return;
        }

        Path deletedDirectory = pendingDeletedDirectory.directory();
        Path createdDirectory = pendingCreatedDirectory.directory();

        if (deletedDirectory.getParent() == null || !deletedDirectory.getParent().equals(createdDirectory.getParent())) {
            return;
        }

        if (deletedDirectory.getFileName().equals(createdDirectory.getFileName())) {
            return;
        }

        pendingDeletedDirectory = null;
        pendingCreatedDirectory = null;

        log.info("识别到目录重命名: {} -> {}", deletedDirectory.getFileName(), createdDirectory.getFileName());
        folderRenameBatchProcessor.processFolderRename(deletedDirectory, createdDirectory);
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

    private void initializeKnownChildDirectories() {
        knownChildDirectories.clear();
        try (Stream<Path> pathStream = Files.list(targetRootDirectory)) {
            pathStream
                    .filter(Files::isDirectory)
                    .forEach(path -> knownChildDirectories.add(path.getFileName().toString()));
        } catch (Exception ex) {
            log.warn("初始化目标目录子目录快照失败: {}", targetRootDirectory, ex);
        }
    }

    private record PendingDirectoryChange(Path directory, Instant changedAt) {
    }
}
