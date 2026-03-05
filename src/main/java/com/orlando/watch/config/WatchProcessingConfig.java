package com.orlando.watch.config;

import java.util.Map;
import java.util.Set;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * 文件监听处理相关的集中配置，避免在代码中散落字符串配置键。
 */
@ConfigMapping(prefix = "watch")
public interface WatchProcessingConfig {

    @WithName("group")
    Map<String, Group> groups();

    @WithName("allow-file-names")
    @WithDefault("re:.*\\\\.(mp4|mkv|ass|ssa|srt|vtt)$")
    Set<String> allowFileNames();

    Filename filename();

    @WithName("db")
    Database database();

    interface Filename {

        String pattern();

        @WithName("season-number-width")
        int seasonNumberWidth();

        @WithName("episode-number-width")
        int episodeNumberWidth();
    }

    interface Database {

        @WithName("asset-file-extensions")
        Set<String> assetFileExtensions();

        @WithName("subtitle-file-extensions")
        Set<String> subtitleFileExtensions();
    }

    interface Group {

        String path();

        @WithName("target-path")
        String targetPath();

        @WithName("single-episode-only")
        @WithDefault("false")
        boolean singleEpisodeOnly();
    }
}
