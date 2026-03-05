package com.orlando.watch.processor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orlando.media.ai.MediaFileNameParsingAiService;
import com.orlando.media.model.ParsedMediaFileInfo;
import com.orlando.watch.model.FileWatchTask;
import com.orlando.watch.naming.MediaTitleNormalizer;
import com.orlando.watch.naming.MovieFileNameResolver;
import com.orlando.watch.naming.OutputFileNameBuilder;
import com.orlando.watch.persistence.MediaAssetPersistenceService;
import com.orlando.watch.queue.FileWatchTaskQueue;
import com.orlando.watch.service.TitleCandidateResolutionService;
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
    private static final Pattern S_E_PATTERN = Pattern.compile("(?i).*?(?:^|[._\\-\\s])s(?<season>\\d{1,2})[._\\-\\s]*e(?<episode>\\d{1,3})(?:$|[._\\-\\s]).*");
    private static final Pattern X_PATTERN = Pattern.compile("(?i).*?(?<season>\\d{1,2})x(?<episode>\\d{1,3}).*");

    @Inject
    FileWatchTaskQueue fileWatchTaskQueue;

    @Inject
    FileReadinessProbe fileReadinessProbe;

    @Inject
    MediaTitleNormalizer mediaTitleNormalizer;

    @Inject
    OutputFileNameBuilder outputFileNameBuilder;

    @Inject
    MovieFileNameResolver movieFileNameResolver;

    @Inject
    MediaFileNameParsingAiService mediaFileNameParsingAiService;

    @Inject
    MediaAssetPersistenceService mediaAssetPersistenceService;

    @Inject
    TitleCandidateResolutionService titleCandidateResolutionService;

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
        parsedInfo = normalizeParsedInfoFromFileName(originalFileName, parsedInfo);
        log.info("fileNameParse: {}", parsedInfo);

        if (!isUsableForTask(parsedInfo, task.singleEpisodeOnly())) {
            retry(task, "AI 未能稳定识别 name/episode");
            return;
        }

        String normalizedTitle = mediaTitleNormalizer.normalize(parsedInfo.name());
        String candidateTitle = titleCandidateResolutionService.resolveCanonicalTitle(originalFileName, parsedInfo.name());
        if (candidateTitle != null && !candidateTitle.isBlank()) {
            normalizedTitle = mediaTitleNormalizer.normalize(candidateTitle);
        }

        String extension = extensionOf(originalFileName);
        String editionTag = null;
        String outputFileName;
        Path targetDirectory;

        Path targetRootDirectory = task.targetRootDirectory();
        Files.createDirectories(targetRootDirectory);

        if (task.singleEpisodeOnly()) {
            MovieFileNameResolver.MovieNamingResult movieNaming = movieFileNameResolver.resolveMovieNaming(
                    originalFileName,
                    normalizedTitle,
                    extension,
                    targetRootDirectory,
                    Set.of());
            normalizedTitle = movieNaming.title();
            editionTag = movieNaming.editionTag();
            targetDirectory = movieNaming.targetDirectory();
            Files.createDirectories(targetDirectory);
            outputFileName = movieNaming.fileName();
        } else {
            targetDirectory = targetRootDirectory.resolve(normalizedTitle);
            Files.createDirectories(targetDirectory);
            outputFileName = outputFileNameBuilder.build(normalizedTitle, parsedInfo.season(), parsedInfo.episode(), extension);
        }

        if (mediaAssetPersistenceService.shouldPersistAsSubtitleOnly(extension)
                && !mediaAssetPersistenceService.hasAssetRecord(parsedInfo, normalizedTitle, targetDirectory, task.singleEpisodeOnly())) {
            retry(task, "字幕文件对应的主媒体记录不存在");
            return;
        }

        Path targetFile = resolveSafeTargetFile(targetDirectory, outputFileName, sourceFile.getFileName().toString());
        if (sourceFile.equals(targetFile)) {
            log.info("文件已在目标位置: {}", sourceFile);
            return;
        }

        Files.move(sourceFile, targetFile);
        mediaAssetPersistenceService.persistByExtension(
                parsedInfo,
                normalizedTitle,
                extension,
                targetDirectory,
                targetFile,
                task.singleEpisodeOnly(),
                editionTag);
        log.info("处理成功: {} -> {}", sourceFile, targetFile);
    }

    private boolean isUsableForTask(ParsedMediaFileInfo parsedInfo, boolean singleEpisodeOnly) {
        if (parsedInfo == null || parsedInfo.name() == null || parsedInfo.name().isBlank()) {
            return false;
        }
        if (singleEpisodeOnly) {
            return true;
        }
        return parsedInfo.season() != null
                && parsedInfo.season() >= 0
                && parsedInfo.episode() != null
                && parsedInfo.episode() > 0;
    }

    private ParsedMediaFileInfo normalizeParsedInfoFromFileName(String fileName, ParsedMediaFileInfo parsedInfo) {
        if (parsedInfo == null) {
            return null;
        }

        EpisodeHint hint = extractEpisodeHint(fileName);
        if (hint == null) {
            return parsedInfo;
        }

        Integer season = parsedInfo.season();
        Integer episode = parsedInfo.episode();
        boolean changed = false;

        if (hint.season() != null) {
            // 文件名显式是 S00 时，强制按特别篇季处理。
            if (hint.season() == 0 && (season == null || season != 0)) {
                season = 0;
                changed = true;
            } else if (season == null || season < 0) {
                season = hint.season();
                changed = true;
            }
        }

        if (hint.episode() != null && (episode == null || episode <= 0)) {
            episode = hint.episode();
            changed = true;
        }

        if (!changed) {
            return parsedInfo;
        }

        return new ParsedMediaFileInfo(
                parsedInfo.name(),
                season,
                episode,
                parsedInfo.confidence(),
                parsedInfo.reason());
    }

    private EpisodeHint extractEpisodeHint(String fileName) {
        String baseName = stripExtension(fileName);

        Matcher sEMatcher = S_E_PATTERN.matcher(baseName);
        if (sEMatcher.matches()) {
            return new EpisodeHint(
                    parseInteger(sEMatcher.group("season")),
                    parseInteger(sEMatcher.group("episode")));
        }

        Matcher xMatcher = X_PATTERN.matcher(baseName);
        if (xMatcher.matches()) {
            return new EpisodeHint(
                    parseInteger(xMatcher.group("season")),
                    parseInteger(xMatcher.group("episode")));
        }

        return null;
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return null;
        }
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

    private Path resolveSafeTargetFile(Path targetDirectory, String desiredFileName, String sourceFileName) {
        if (desiredFileName.equals(sourceFileName)) {
            return targetDirectory.resolve(desiredFileName);
        }

        Path desired = targetDirectory.resolve(desiredFileName);
        if (!Files.exists(desired)) {
            return desired;
        }

        String extension = extensionOf(desiredFileName);
        String baseName = stripExtension(desiredFileName);
        String suffix = extension.isBlank() ? "" : "." + extension;
        Set<String> reserved = new HashSet<>();
        reserved.add(desiredFileName);

        int index = 2;
        String candidate = baseName + "_" + index + suffix;
        while (reserved.contains(candidate) || Files.exists(targetDirectory.resolve(candidate))) {
            index++;
            candidate = baseName + "_" + index + suffix;
        }

        log.warn("检测到目标文件已存在，自动改名避免覆盖: {} -> {}", desiredFileName, candidate);
        return targetDirectory.resolve(candidate);
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }

    private record EpisodeHint(Integer season, Integer episode) {
    }
}
