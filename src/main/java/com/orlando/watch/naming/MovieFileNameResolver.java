package com.orlando.watch.naming;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orlando.media.ai.MovieEditionTagAiService;
import com.orlando.media.model.MovieEditionDecision;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 电影模式下的 AI 标签判别与防覆盖命名解析。
 */
@ApplicationScoped
@Slf4j
public class MovieFileNameResolver {

    private static final String OTHER_EDITION = "其他";
    private static final String MAIN_FEATURE_EDITION = "正片";
    private static final Pattern EDITION_BRACE_PATTERN = Pattern.compile("\\{edition-([^{}]+)}", Pattern.CASE_INSENSITIVE);

    @Inject
    MovieEditionTagAiService movieEditionTagAiService;

    public MovieNamingResult resolveMovieNaming(
            String rawFileName,
            String titleHint,
            String extension,
            Path targetRootDirectory,
            Set<String> reservedFileNames) {
        MovieEditionDecision decision = decideByAi(rawFileName, titleHint);
        String finalTitle = normalizeTitle(decision == null ? null : decision.title(), titleHint);
        String finalEditionTag = resolveFinalEditionTag(rawFileName, decision == null ? null : decision.editionTag());

        Path targetDirectory = targetRootDirectory.resolve(finalTitle);
        String finalFileName = resolveUniqueFileName(targetDirectory, finalTitle, finalEditionTag, extension, reservedFileNames);
        return new MovieNamingResult(finalTitle, finalEditionTag, targetDirectory, finalFileName);
    }

    public String extractEditionTag(String sourceFileName) {
        String detectedEdition = detectEditionFromSourceFileName(sourceFileName);
        if (detectedEdition != null) {
            return detectedEdition;
        }

        MovieEditionDecision decision = decideByAi(sourceFileName, null);
        return resolveFinalEditionTag(sourceFileName, decision == null ? null : decision.editionTag());
    }

    public String buildMovieBaseName(String normalizedTitle, String editionTag) {
        String title = safeTitle(normalizedTitle);
        if (editionTag == null || editionTag.isBlank()) {
            return title;
        }
        String normalizedEditionTag = normalizeEditionTag(editionTag);
        if (MAIN_FEATURE_EDITION.equals(normalizedEditionTag)) {
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

    private MovieEditionDecision decideByAi(String rawFileName, String titleHint) {
        try {
            String prompt = """
                    rawFileName: %s
                    titleHint: %s
                    """.formatted(rawFileName, titleHint == null ? "" : titleHint);
            return movieEditionTagAiService.decide(prompt);
        } catch (Exception ex) {
            log.warn("电影 edition AI 判别失败，降级使用默认标签: file={}", rawFileName, ex);
            return null;
        }
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

    private String resolveFinalEditionTag(String rawFileName, String aiEditionTag) {
        String detectedEdition = detectEditionFromSourceFileName(rawFileName);
        if (detectedEdition != null) {
            return detectedEdition;
        }

        String normalizedAiEdition = normalizeEditionTag(aiEditionTag);
        if (!OTHER_EDITION.equals(normalizedAiEdition)) {
            return normalizedAiEdition;
        }

        // 未命中显式标签时，默认按正片处理，避免生成 {edition-其他}
        return MAIN_FEATURE_EDITION;
    }

    private String detectEditionFromSourceFileName(String rawFileName) {
        if (rawFileName == null || rawFileName.isBlank()) {
            return null;
        }

        Matcher matcher = EDITION_BRACE_PATTERN.matcher(rawFileName);
        if (matcher.find()) {
            return normalizeEditionTag(matcher.group(1));
        }

        String value = rawFileName.trim().toLowerCase(Locale.ROOT);
        if (containsAny(value, "预告", "trailer", "teaser", "pv")) {
            return "预告片";
        }
        if (containsAny(value, "花絮", "making", "behind")) {
            return "花絮";
        }
        if (containsAny(value, "特典", "bonus", "extra")) {
            return "特典";
        }
        if (containsAny(value, "正片", "feature", "main")) {
            return MAIN_FEATURE_EDITION;
        }
        if (containsAny(value, "其他", "other")) {
            return OTHER_EDITION;
        }
        return null;
    }

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeEditionTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return OTHER_EDITION;
        }

        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.contains("预告") || value.contains("trailer") || value.contains("teaser") || value.equals("pv")) {
            return "预告片";
        }
        if (value.contains("花絮") || value.contains("making") || value.contains("behind")) {
            return "花絮";
        }
        if (value.contains("特典") || value.contains("bonus") || value.contains("extra")) {
            return "特典";
        }
        if (value.contains("正片") || value.contains("feature") || value.contains("main")) {
            return MAIN_FEATURE_EDITION;
        }
        if (value.contains("其他") || value.contains("other")) {
            return OTHER_EDITION;
        }

        return OTHER_EDITION;
    }

    private String normalizeTitle(String aiTitle, String titleHint) {
        String selected = (aiTitle == null || aiTitle.isBlank()) ? titleHint : aiTitle;
        return safeTitle(selected)
                .replaceAll("[\\\\/:*?\"<>|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
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

    public record MovieNamingResult(String title, String editionTag, Path targetDirectory, String fileName) {
    }
}
