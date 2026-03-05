package com.orlando.watch.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orlando.entity.WatchConfigEntry;
import com.orlando.repository.WatchConfigEntryRepository;

import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动时对比并同步监听配置到数据库。
 */
@ApplicationScoped
@Startup
@Slf4j
public class WatchConfigurationSyncService {

    private static final String CONFIG_SOURCE = "watch-processing.properties";
    private static final String KEY_FILE_NAME_PATTERN = "watch.filename.pattern";
    private static final String KEY_EPISODE_NUMBER_WIDTH = "watch.filename.episode-number-width";
    private static final String KEY_SEASON_NUMBER_WIDTH = "watch.filename.season-number-width";

    @Inject
    WatchProcessingConfig watchProcessingConfig;

    @Inject
    WatchConfigEntryRepository watchConfigEntryRepository;

    @Inject
    WatchFilenameMigrationService watchFilenameMigrationService;

    @Transactional
    void syncOnStartup(@Observes StartupEvent event) {
        Map<String, String> currentConfig = buildCurrentConfigSnapshot();
        Map<String, WatchConfigEntry> databaseConfig = loadDatabaseSnapshot();

        ChangeSummary changeSummary = logDifferences(currentConfig, databaseConfig);
        if (requiresFileNameMigration(changeSummary.changedKeys())) {
            log.info("检测到命名配置变更，开始迁移数据库记录与磁盘文件");
            watchFilenameMigrationService.migrateAllAssetsToCurrentPattern();
        }
        syncToDatabase(currentConfig, databaseConfig);
    }

    private Map<String, String> buildCurrentConfigSnapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, WatchProcessingConfig.Group> entry : watchProcessingConfig.groups().entrySet()) {
            String groupKeyPrefix = "watch.group." + entry.getKey();
            snapshot.put(groupKeyPrefix + ".path", entry.getValue().path());
            snapshot.put(groupKeyPrefix + ".target-path", entry.getValue().targetPath());
        }
        snapshot.put("watch.allow-file-names", joinSet(watchProcessingConfig.allowFileNames()));
        snapshot.put("watch.filename.pattern", watchProcessingConfig.filename().pattern());
        snapshot.put(
                "watch.filename.season-number-width",
                String.valueOf(watchProcessingConfig.filename().seasonNumberWidth()));
        snapshot.put(
                "watch.filename.episode-number-width",
                String.valueOf(watchProcessingConfig.filename().episodeNumberWidth()));
        snapshot.put(
                "watch.db.asset-file-extensions",
                joinSet(watchProcessingConfig.database().assetFileExtensions()));
        snapshot.put(
                "watch.db.subtitle-file-extensions",
                joinSet(watchProcessingConfig.database().subtitleFileExtensions()));
        return snapshot;
    }

    private Map<String, WatchConfigEntry> loadDatabaseSnapshot() {
        Map<String, WatchConfigEntry> snapshot = new LinkedHashMap<>();
        for (WatchConfigEntry entry : watchConfigEntryRepository.listAll()) {
            snapshot.put(entry.configKey, entry);
        }
        return snapshot;
    }

    private ChangeSummary logDifferences(Map<String, String> currentConfig, Map<String, WatchConfigEntry> databaseConfig) {
        List<String> addedKeys = new ArrayList<>();
        List<String> changedKeys = new ArrayList<>();
        List<String> removedKeys = new ArrayList<>();

        for (Map.Entry<String, String> entry : currentConfig.entrySet()) {
            WatchConfigEntry dbEntry = databaseConfig.get(entry.getKey());
            if (dbEntry == null) {
                addedKeys.add(entry.getKey());
                continue;
            }
            if (!entry.getValue().equals(dbEntry.configValue)) {
                changedKeys.add(entry.getKey());
                log.info("配置变更: key={}, dbValue={}, propValue={}",
                        entry.getKey(),
                        dbEntry.configValue,
                        entry.getValue());
            }
        }

        for (String dbKey : databaseConfig.keySet()) {
            if (!currentConfig.containsKey(dbKey)) {
                removedKeys.add(dbKey);
            }
        }

        if (addedKeys.isEmpty() && changedKeys.isEmpty() && removedKeys.isEmpty()) {
            log.info("配置检查完成：数据库与当前 properties 无差异");
            return new ChangeSummary(Set.of(), Set.of(), Set.of());
        }

        if (!addedKeys.isEmpty()) {
            log.info("配置新增项: {}", addedKeys);
        }
        if (!changedKeys.isEmpty()) {
            log.info("配置更新项: {}", changedKeys);
        }
        if (!removedKeys.isEmpty()) {
            log.info("配置删除项: {}", removedKeys);
        }

        return new ChangeSummary(
                new HashSet<>(addedKeys),
                new HashSet<>(changedKeys),
                new HashSet<>(removedKeys));
    }

    private void syncToDatabase(Map<String, String> currentConfig, Map<String, WatchConfigEntry> databaseConfig) {
        for (Map.Entry<String, String> entry : currentConfig.entrySet()) {
            WatchConfigEntry dbEntry = databaseConfig.get(entry.getKey());
            if (dbEntry == null) {
                WatchConfigEntry newEntry = new WatchConfigEntry();
                newEntry.configKey = entry.getKey();
                newEntry.configValue = entry.getValue();
                newEntry.configSource = CONFIG_SOURCE;
                watchConfigEntryRepository.persist(newEntry);
                continue;
            }

            dbEntry.configValue = entry.getValue();
            dbEntry.configSource = CONFIG_SOURCE;
        }

        for (Map.Entry<String, WatchConfigEntry> entry : databaseConfig.entrySet()) {
            if (!currentConfig.containsKey(entry.getKey())) {
                watchConfigEntryRepository.delete(entry.getValue());
            }
        }

        watchConfigEntryRepository.flush();
        log.info("配置同步完成：已将 properties 配置写入数据库表 watch_config_entry");
    }

    private String joinSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }

        List<String> sortedValues = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                sortedValues.add(value.trim());
            }
        }
        Collections.sort(sortedValues);
        return String.join(",", sortedValues);
    }

    private boolean requiresFileNameMigration(Set<String> changedKeys) {
        return changedKeys.contains(KEY_FILE_NAME_PATTERN)
                || changedKeys.contains(KEY_EPISODE_NUMBER_WIDTH)
                || changedKeys.contains(KEY_SEASON_NUMBER_WIDTH);
    }

    private record ChangeSummary(Set<String> addedKeys, Set<String> changedKeys, Set<String> removedKeys) {
    }
}
