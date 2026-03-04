package com.orlando.watch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.orlando.dto.FileNameParse;
import com.orlando.entity.MediaAsset;
import com.orlando.service.FileNameParseService;
import com.orlando.repository.MediaAssetRepository;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Slf4j
public class FileProcessWorker {
    private static final int MAX_ATTEMPTS = 3;

    @Inject
    FileJobQueue jobQueue;

    @Inject
    FileNameParseService fileNameParseService;

    @Inject
    MediaAssetRepository mediaAssetRepository;

    @ConfigProperty(name = "watch.target-path", defaultValue = "/Users/orlando/Documents/Program/warehouse-manager/data-target")
    String watchTargetPath;

    @ConfigProperty(name = "watch.db.asset-file-extensions", defaultValue = "mp4,mkv")
    Set<String> assetFileExtensions;

    @ConfigProperty(name = "watch.db.subtitle-file-extensions", defaultValue = "ass,srt,ssa,vtt")
    Set<String> subtitleFileExtensions;

    @ConfigProperty(name = "watch.filename.pattern", defaultValue = "{name}_s{snumber}.e{enumber}.{ext}")
    String outputFileNamePattern;

    @ConfigProperty(name = "watch.filename.season-number-width", defaultValue = "2")
    Integer seasonNumberWidth;

    @ConfigProperty(name = "watch.filename.episode-number-width", defaultValue = "2")
    Integer episodeNumberWidth;

    @Scheduled(every = "1s")
    @ActivateRequestContext
    @Transactional
    void processOne() {
        FileProcessJob job = jobQueue.poll();
        if (job == null) {
            return;
        }

        Path sourceFile = job.fullPath();
        if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            log.warn("跳过任务，文件不存在或不是普通文件: {}", sourceFile);
            return;
        }

        if (!isFileReady(sourceFile)) {
            retry(job, "文件仍在写入中");
            return;
        }

        String originalFileName = sourceFile.getFileName().toString();

