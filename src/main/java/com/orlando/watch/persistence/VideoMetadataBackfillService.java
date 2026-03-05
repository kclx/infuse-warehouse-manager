package com.orlando.watch.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.orlando.entity.MediaAssetVideoFile;
import com.orlando.repository.MediaAssetVideoFileRepository;

import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动时回填历史视频文件的 ffprobe 元信息。
 */
@ApplicationScoped
@Startup
@Slf4j
public class VideoMetadataBackfillService {

    @Inject
    MediaAssetVideoFileRepository mediaAssetVideoFileRepository;

    @Inject
    VideoMetadataProbeService videoMetadataProbeService;

    @Transactional
    void backfillOnStartup(@Observes StartupEvent event) {
        List<MediaAssetVideoFile> missing = mediaAssetVideoFileRepository.listMissingMetadata();
        if (missing.isEmpty()) {
            return;
        }

        int scanned = 0;
        int updated = 0;

        for (MediaAssetVideoFile videoFile : missing) {
            scanned++;
            if (videoFile.filePath == null || videoFile.filePath.isBlank()) {
                continue;
            }

            Path path = Path.of(videoFile.filePath);
            if (!Files.exists(path)) {
                continue;
            }

            VideoMetadataProbeService.VideoMetadata metadata = videoMetadataProbeService.probe(path);
            videoFile.videoWidth = metadata.videoWidth();
            videoFile.videoHeight = metadata.videoHeight();
            videoFile.videoCodec = metadata.videoCodec();
            videoFile.audioCodec = metadata.audioCodec();
            videoFile.durationMs = metadata.durationMs();
            videoFile.fileSizeBytes = metadata.fileSizeBytes();
            updated++;
        }

        mediaAssetVideoFileRepository.flush();

        if (scanned > 0) {
            log.info("视频元信息回填完成: scanned={}, updated={}", scanned, updated);
        }
    }
}
