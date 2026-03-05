package com.orlando.watch.processor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

import com.orlando.entity.MediaAsset;
import com.orlando.repository.MediaAssetRepository;
import com.orlando.watch.config.WatchProcessingConfig;
import com.orlando.watch.naming.OutputFileNameBuilder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

/**
 * 在目录改名后，批量重命名该目录内文件并同步数据库记录。
 */
@ApplicationScoped
@Slf4j
public class FolderRenameBatchProcessor {

    private static final String NAME_TOKEN = "<<NAME_TOKEN>>";
    private static final String SEASON_TOKEN = "<<SEASON_TOKEN>>";
    private static final String EPISODE_TOKEN = "<<EPISODE_TOKEN>>";
    private static final String EXTENSION_TOKEN = "<<EXTENSION_TOKEN>>";

    @Inject
    OutputFileNameBuilder outputFileNameBuilder;

    @Inject
    WatchProcessingConfig watchProcessingConfig;

    @Inject
    MediaAssetRepository mediaAssetRepository;

    private Pattern episodeFilePattern;

    @PostConstruct
    void init() {
        episodeFilePattern = buildEpisodeFilePattern(watchProcessingConfig.filename().pattern());
    }

    @Transactional
    public void processFolderRename(Path oldDirectory, Path newDirectory) {
        if (!Files.isDirectory(newDirectory)) {
            log.warn("目录改名处理跳过，目标目录不存在: {}", newDirectory);
            return;
        }

        String newTitle = newDirectory.getFileName().toString();
        Map<EpisodeKey, String> mediaFileNameByEpisode = new HashMap<>();
        Map<EpisodeKey, List<String>> subtitleFileNamesByEpisode = new HashMap<>();

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
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                    log.info("目录改名触发文件重命名: {} -> {}", file.getFileName(), target.getFileName());
                }

                EpisodeKey key = new EpisodeKey(parsed.season(), parsed.episode());
                if (isMediaExtension(parsed.extension())) {
                    mediaFileNameByEpisode.put(key, newFileName);
                } else if (isSubtitleExtension(parsed.extension())) {
                    subtitleFileNamesByEpisode.computeIfAbsent(key, ignored -> new ArrayList<>()).add(newFileName);
                }
            }
        } catch (Exception ex) {
            log.error("目录改名批量重命名失败: {}", newDirectory, ex);
            return;
        }

        updateDatabaseAfterFolderRename(oldDirectory, newDirectory, newTitle, mediaFileNameByEpisode, subtitleFileNamesByEpisode);
    }

    private void updateDatabaseAfterFolderRename(
            Path oldDirectory,
            Path newDirectory,
            String newTitle,
            Map<EpisodeKey, String> mediaFileNameByEpisode,
            Map<EpisodeKey, List<String>> subtitleFileNamesByEpisode) {
        List<MediaAsset> assets = mediaAssetRepository.list("folderPath", oldDirectory.toString());
        if (assets.isEmpty()) {
            assets = mediaAssetRepository.list("folderPath", newDirectory.toString());
        }

        for (MediaAsset asset : assets) {
            asset.title = newTitle;
            asset.folderPath = newDirectory.toString();

            EpisodeKey key = new EpisodeKey(asset.season, asset.episode);
            String mediaFileName = mediaFileNameByEpisode.get(key);
            if (mediaFileName != null) {
                asset.fileName = mediaFileName;
            }

            List<String> subtitleNames = subtitleFileNamesByEpisode.get(key);
            if (subtitleNames != null) {
                asset.subtitleFileNames = new ArrayList<>(subtitleNames);
            }
        }

        mediaAssetRepository.flush();
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

    private boolean isMediaExtension(String extension) {
        return normalizedSet(watchProcessingConfig.database().assetFileExtensions()).contains(extension.toLowerCase(Locale.ROOT));
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

        String tokenizedPattern = configuredPattern
                .replace("{name}", NAME_TOKEN)
                .replace("{snumber}", SEASON_TOKEN)
                .replace("{enumber}", EPISODE_TOKEN)
                .replace("{ext}", EXTENSION_TOKEN);

        if (!tokenizedPattern.contains(SEASON_TOKEN)
                || !tokenizedPattern.contains(EPISODE_TOKEN)
                || !tokenizedPattern.contains(EXTENSION_TOKEN)) {
            log.error("文件名模板缺少必要占位符(snumber/enumber/ext): {}", configuredPattern);
            return null;
        }

        String regex = Pattern.quote(tokenizedPattern)
                .replace(Pattern.quote(NAME_TOKEN), ".+?")
                .replace(Pattern.quote(SEASON_TOKEN), "(?<season>\\\\d+)")
                .replace(Pattern.quote(EPISODE_TOKEN), "(?<episode>\\\\d+)")
                .replace(Pattern.quote(EXTENSION_TOKEN), "(?<ext>[^.]+)");

        Pattern pattern = Pattern.compile("(?i)^" + regex + "$");
        log.info("目录改名解析规则已加载: {}", configuredPattern);
        return pattern;
    }

    private record EpisodeKey(Integer season, Integer episode) {
    }

    private record EpisodeFile(Integer season, Integer episode, String extension) {
    }
}
