package com.orlando.watch.naming;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * 电影模式下的附加标签提取与防覆盖命名解析。
 */
@ApplicationScoped
public class MovieFileNameResolver {

    private static final Pattern EDITION_BRACE_PATTERN = Pattern.compile("\\{\\s*edition-([^}]+)\\}", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_EDITION = "正片";

    public String extractEditionTag(String sourceFileName) {
        if (sourceFileName == null || sourceFileName.isBlank()) {
            return DEFAULT_EDITION;
        }

        Matcher matcher = EDITION_BRACE_PATTERN.matcher(sourceFileName);
        if (matcher.find()) {
            return normalizeEditionTag(matcher.group(1));
        }

        String lower = sourceFileName.toLowerCase(Locale.ROOT);
        if (lower.contains("预告") || lower.contains("trailer") || lower.contains("pv")) {
            return "预告片";
        }
        if (lower.contains("花絮") || lower.contains("making") || lower.contains("behind")) {
            return "花絮";
        }
        if (lower.contains("特典") || lower.contains("bonus") || lower.contains("extra")) {
            return "特典";
        }
        if (lower.contains("正片") || lower.contains("feature") || lower.contains("main")) {
            return DEFAULT_EDITION;
        }

        return DEFAULT_EDITION;
    }

    public String buildMovieBaseName(String normalizedTitle, String editionTag) {
        String title = safeTitle(normalizedTitle);
        String normalizedEditionTag = normalizeEditionTag(editionTag);
        if (DEFAULT_EDITION.equals(normalizedEditionTag)) {
            return title;
        }
        return title + "{edition-" + normalizedEditionTag + "}";
    }

    public String resolveUniqueFileName(
            Path targetDirectory,
            String normalizedTitle,
            String editionTag,
            String extension,
            Set<String> reservedFileNames) {
        String baseName = buildMovieBaseName(normalizedTitle, editionTag);
        String ext = normalizeExtension(extension);
        return resolveUniqueName(targetDirectory, baseName, ext, reservedFileNames);
    }

    private String resolveUniqueName(Path directory, String baseName, String extension, Set<String> reserved) {
        String suffix = extension.isBlank() ? "" : "." + extension;
        String candidate = baseName + suffix;
        int sequence = 2;
        while (isOccupied(directory, candidate, reserved)) {
            candidate = baseName + "_" + sequence + suffix;
            sequence++;
        }
        return candidate;
    }

    private boolean isOccupied(Path directory, String candidate, Set<String> reserved) {
        if (reserved != null && reserved.contains(candidate)) {
            return true;
        }
        return Files.exists(directory.resolve(candidate));
    }

    private String normalizeEditionTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_EDITION;
        }
        String sanitized = raw.trim().replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ");
        return sanitized.isBlank() ? DEFAULT_EDITION : sanitized;
    }

    private String safeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "unknown";
        }
        return title.trim();
    }

    private String normalizeExtension(String ext) {
        if (ext == null || ext.isBlank()) {
            return "";
        }
        return ext.trim().toLowerCase(Locale.ROOT);
    }
}
