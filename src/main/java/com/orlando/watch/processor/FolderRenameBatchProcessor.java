package com.orlando.watch.processor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.orlando.entity.MediaAsset;
import com.orlando.entity.MediaAssetVideoFile;
import com.orlando.repository.MediaAssetRepository;
import com.orlando.repository.MediaAssetVideoFileRepository;
import com.orlando.watch.config.WatchProcessingConfig;
import com.orlando.watch.naming.MovieFileNameResolver;
import com.orlando.watch.naming.OutputFileNameBuilder;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

/**
 * 在目录改名后，批量重命名该目录内文件并同步数据库记录。
 */
@ApplicationScoped
@Slf4j
public class FolderRenameBatchProcessor {

    @Inject
    OutputFileNameBuilder outputFileNameBuilder;

    @Inject
    MovieFileNameResolver movieFileNameResolver;

    @Inject
    WatchProcessingConfig watchProcessingConfig;

    @Inject
    MediaAssetRepository mediaAssetRepository;

    @Inject
    MediaAssetVideoFileRepository mediaAssetVideoFileRepository;

    private Pattern episodeFilePattern;

    @PostConstruct
    void init() {
        episodeFilePattern = buildEpisodeFilePattern(watchProcessingConfig.filename().pattern());
    }

    @Transactional
    @ActivateRequestContext
    public void processFolderRename(Path oldDirectory, Path newDirectory) {
        if (!Files.isDirectory(newDirectory)) {
            log.warn("目录改名处理跳过，目标目录不存在: {}", newDirectory);
            return;
        }

        String newTitle = newDirectory.getFileName().toString();
        List<MediaAsset> assets = findAssetsByDirectory(oldDirectory, newDirectory);
        if (isMovieMode(assets)) {
            processMovieFolderRename(oldDirectory, newDirectory, newTitle, assets);
            return;
        }

        Map<EpisodeKey, List<String>> subtitleFileNamesByEpisode = new HashMap<>();
        Map<String, String> renamedFileMap = new HashMap<>();

        try {
            List<Path> files = Files.list(newDirectory)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

            for (Path file : files) {
                EpisodeFile parsed = parseEpisodeFile(file.getFileName().toString());
                if (parsed == null) {
                    log.warn("文件名不符合剧集命名格式，跳过: {}", file.getFileName());
                    continue;
                }

                String newFileName = outputFileNameBuilder.build(newTitle, parsed.season(), parsed.episode(), parsed.extension());
                Path target = newDirectory.resolve(newFileName);
                if (!file.equals(target)) {
                    if (Files.exists(target)) {
                        target = resolveNonConflictingSeriesTarget(newDirectory, newFileName);
                    }
                    Files.move(file, target);
                    log.info("目录改名触发文件重命名: {} -> {}", file.getFileName(), target.getFileName());
                }

                String finalFileName = target.getFileName().toString();
                renamedFileMap.put(file.getFileName().toString(), finalFileName);
                EpisodeKey key = new EpisodeKey(parsed.season(), parsed.episode());
                if (isSubtitleExtension(parsed.extension())) {
                    subtitleFileNamesByEpisode.computeIfAbsent(key, ignored -> new ArrayList<>()).add(finalFileName);
                }
            }
        } catch (Exception ex) {
            log.error("目录改名批量重命名失败: {}", newDirectory, ex);
            return;
        }

        updateSeriesDatabaseAfterFolderRename(oldDirectory, newDirectory, newTitle, subtitleFileNamesByEpisode, renamedFileMap);
    }

    private void processMovieFolderRename(
            Path oldDirectory,
            Path newDirectory,
            String newTitle,
            List<MediaAsset> assets) {
        Map<String, String> renamedFileMap = new HashMap<>();
        Set<String> reservedNames = new HashSet<>();

        try {
            List<Path> files = Files.list(newDirectory)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

            for (Path file : files) {
                String oldFileName = file.getFileName().toString();
                String extension = extensionOf(oldFileName);
                String editionTag = movieFileNameResolver.extractEditionTag(oldFileName);
                String newFileName = resolveMovieRenameTargetName(newDirectory, newTitle, editionTag, extension, oldFileName, reservedNames);
                Path target = newDirectory.resolve(newFileName);

                if (!oldFileName.equals(newFileName)) {
                    Files.move(file, target);
                    log.info("目录改名触发电影文件重命名: {} -> {}", oldFileName, newFileName);
                }
                reservedNames.add(newFileName);
                renamedFileMap.put(oldFileName, newFileName);
            }
        } catch (Exception ex) {
            log.error("目录改名电影文件批量重命名失败: {}", newDirectory, ex);
            return;
        }

        for (MediaAsset asset : assets) {
            asset.title = newTitle;
            asset.folderPath = newDirectory.toString();
            asset.fileName = null;
        }
        mediaAssetRepository.flush();

        List<MediaAssetVideoFile> allVideoFiles = assets.stream()
                .flatMap(asset -> mediaAssetVideoFileRepository.listByMediaAssetId(asset.id).stream())
                .collect(Collectors.toList());
        for (MediaAssetVideoFile videoFile : allVideoFiles) {
            String mappedName = resolveMovieMappedNameWhenFolderRenamed(
                    newDirectory,
                    newTitle,
                    videoFile,
                    renamedFileMap,
                    reservedNames);
            if (mappedName == null || mappedName.isBlank()) {
                log.warn("电影视频文件记录缺少 fileName，跳过更新: videoFileId={}", videoFile.id);
                continue;
            }
            videoFile.fileName = mappedName;
            videoFile.filePath = newDirectory.resolve(mappedName).toString();
            videoFile.editionTag = movieFileNameResolver.extractEditionTag(mappedName);
        }
        mediaAssetVideoFileRepository.flush();

        log.info("目录改名电影库同步完成: {} -> {}", oldDirectory.getFileName(), newDirectory.getFileName());
    }