        try {
            FileNameParse parsed = fileNameParseService.parseName(originalFileName);
            log.info("fileNameParse: {}", parsed.toString());
            if (!isValidParse(parsed)) {
                retry(job, "AI 未能稳定识别 name/episode");
                return;
            }

            String normalizedName = normalizeName(parsed.name());
            String extension = extensionOf(originalFileName);
            String targetFileName = buildTargetFileName(normalizedName, parsed.season(), parsed.episode(), extension);

            Path targetRootDir = Paths.get(watchTargetPath);
            Files.createDirectories(targetRootDir);
            Path targetDir = targetRootDir.resolve(normalizedName);
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(targetFileName);
            String ext = extension.toLowerCase(Locale.ROOT);

            if (isSubtitleExtension(ext) && !assetExists(parsed, normalizedName, targetDir)) {
                retry(job, "字幕文件对应的主媒体记录不存在");
                return;
            }

            if (sourceFile.equals(targetFile)) {
                log.info("文件已在目标位置: {}", sourceFile);
                return;
            }

            Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            persistMediaAsset(parsed, normalizedName, ext, targetDir, targetFile);
            log.info("处理成功: {} -> {}", sourceFile, targetFile);
        } catch (Exception e) {
            retry(job, "处理异常: " + e.getMessage());
            log.error("处理失败: {}", sourceFile, e);
        }
    }

    private void retry(FileProcessJob job, String reason) {
        if (job.attempts() + 1 >= MAX_ATTEMPTS) {
            log.error("任务失败且超过最大重试次数({}): {}, reason={}", MAX_ATTEMPTS, job.fullPath(), reason);
            return;
        }
        FileProcessJob next = job.nextAttempt();
        jobQueue.offer(next);
        log.warn("任务重试({}/{}): {}, reason={}", next.attempts(), MAX_ATTEMPTS - 1, next.fullPath(), reason);
    }

    private boolean isFileReady(Path file) {
        try {
            long size1 = Files.size(file);
            Thread.sleep(500);
            long size2 = Files.size(file);
            return size1 == size2;
        } catch (Exception e) {
            log.warn("无法确认文件是否写入完成: {}, reason={}", file, e.getMessage());
            return false;
        }
    }

    private boolean isValidParse(FileNameParse parsed) {
        return parsed != null
                && parsed.name() != null
                && !parsed.name().isBlank()
                && parsed.season() != null
                && parsed.season() > 0
                && parsed.episode() != null
                && parsed.episode() > 0;
    }

    private String buildTargetFileName(String name, Integer season, Integer episode, String ext) {
        int seasonWidth = normalizedNumberWidth(seasonNumberWidth);
        int episodeWidth = normalizedNumberWidth(episodeNumberWidth);
        String snumber = String.format(Locale.ROOT, "%0" + seasonWidth + "d", season);
        String enumber = String.format(Locale.ROOT, "%0" + episodeWidth + "d", episode);
        String safeName = filenameSafe(name);
        String safeExt = ext == null ? "" : ext.trim();

        String rendered = outputFileNamePattern
                .replace("{name}", safeName)
                .replace("{snumber}", snumber)
                .replace("{enumber}", enumber)
                .replace("{ext}", safeExt);

        if (safeExt.isBlank()) {
            rendered = rendered
                    .replaceAll("\\.$", "")
                    .replaceAll("_$", "")
                    .replaceAll("-$", "");
        }
        return rendered;
    }

    private int normalizedNumberWidth(Integer width) {
        if (width == null || width < 1) {
            return 2;
        }
        return width;
    }

    private String normalizeName(String raw) {
        String cleaned = raw
                .replace('_', ' ')
                .replaceAll("(?i)[._-]?s\\d{1,2}[._-]?e\\d{1,3}$", "")
                .replaceAll("[\\\\/:*?\"<>|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return "Unknown";
        }
        return cleaned;
    }

    private String filenameSafe(String name) {
        return name.trim();
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1);
    }

    private void persistMediaAsset(FileNameParse parsed, String normalizedName, String ext, Path targetDir,
            Path targetFile) {
        if (isAssetExtension(ext)) {
            upsertAsset(parsed, normalizedName, targetDir, targetFile);
            return;
        }

        if (isSubtitleExtension(ext)) {
            appendSubtitle(parsed, normalizedName, targetDir, targetFile);
            return;
        }

        log.info("扩展名不在落库白名单中，跳过落库: {}", targetFile.getFileName());
    }

    private void upsertAsset(FileNameParse parsed, String normalizedName, Path targetDir, Path targetFile) {
        Optional<MediaAsset> existing = mediaAssetRepository.find(
                "title = ?1 and season = ?2 and episode = ?3 and folderPath = ?4",
                normalizedName,
                parsed.season(),
                parsed.episode(),
                targetDir.toString()).firstResultOptional();

        MediaAsset mediaAsset = existing.orElseGet(MediaAsset::new);
        mediaAsset.title = normalizedName;
        mediaAsset.season = parsed.season();
        mediaAsset.episode = parsed.episode();
        mediaAsset.folderPath = targetDir.toString();
        mediaAsset.fileName = targetFile.getFileName().toString();
        mediaAsset.contentType = MediaAsset.ContentType.SERIES;
        if (mediaAsset.subtitleFileNames == null) {
            mediaAsset.subtitleFileNames = new ArrayList<>();
        }

        mediaAssetRepository.persist(mediaAsset);
        mediaAssetRepository.flush();
    }

    private void appendSubtitle(FileNameParse parsed, String normalizedName, Path targetDir, Path targetFile) {
        Optional<MediaAsset> existing = mediaAssetRepository.find(
                "title = ?1 and season = ?2 and episode = ?3 and folderPath = ?4",
                normalizedName,
                parsed.season(),
                parsed.episode(),
                targetDir.toString()).firstResultOptional();

        if (existing.isEmpty()) {
            log.warn("字幕文件落库跳过，未找到主媒体记录: {}", targetFile.getFileName());
            return;
        }

        MediaAsset mediaAsset = existing.get();
        if (mediaAsset.subtitleFileNames == null) {
            mediaAsset.subtitleFileNames = new ArrayList<>();
        }
        String subtitleFileName = targetFile.getFileName().toString();
        if (!mediaAsset.subtitleFileNames.contains(subtitleFileName)) {
            mediaAsset.subtitleFileNames.add(subtitleFileName);
            mediaAssetRepository.persist(mediaAsset);
            mediaAssetRepository.flush();
        }
    }

    private boolean assetExists(FileNameParse parsed, String normalizedName, Path targetDir) {
        return mediaAssetRepository.find(
                "title = ?1 and season = ?2 and episode = ?3 and folderPath = ?4",
                normalizedName,
                parsed.season(),
                parsed.episode(),
                targetDir.toString()).firstResultOptional().isPresent();
    }

    private boolean isAssetExtension(String extension) {
        return normalizedSet(assetFileExtensions).contains(extension);
    }

    private boolean isSubtitleExtension(String extension) {
        return normalizedSet(subtitleFileExtensions).contains(extension);
    }

    private Set<String> normalizedSet(Set<String> values) {
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }
}
