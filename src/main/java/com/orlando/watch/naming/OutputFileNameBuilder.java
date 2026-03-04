package com.orlando.watch.naming;

import java.util.Locale;

import com.orlando.watch.config.WatchProcessingConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 根据配置模板渲染输出文件名。
 */
@ApplicationScoped
public class OutputFileNameBuilder {

    @Inject
    WatchProcessingConfig watchProcessingConfig;

    public String build(String normalizedTitle, int season, int episode, String extension) {
        String seasonNumber = String.format(Locale.ROOT, "%0" + normalizeWidth(watchProcessingConfig.filename().seasonNumberWidth()) + "d", season);
        String episodeNumber = String.format(Locale.ROOT, "%0" + normalizeWidth(watchProcessingConfig.filename().episodeNumberWidth()) + "d", episode);
        String safeExtension = extension == null ? "" : extension.trim();

        String rendered = watchProcessingConfig.filename().pattern()
                .replace("{name}", normalizedTitle.trim())
                .replace("{snumber}", seasonNumber)
                .replace("{enumber}", episodeNumber)
                .replace("{ext}", safeExtension);

        if (safeExtension.isBlank()) {
            return rendered
                    .replaceAll("\\.$", "")
                    .replaceAll("_$", "")
                    .replaceAll("-$", "");
        }
        return rendered;
    }

    private int normalizeWidth(int width) {
        return width < 1 ? 2 : width;
    }
}