    /**
     * 目录改名场景下兜底解析视频文件名映射：
     * 1. 先使用本次批量文件重命名得到的映射；
     * 2. 若映射缺失，按数据库记录尝试补做一次重命名；
     * 3. 若仍无法重命名，至少保证路径切换到新目录。
     */
    private String resolveMovieMappedNameWhenFolderRenamed(
            Path newDirectory,
            String newTitle,
            MediaAssetVideoFile videoFile,
            Map<String, String> renamedFileMap,
            Set<String> reservedNames) {
        String currentFileName = videoFile.fileName;
        if (currentFileName == null || currentFileName.isBlank()) {
            return null;
        }

        String mappedName = renamedFileMap.get(currentFileName);
        if (mappedName != null && !mappedName.isBlank()) {
            reservedNames.add(mappedName);
            return mappedName;
        }

        String editionTag = (videoFile.editionTag == null || videoFile.editionTag.isBlank())
                ? movieFileNameResolver.extractEditionTag(currentFileName)
                : videoFile.editionTag;
        String extension = extensionOf(currentFileName);
        String expectedFileName = resolveMovieRenameTargetName(
                newDirectory,
                newTitle,
                editionTag,
                extension,
                currentFileName,
                reservedNames);
        Path currentPath = newDirectory.resolve(currentFileName);
        Path targetPath = newDirectory.resolve(expectedFileName);

        if (!currentFileName.equals(expectedFileName) && Files.exists(currentPath)) {
            try {
                Files.move(currentPath, targetPath);
                log.info("目录改名触发电影文件兜底重命名: {} -> {}", currentFileName, expectedFileName);
                renamedFileMap.put(currentFileName, expectedFileName);
                reservedNames.add(expectedFileName);
                return expectedFileName;
            } catch (Exception ex) {
                log.warn("目录改名电影文件兜底重命名失败，保留原文件名: {} -> {}", currentFileName, expectedFileName, ex);
            }
        }

        // 目录已改名但文件名映射缺失时，至少保证 file_path 同步到新目录。
        reservedNames.add(currentFileName);
        return currentFileName;
    }

    private void updateSeriesDatabaseAfterFolderRename(
            Path oldDirectory,
            Path newDirectory,
            String newTitle,
            Map<EpisodeKey, List<String>> subtitleFileNamesByEpisode,
            Map<String, String> renamedFileMap) {
        List<MediaAsset> assets = findAssetsByDirectory(oldDirectory, newDirectory);

        for (MediaAsset asset : assets) {
            asset.title = newTitle;
            asset.folderPath = newDirectory.toString();
            asset.fileName = null;

            EpisodeKey key = new EpisodeKey(asset.season, asset.episode);
            List<String> subtitleNames = subtitleFileNamesByEpisode.get(key);
            if (subtitleNames != null) {
                asset.subtitleFileNames = new ArrayList<>(subtitleNames);
            }

            List<MediaAssetVideoFile> videoFiles = mediaAssetVideoFileRepository.listByMediaAssetId(asset.id);
            for (MediaAssetVideoFile videoFile : videoFiles) {
                String mappedName = renamedFileMap.get(videoFile.fileName);
                if (mappedName != null) {
                    videoFile.fileName = mappedName;
                }
                videoFile.filePath = newDirectory.resolve(videoFile.fileName).toString();
                videoFile.editionTag = "正片";
            }
        }

        mediaAssetRepository.flush();
        mediaAssetVideoFileRepository.flush();
        log.info("目录改名数据库同步完成: {} -> {}", oldDirectory.getFileName(), newDirectory.getFileName());
    }

    private EpisodeFile parseEpisodeFile(String fileName) {
        if (episodeFilePattern == null) {
            return null;
        }

        Matcher matcher = episodeFilePattern.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }

        return new EpisodeFile(
                Integer.parseInt(matcher.group("season")),
                Integer.parseInt(matcher.group("episode")),
                matcher.group("ext").toLowerCase(Locale.ROOT));
    }

    private boolean isSubtitleExtension(String extension) {
        return normalizedSet(watchProcessingConfig.database().subtitleFileExtensions()).contains(extension.toLowerCase(Locale.ROOT));
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

    private Pattern buildEpisodeFilePattern(String configuredPattern) {
        if (configuredPattern == null || configuredPattern.isBlank()) {
            log.error("文件名模板为空，无法构建目录改名解析规则");
            return null;
        }

        if (!configuredPattern.contains("{snumber}")
                || !configuredPattern.contains("{enumber}")
                || !configuredPattern.contains("{ext}")) {
            log.error("文件名模板缺少必要占位符(snumber/enumber/ext): {}", configuredPattern);
            return null;
        }

        String regex = toEpisodeRegex(configuredPattern);
        Pattern pattern = Pattern.compile(regex);
        log.info("目录改名解析规则已加载: {}", configuredPattern);
        return pattern;
    }

    private String toEpisodeRegex(String configuredPattern) {
        StringBuilder regex = new StringBuilder("(?i)^");
        int index = 0;
        while (index < configuredPattern.length()) {
            int next = configuredPattern.indexOf('{', index);
            if (next < 0) {
                regex.append(Pattern.quote(configuredPattern.substring(index)));
                break;
            }

            if (next > index) {
                regex.append(Pattern.quote(configuredPattern.substring(index, next)));
            }

            if (configuredPattern.startsWith("{name}", next)) {
                regex.append(".+?");
                index = next + "{name}".length();
                continue;
            }
            if (configuredPattern.startsWith("{snumber}", next)) {
                regex.append("(?<season>\\d+)");
                index = next + "{snumber}".length();
                continue;
            }
            if (configuredPattern.startsWith("{enumber}", next)) {
                regex.append("(?<episode>\\d+)");
                index = next + "{enumber}".length();
                continue;
            }
            if (configuredPattern.startsWith("{ext}", next)) {
                regex.append("(?<ext>[^.]+)");
                index = next + "{ext}".length();
                continue;
            }

            // 未识别占位符时按普通字符处理，保证行为可预期。
            regex.append(Pattern.quote("{"));
            index = next + 1;
        }
        regex.append("$");
        return regex.toString();
    }

    private List<MediaAsset> findAssetsByDirectory(Path oldDirectory, Path newDirectory) {
        List<MediaAsset> assets = mediaAssetRepository.list("folderPath", oldDirectory.toString());
        if (assets.isEmpty()) {
            assets = mediaAssetRepository.list("folderPath", newDirectory.toString());
        }
        return assets;
    }

    private boolean isMovieMode(List<MediaAsset> assets) {
        if (assets.isEmpty()) {
            return false;
        }
        for (MediaAsset asset : assets) {
            if (asset.contentType == MediaAsset.ContentType.MOVIE || (asset.season == null && asset.episode == null)) {
                return true;
            }
        }
        return false;
    }

    private Path resolveNonConflictingSeriesTarget(Path directory, String desiredFileName) {
        String extension = extensionOf(desiredFileName);
        String baseName = stripExtension(desiredFileName);
        String suffix = extension.isBlank() ? "" : "." + extension;
        int sequence = 2;
        String candidate = baseName + "_" + sequence + suffix;
        while (Files.exists(directory.resolve(candidate))) {
            sequence++;
            candidate = baseName + "_" + sequence + suffix;
        }
        return directory.resolve(candidate);
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }

    private String extensionOf(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String resolveMovieRenameTargetName(
            Path directory,
            String title,
            String editionTag,
            String extension,
            String currentFileName,
            Set<String> reservedNames) {
        String baseName = movieFileNameResolver.buildMovieBaseName(title, editionTag);
        String suffix = extension.isBlank() ? "" : "." + extension;
        String candidate = baseName + suffix;
        if (!isOccupied(directory, candidate, currentFileName, reservedNames)) {
            return candidate;
        }

        int sequence = 2;
        while (true) {
            candidate = baseName + "_" + sequence + suffix;
            if (!isOccupied(directory, candidate, currentFileName, reservedNames)) {
                return candidate;
            }
            sequence++;
        }
    }

    private boolean isOccupied(Path directory, String candidate, String currentFileName, Set<String> reservedNames) {
        if (candidate.equals(currentFileName)) {
            return false;
        }
        if (reservedNames.contains(candidate)) {
            return true;
        }
        return Files.exists(directory.resolve(candidate));
    }

    private record EpisodeKey(Integer season, Integer episode) {
    }

    private record EpisodeFile(Integer season, Integer episode, String extension) {
    }
}
