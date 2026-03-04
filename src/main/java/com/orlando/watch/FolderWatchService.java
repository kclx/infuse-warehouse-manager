package com.orlando.watch;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.quarkus.runtime.Startup;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Startup
@Slf4j
public class FolderWatchService {
    private static final Path WATCH_PATH = Paths.get("/Users/orlando/Documents/Program/warehouse-manager/data");

    @Inject
    FileJobQueue jobQueue;

    @ConfigProperty(name = "watch.ignore-file-names", defaultValue = ".DS_Store")
    Set<String> ignoreFileNames;

    private Set<String> exactIgnoreFileNames;
    private List<Pattern> regexIgnoreFilePatterns;

    @PostConstruct
    void start() {
        initIgnoreRules();
        log.info("文件监听器启动");
        Thread watcherThread = new Thread(this::watch, "folder-watch-thread");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    void watch() {

        try {
            Files.createDirectories(WATCH_PATH);

            log.info("监听路径: {}", WATCH_PATH);

            WatchService watchService = FileSystems.getDefault().newWatchService();

            WATCH_PATH.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE);

            while (true) {
                WatchKey key = watchService.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    Path file = (Path) event.context();
                    if (file == null) {
                        continue;
                    }
                    if (shouldIgnore(file)) {
                        log.debug("忽略文件: {}", file);
                        continue;
                    }

                    FileProcessJob job = new FileProcessJob(WATCH_PATH, file.toString(), 0);
                    jobQueue.offer(job);
                    log.info("文件变化入队: {}, queueSize={}", job.fullPath(), jobQueue.size());
                }

                key.reset();
            }

        } catch (Exception e) {
            log.error("文件监听线程异常退出", e);
        }
    }

    private boolean shouldIgnore(Path file) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        if (exactIgnoreFileNames.contains(fileName)) {
            return true;
        }
        for (Pattern pattern : regexIgnoreFilePatterns) {
            if (pattern.matcher(fileName).matches()) {
                return true;
            }
        }
        return false;
    }

    private void initIgnoreRules() {
        exactIgnoreFileNames = new HashSet<>();
        regexIgnoreFilePatterns = new ArrayList<>();
        for (String rawRule : ignoreFileNames) {
            if (rawRule == null) {
                continue;
            }
            String rule = rawRule.trim();
            if (rule.isEmpty()) {
                continue;
            }

            if (rule.startsWith("re:")) {
                String regex = rule.substring(3).trim();
                try {
                    regexIgnoreFilePatterns.add(Pattern.compile(regex));
                } catch (PatternSyntaxException e) {
                    log.warn("忽略规则正则非法，已跳过: {}", rule);
                }
                continue;
            }

            exactIgnoreFileNames.add(rule);
        }
        log.info("忽略规则加载完成: exact={}, regex={}", exactIgnoreFileNames.size(), regexIgnoreFilePatterns.size());
    }
}
