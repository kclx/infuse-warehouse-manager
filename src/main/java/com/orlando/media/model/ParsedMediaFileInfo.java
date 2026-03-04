package com.orlando.media.model;

/**
 * AI 从原始剧集文件名提取出的结构化结果。
 */
public record ParsedMediaFileInfo(String name, Integer season, Integer episode, Float confidence, String reason) {

    public boolean isUsable() {
        return name != null
                && !name.isBlank()
                && season != null
                && season > 0
                && episode != null
                && episode > 0;
    }
}
