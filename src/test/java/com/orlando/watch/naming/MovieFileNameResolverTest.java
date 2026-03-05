package com.orlando.watch.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.orlando.media.model.MovieEditionDecision;

import org.junit.jupiter.api.Test;

class MovieFileNameResolverTest {

    private final MovieFileNameResolver resolver = new MovieFileNameResolver();

    MovieFileNameResolverTest() {
        resolver.movieEditionTagAiService = prompt -> {
            if (prompt.toLowerCase().contains("trailer") || prompt.contains("预告")) {
                return new MovieEditionDecision("超时空辉夜姬", "预告片", 0.95f, "命中预告关键词");
            }
            return new MovieEditionDecision("超时空辉夜姬", "正片", 0.95f, "默认正片");
        };
    }

    @Test
    void shouldExtractEditionFromBraceTag() {
        String edition = resolver.extractEditionTag("超时空辉夜姬 (2026) {edition-预告片}.mkv");
        assertEquals("预告片", edition);
    }

    @Test
    void shouldFallbackToKeywordEdition() {
        String edition = resolver.extractEditionTag("超时空辉夜姬_trailer.mkv");
        assertEquals("预告片", edition);
    }

    @Test
    void shouldResolveUniqueNameWhenConflictExists() throws Exception {
        Path tempDir = Files.createTempDirectory("movie-name-test");
        Files.createFile(tempDir.resolve("超时空辉夜姬{edition-预告片}.mkv"));

        String fileName = resolver.resolveUniqueFileName(
                tempDir,
                "超时空辉夜姬",
                "预告片",
                "mkv",
                new HashSet<>());

        assertEquals("超时空辉夜姬{edition-预告片}_2.mkv", fileName);
    }

    @Test
    void shouldUseTitleOnlyForMainFeature() {
        String base = resolver.buildMovieBaseName("超时空辉夜姬", "正片");
        assertTrue(base.equals("超时空辉夜姬"));
    }

    @Test
    void shouldDefaultToMainFeatureWhenAiReturnsOtherWithoutExplicitTag() throws Exception {
        resolver.movieEditionTagAiService = prompt -> new MovieEditionDecision("目中无人2", "其他", 0.5f, "无法识别附加标签");
        Path tempDir = Files.createTempDirectory("movie-name-default-main-test");

        MovieFileNameResolver.MovieNamingResult result = resolver.resolveMovieNaming(
                "目中无人2.m4v",
                "目中无人2",
                "m4v",
                tempDir,
                Set.of());

        assertEquals("正片", result.editionTag());
        assertEquals("目中无人2.m4v", result.fileName());
    }

    @Test
    void shouldKeepOtherWhenBraceTagExplicitlyProvided() {
        String edition = resolver.extractEditionTag("目中无人2{edition-其他}.m4v");
        assertEquals("其他", edition);
    }
}
