package com.orlando.watch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import com.orlando.dto.FileNameParse;
import com.orlando.service.FileNameParseService;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class FileProcessWorker {
    private static final int MAX_ATTEMPTS = 3;

    @Inject
    FileJobQueue jobQueue;

    @Inject
    FileNameParseService fileNameParseService;

    @Scheduled(every = "1s")
    @ActivateRequestContext
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
            if (!isValidParse(parsed)) {
                retry(job, "AI 未能稳定识别 name/episode");
                return;
            }

            String normalizedName = normalizeName(parsed.name());
            String extension = extensionOf(originalFileName);
            String targetFileName = buildTargetFileName(normalizedName, parsed.season(), parsed.episode(), extension);

            Path targetDir = sourceFile.getParent().resolve(normalizedName);
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(targetFileName);

            if (sourceFile.equals(targetFile)) {
                log.info("文件已在目标位置: {}", sourceFile);
                return;
            }

            Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
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
        String fileName = String.format(Locale.ROOT, "%s_s%02d.e%02d", filenameSafe(name), season, episode);
        return ext.isBlank() ? fileName : fileName + "." + ext;
    }

    private String normalizeName(String raw) {
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return "Unknown";
        }
        return cleaned;
    }

    private String filenameSafe(String name) {
        return name.replace(" ", "_");
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1);
    }
}
