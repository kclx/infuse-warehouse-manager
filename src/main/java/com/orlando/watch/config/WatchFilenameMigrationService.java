package com.orlando.watch.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.orlando.entity.MediaAsset;
import com.orlando.repository.MediaAssetRepository;
import com.orlando.watch.naming.OutputFileNameBuilder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 当命名配置发生变化时，重命名已有文件并同步更新数据库中的文件名字段。
 */
@ApplicationScoped
@Slf4j
public class WatchFilenameMigrationService {

    @Inject
    MediaAssetRepository mediaAssetRepository;

    @Inject
    OutputFileNameBuilder outputFileNameBuilder;

    public void migrateAllAssetsToCurrentPattern() {
        List<MediaAsset> assets = mediaAssetRepository.listAll();
        int totalAssets = assets.size();
        int updatedAssets = 0;

        for (MediaAsset asset : assets) {
            boolean changed = migrateSingleAsset(asset);
            if (changed) {
                updatedAssets++;
            }
        }

        mediaAssetRepository.flush();
        log.info("命名配置迁移完成: totalAssets={}, updatedAssets={}", totalAssets, updatedAssets);
    }

    private boolean migrateSingleAsset(MediaAsset asset) {
        if (asset.folderPath == null || asset.folderPath.isBlank()) {
            log.warn("跳过迁移，folderPath 为空: mediaAssetId={}", asset.id);
            return false;
        }
        if (asset.title == null || asset.title.isBlank() || asset.season == null || asset.episode == null) {
            log.warn("跳过迁移，标题或季集信息不完整: mediaAssetId={}", asset.id);
            return false;
        }

        Path folder = Path.of(asset.folderPath);
        boolean changed = false;

        if (asset.fileName != null && !asset.fileName.isBlank()) {
            String migratedAssetFileName = outputFileNameBuilder.build(
                    asset.title,
                    asset.season,
                    asset.episode,
                    extensionOf(asset.fileName));
            if (ensureMigratedOnDisk(folder, asset.fileName, migratedAssetFileName)) {
                asset.fileName = migratedAssetFileName;
                changed = true;
            }
        }

        if (asset.subtitleFileNames != null && !asset.subtitleFileNames.isEmpty()) {
            List<String> migratedSubtitleFileNames = new ArrayList<>();
            boolean subtitleChanged = false;
            for (String subtitleFileName : asset.subtitleFileNames) {
                if (subtitleFileName == null || subtitleFileName.isBlank()) {
                    continue;
                }
                String migratedSubtitleFileName = outputFileNameBuilder.build(
                        asset.title,
                        asset.season,
                        asset.episode,
                        extensionOf(subtitleFileName));
                boolean renamed = ensureMigratedOnDisk(folder, subtitleFileName, migratedSubtitleFileName);
                migratedSubtitleFileNames.add(renamed ? migratedSubtitleFileName : subtitleFileName);
                if (renamed) {
                    subtitleChanged = true;
                }
            }

            if (!migratedSubtitleFileNames.isEmpty()) {
                asset.subtitleFileNames = migratedSubtitleFileNames;
                changed = changed || subtitleChanged;
            }
        }

        return changed;
    }

    private boolean ensureMigratedOnDisk(Path folder, String oldFileName, String newFileName) {
        if (oldFileName.equals(newFileName)) {
            return false;
        }

        Path source = folder.resolve(oldFileName);
        Path target = folder.resolve(newFileName);

        if (!Files.exists(source) && Files.exists(target)) {
            log.info("检测到目标文件已存在，直接修正数据库文件名: {}", target);
            return true;
        }
        if (!Files.exists(source)) {
            log.warn("文件迁移失败，源文件不存在且目标文件不存在: {}", source);
            return false;
        }
        if (Files.exists(target)) {
            log.warn("文件迁移失败，源文件和目标文件同时存在: source={}, target={}", source, target);
            return false;
        }

        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            log.info("命名配置迁移重命名: {} -> {}", source.getFileName(), target.getFileName());
            return true;
        } catch (Exception ex) {
            try {
                Files.move(source, target);
                log.info("命名配置迁移重命名(非原子): {} -> {}", source.getFileName(), target.getFileName());
                return true;
            } catch (Exception fallbackEx) {
                log.error("命名配置迁移重命名失败: {} -> {}", source, target, fallbackEx);
                return false;
            }
        }
    }

    private String extensionOf(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1);
    }
}
