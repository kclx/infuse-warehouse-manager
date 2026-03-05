package com.orlando.watch.persistence;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 调用 ffprobe 获取视频元信息，失败时降级为空值。
 */
@ApplicationScoped
@Slf4j
public class VideoMetadataProbeService {

    @Inject
    ObjectMapper objectMapper;

    public VideoMetadata probe(Path filePath) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffprobe",
                    "-v",
                    "quiet",
                    "-print_format",
                    "json",
                    "-show_streams",
                    "-show_format",
                    filePath.toString());
            Process process = processBuilder.start();

            StringBuilder outputBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuilder.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("ffprobe 执行失败: file={}, exitCode={}", filePath, exitCode);
                return VideoMetadata.empty();
            }

            JsonNode root = objectMapper.readTree(outputBuilder.toString());
            JsonNode streams = root.path("streams");
            JsonNode format = root.path("format");

            JsonNode videoStream = firstStreamByType(streams, "video");
            JsonNode audioStream = firstStreamByType(streams, "audio");

            Long durationMs = parseDurationMs(format.path("duration").asText(null));
            Long fileSize = parseLong(format.path("size").asText(null));
            Integer width = parseInteger(videoStream.path("width").asText(null));
            Integer height = parseInteger(videoStream.path("height").asText(null));
            String videoCodec = textOrNull(videoStream.path("codec_name"));
            String audioCodec = textOrNull(audioStream.path("codec_name"));

            return new VideoMetadata(width, height, videoCodec, audioCodec, durationMs, fileSize);
        } catch (Exception ex) {
            log.warn("ffprobe 元信息采集失败，使用空元信息: file={}", filePath, ex);
            return VideoMetadata.empty();
        }
    }

    private JsonNode firstStreamByType(JsonNode streams, String codecType) {
        if (streams == null || !streams.isArray()) {
            return objectMapper.nullNode();
        }
        Iterator<JsonNode> iterator = streams.elements();
        while (iterator.hasNext()) {
            JsonNode stream = iterator.next();
            if (codecType.equalsIgnoreCase(stream.path("codec_type").asText())) {
                return stream;
            }
        }
        return objectMapper.nullNode();
    }

    private Long parseDurationMs(String durationText) {
        if (durationText == null || durationText.isBlank() || "N/A".equalsIgnoreCase(durationText)) {
            return null;
        }
        try {
            BigDecimal seconds = new BigDecimal(durationText);
            return seconds.multiply(BigDecimal.valueOf(1000L)).setScale(0, RoundingMode.HALF_UP).longValue();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long parseLong(String text) {
        if (text == null || text.isBlank() || "N/A".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer parseInteger(String text) {
        if (text == null || text.isBlank() || "N/A".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    public record VideoMetadata(
            Integer videoWidth,
            Integer videoHeight,
            String videoCodec,
            String audioCodec,
            Long durationMs,
            Long fileSizeBytes) {
        public static VideoMetadata empty() {
            return new VideoMetadata(null, null, null, null, null, null);
        }
    }
}
