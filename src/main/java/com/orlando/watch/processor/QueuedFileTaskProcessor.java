package com.orlando.watch.processor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.orlando.media.ai.MediaFileNameParsingAiService;
import com.orlando.media.model.ParsedMediaFileInfo;
import com.orlando.watch.model.FileWatchTask;
import com.orlando.watch.naming.MediaTitleNormalizer;
import com.orlando.watch.naming.OutputFileNameBuilder;
import com.orlando.watch.persistence.MediaAssetPersistenceService;
import com.orlando.watch.queue.FileWatchTaskQueue;
import com.orlando.watch.util.FileReadinessProbe;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

/**
 * 消费监听队列任务，执行“解析 -> 移动 -> 落库”处理链路。
 */
@ApplicationScoped
@Slf4j
public class QueuedFileTaskProcessor {

    private static final int MAX_RETRY_COUNT = 3;

    @Inject
    FileWatchTaskQueue fileWatchTaskQueue;

    @Inject
    FileReadinessProbe fileReadinessProbe;

    @Inject
    MediaTitleNormalizer mediaTitleNormalizer;

    @Inject
    OutputFileNameBuilder outputFileNameBuilder;

    @Inject
    MediaFileNameParsingAiService mediaFileNameParsingAiService;

    @Inject
    MediaAssetPersistenceService mediaAssetPersistenceService;

    @Scheduled(every = "1s")
    @ActivateRequestContext
    @Transactional
    void processOne() {
        FileWatchTask task = fileWatchTaskQueue.poll();
        if (task == null) {
            return;
        }

        Path sourceFile = task.absolutePath();
        if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            log.warn("跳过任务，文件不存在或不是普通文件: {}", sourceFile);
            return;
        }

        if (!fileReadinessProbe.isStable(sourceFile)) {
            retry(task, "文件仍在写入中");
            return;
        }

        try {
            processFileTask(task, sourceFile);
        } catch (Exception ex) {
            retry(task, "处理异常: " + ex.getMessage());
            log.error("处理失败: {}", sourceFile, ex);
        }
    }

    private void processFileTask(FileWatchTask task, Path sourceFile) throws Exception {
        String originalFileName = sourceFile.getFileName().toString();
        ParsedMediaFileInfo parsedInfo = mediaFileNameParsingAiService.parse(originalFileName);
        log.info("fileNameParse: {}", parsedInfo);

        if (parsedInfo == null || !parsedInfo.isUsable()) {
            retry(task, "AI 未能稳定识别 name/episode");
            return;
        }

        String normalizedTitle = mediaTitleNormalizer.normalize(parsedInfo.name());
        String extension = extensionOf(originalFileName);

        String outputFileName = outputFileNameBuilder.build(
                normalizedTitle,
                parsedInfo.season(),
                parsedInfo.episode(),
                extension);

        Path targetRootDirectory = task.targetRootDirectory();
        Files.createDirectories(targetRootDirectory);

        Path targetDirectory = targetRootDirectory.resolve(normalizedTitle);
        Files.createDirectories(targetDirectory);

        if (mediaAssetPersistenceService.shouldPersistAsSubtitleOnly(extension)
                && !mediaAssetPersistenceService.hasAssetRecord(parsedInfo, normalizedTitle, targetDirectory)) {
            retry(task, "字幕文件对应的主媒体记录不存在");
            return;
        }

        Path targetFile = targetDirectory.resolve(outputFileName);
        if (sourceFile.equals(targetFile)) {
            log.info("文件已在目标位置: {}", sourceFile);
            return;
        }

        Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        mediaAssetPersistenceService.persistByExtension(
                parsedInfo,
                normalizedTitle,
                extension,
                targetDirectory,
                targetFile);
        log.info("处理成功: {} -> {}", sourceFile, targetFile);
    }

    private void retry(FileWatchTask task, String reason) {
        if (task.retryCount() + 1 >= MAX_RETRY_COUNT) {
            log.error("任务失败且超过最大重试次数({}): {}, reason={}", MAX_RETRY_COUNT, task.absolutePath(), reason);
            return;
        }

        FileWatchTask nextTask = task.nextRetry();
        fileWatchTaskQueue.offer(nextTask);
        log.warn("任务重试({}/{}): {}, reason={}",
                nextTask.retryCount(),
                MAX_RETRY_COUNT - 1,
                nextTask.absolutePath(),
                reason);
    }

    private String extensionOf(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1);
    }
}
