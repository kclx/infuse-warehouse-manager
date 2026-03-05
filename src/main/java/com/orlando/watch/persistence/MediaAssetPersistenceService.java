package com.orlando.watch.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.orlando.entity.MediaAsset;
import com.orlando.entity.MediaAssetVideoFile;
import com.orlando.media.model.ParsedMediaFileInfo;
import com.orlando.repository.MediaAssetRepository;
import com.orlando.repository.MediaAssetVideoFileRepository;
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

    @Inject
    MediaAssetVideoFileRepository mediaAssetVideoFileRepository;

    @Inject
    VideoMetadataProbeService videoMetadataProbeService;

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

    public boolean hasAssetRecord(ParsedMediaFileInfo parsedInfo, String normalizedTitle, Path targetDirectory, boolean singleEpisodeOnly) {
        Integer season = singleEpisodeOnly ? null : parsedInfo.season();
        Integer episode = singleEpisodeOnly ? null : parsedInfo.episode();
        return mediaAssetRepository.findByEpisodeKey(
                normalizedTitle,
                season,
                episode,
                targetDirectory.toString()).isPresent();
    }

    public void persistByExtension(ParsedMediaFileInfo parsedInfo, String normalizedTitle, String extension,
            Path targetDirectory, Path targetFile, boolean singleEpisodeOnly, String editionTag) {
        if (canPersistAsAsset(extension)) {
            upsertMediaAsset(parsedInfo, normalizedTitle, targetDirectory, targetFile, singleEpisodeOnly, editionTag);
            return;
        }

        if (shouldPersistAsSubtitleOnly(extension)) {
            appendSubtitleFile(parsedInfo, normalizedTitle, targetDirectory, targetFile, singleEpisodeOnly);
            return;
        }

        log.info("扩展名不在落库白名单中，跳过落库: {}", targetFile.getFileName());
    }

    private void upsertMediaAsset(ParsedMediaFileInfo parsedInfo, String normalizedTitle, Path targetDirectory,
            Path targetFile, boolean singleEpisodeOnly, String editionTag) {
        Integer season = singleEpisodeOnly ? null : parsedInfo.season();
        Integer episode = singleEpisodeOnly ? null : parsedInfo.episode();
        MediaAsset mediaAsset = mediaAssetRepository.findByEpisodeKey(
                normalizedTitle,
                season,
                episode,
                targetDirectory.toString()).orElseGet(MediaAsset::new);

        mediaAsset.title = normalizedTitle;
        mediaAsset.season = season;
        mediaAsset.episode = episode;
        mediaAsset.folderPath = targetDirectory.toString();
        mediaAsset.fileName = singleEpisodeOnly ? null : targetFile.getFileName().toString();
        mediaAsset.contentType = singleEpisodeOnly ? MediaAsset.ContentType.MOVIE : MediaAsset.ContentType.SERIES;
        if (mediaAsset.subtitleFileNames == null) {
            mediaAsset.subtitleFileNames = new ArrayList<>();
        }

        mediaAssetRepository.persist(mediaAsset);
        if (singleEpisodeOnly) {
            upsertMovieVideoFile(mediaAsset, targetFile, editionTag);
        }
        mediaAssetRepository.flush();
    }

    private void upsertMovieVideoFile(MediaAsset mediaAsset, Path targetFile, String editionTag) {
        String filePath = targetFile.toString();
        MediaAssetVideoFile videoFile = mediaAssetVideoFileRepository.findByFilePath(filePath).orElseGet(MediaAssetVideoFile::new);
        VideoMetadataProbeService.VideoMetadata metadata = videoMetadataProbeService.probe(targetFile);

        videoFile.mediaAsset = mediaAsset;
        videoFile.fileName = targetFile.getFileName().toString();
        videoFile.filePath = filePath;
        videoFile.fileExt = extensionOf(videoFile.fileName);
        videoFile.fileSizeBytes = metadata.fileSizeBytes();
        videoFile.videoWidth = metadata.videoWidth();
        videoFile.videoHeight = metadata.videoHeight();
        videoFile.videoCodec = metadata.videoCodec();
        videoFile.audioCodec = metadata.audioCodec();
        videoFile.durationMs = metadata.durationMs();
        videoFile.editionTag = normalizeEditionTag(editionTag);
        videoFile.isPrimary = Boolean.FALSE;

        mediaAssetVideoFileRepository.persist(videoFile);
        mediaAssetVideoFileRepository.flush();
    }

    private void appendSubtitleFile(ParsedMediaFileInfo parsedInfo, String normalizedTitle, Path targetDirectory,
            Path targetFile, boolean singleEpisodeOnly) {
        Integer season = singleEpisodeOnly ? null : parsedInfo.season();
        Integer episode = singleEpisodeOnly ? null : parsedInfo.episode();
        Optional<MediaAsset> existing = mediaAssetRepository.findByEpisodeKey(
                normalizedTitle,
                season,
                episode,
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

    private String normalizeEditionTag(String editionTag) {
        if (editionTag == null || editionTag.isBlank()) {
            return "正片";
        }
        return editionTag.trim();
    }

    private String extensionOf(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
