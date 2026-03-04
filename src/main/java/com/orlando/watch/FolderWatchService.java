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
    @Inject
    FileJobQueue jobQueue;

    @ConfigProperty(name = "watch.path", defaultValue = "/Users/orlando/Documents/Program/warehouse-manager/data")
    String watchPath;

    @ConfigProperty(name = "watch.allow-file-names", defaultValue = "re:.*\\.(mp4|mkv|ass|ssa|srt|vtt)$")
    Set<String> allowFileNames;

    private Set<String> exactAllowFileNames;
    private List<Pattern> regexAllowFilePatterns;

    @PostConstruct
    void start() {
        initAllowRules();
        log.info("文件监听器启动");
        Thread watcherThread = new Thread(this::watch, "folder-watch-thread");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    void watch() {

        try {
            Path watchDir = Paths.get(watchPath);
            Files.createDirectories(watchDir);

            log.info("监听路径: {}", watchDir);

            WatchService watchService = FileSystems.getDefault().newWatchService();

            watchDir.register(
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
                    if (!shouldProcess(file)) {
                        log.debug("不在白名单内，跳过文件: {}", file);
                        continue;
                    }

                    FileProcessJob job = new FileProcessJob(watchDir, file.toString(), 0);
                    jobQueue.offer(job);
                    log.info("文件变化入队: {}, queueSize={}", job.fullPath(), jobQueue.size());
                }

                key.reset();
            }

        } catch (Exception e) {
            log.error("文件监听线程异常退出", e);
        }
    }

    private boolean shouldProcess(Path file) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        if (exactAllowFileNames.contains(fileName)) {
            return true;
        }
        for (Pattern pattern : regexAllowFilePatterns) {
            if (pattern.matcher(fileName).matches()) {
                return true;
            }
        }
        return false;
    }

    private void initAllowRules() {
        exactAllowFileNames = new HashSet<>();
        regexAllowFilePatterns = new ArrayList<>();
        for (String rawRule : allowFileNames) {
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
                    regexAllowFilePatterns.add(Pattern.compile(regex));
                } catch (PatternSyntaxException e) {
                    log.warn("白名单规则正则非法，已跳过: {}", rule);
                }
                continue;
            }

            exactAllowFileNames.add(rule);
        }
        log.info("白名单规则加载完成: exact={}, regex={}", exactAllowFileNames.size(), regexAllowFilePatterns.size());
    }
}
