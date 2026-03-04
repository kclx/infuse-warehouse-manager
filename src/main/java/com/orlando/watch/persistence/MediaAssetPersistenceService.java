package com.orlando.watch.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.orlando.entity.MediaAsset;
import com.orlando.media.model.ParsedMediaFileInfo;
import com.orlando.repository.MediaAssetRepository;
import com.orlando.watch.config.WatchProcessingConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 负责媒体落库规则：
 * - 主媒体文件：创建或更新 `media_asset` 主记录
 * - 字幕文件：仅追加字幕名到 `media_asset_subtitle_file`
 */
@ApplicationScoped
@Slf4j
public class MediaAssetPersistenceService {

    @Inject
    WatchProcessingConfig watchProcessingConfig;

    @Inject
    MediaAssetRepository mediaAssetRepository;

    public boolean shouldPersistAsSubtitleOnly(String extension) {
        return subtitleExtensions().contains(normalizeExtension(extension));
    }

    public boolean canPersistAsAsset(String extension) {
        return mediaExtensions().contains(normalizeExtension(extension));
    }

    public boolean hasAssetRecord(ParsedMediaFileInfo parsedInfo, String normalizedTitle, Path targetDirectory) {
        return mediaAssetRepository.findByEpisodeKey(
                normalizedTitle,
                parsedInfo.season(),
                parsedInfo.episode(),
                targetDirectory.toString()).isPresent();
    }

    public void persistByExtension(ParsedMediaFileInfo parsedInfo, String normalizedTitle, String extension,
            Path targetDirectory, Path targetFile) {
        if (canPersistAsAsset(extension)) {
            upsertMediaAsset(parsedInfo, normalizedTitle, targetDirectory, targetFile);
            return;
        }

        if (shouldPersistAsSubtitleOnly(extension)) {
            appendSubtitleFile(parsedInfo, normalizedTitle, targetDirectory, targetFile);
            return;
        }

        log.info("扩展名不在落库白名单中，跳过落库: {}", targetFile.getFileName());
    }

    private void upsertMediaAsset(ParsedMediaFileInfo parsedInfo, String normalizedTitle, Path targetDirectory,
            Path targetFile) {
        MediaAsset mediaAsset = mediaAssetRepository.findByEpisodeKey(
                normalizedTitle,
                parsedInfo.season(),
                parsedInfo.episode(),
                targetDirectory.toString()).orElseGet(MediaAsset::new);

        mediaAsset.title = normalizedTitle;
        mediaAsset.season = parsedInfo.season();
        mediaAsset.episode = parsedInfo.episode();
        mediaAsset.folderPath = targetDirectory.toString();
        mediaAsset.fileName = targetFile.getFileName().toString();
        mediaAsset.contentType = MediaAsset.ContentType.SERIES;
        if (mediaAsset.subtitleFileNames == null) {
            mediaAsset.subtitleFileNames = new ArrayList<>();
        }

        mediaAssetRepository.persist(mediaAsset);
        mediaAssetRepository.flush();
    }

    private void appendSubtitleFile(ParsedMediaFileInfo parsedInfo, String normalizedTitle, Path targetDirectory,
            Path targetFile) {
        Optional<MediaAsset> existing = mediaAssetRepository.findByEpisodeKey(
                normalizedTitle,
                parsedInfo.season(),
                parsedInfo.episode(),
                targetDirectory.toString());

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

    private Set<String> mediaExtensions() {
        return normalizeSet(watchProcessingConfig.database().assetFileExtensions());
    }

    private Set<String> subtitleExtensions() {
        return normalizeSet(watchProcessingConfig.database().subtitleFileExtensions());
    }

    private Set<String> normalizeSet(Set<String> values) {
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private String normalizeExtension(String extension) {
        return extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
    }
}
