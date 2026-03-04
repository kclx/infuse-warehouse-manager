package com.orlando.watch.naming;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * 将 AI 返回标题标准化为安全且可读的目录/文件标题片段。
 */
@ApplicationScoped
public class MediaTitleNormalizer {

    public String normalize(String rawTitle) {
        String cleaned = rawTitle
                .replace('_', ' ')
                .replaceAll("(?i)[._-]?s\\d{1,2}[._-]?e\\d{1,3}$", "")
                .replaceAll("[\\\\/:*?\"<>|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? "Unknown" : cleaned;
    }
}
