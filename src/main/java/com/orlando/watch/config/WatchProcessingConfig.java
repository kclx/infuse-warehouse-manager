package com.orlando.watch.config;

import java.util.Set;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * 文件监听处理相关的集中配置，避免在代码中散落字符串配置键。
 */
@ConfigMapping(prefix = "watch")
public interface WatchProcessingConfig {

    @WithDefault("/Users/orlando/Documents/Program/warehouse-manager/data")
    String path();

    @WithName("target-path")
    @WithDefault("/Users/orlando/Documents/Program/warehouse-manager/data/data-target")
    String targetPath();

    @WithName("allow-file-names")
    @WithDefault("re:.*\\\\.(mp4|mkv|ass|ssa|srt|vtt)$")
    Set<String> allowFileNames();

    Filename filename();

    @WithName("db")
    Database database();

    interface Filename {

        @WithDefault("{name}_s{snumber}.e{enumber}.{ext}")
        String pattern();

        @WithName("season-number-width")
        @WithDefault("2")
        int seasonNumberWidth();

        @WithName("episode-number-width")
        @WithDefault("2")
        int episodeNumberWidth();
    }

    interface Database {

        @WithName("asset-file-extensions")
        @WithDefault("mp4,mkv")
        Set<String> assetFileExtensions();

        @WithName("subtitle-file-extensions")
        @WithDefault("ass,srt,ssa,vtt")
        Set<String> subtitleFileExtensions();
    }
}
