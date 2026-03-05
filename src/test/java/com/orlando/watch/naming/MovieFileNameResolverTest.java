package com.orlando.watch.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

class MovieFileNameResolverTest {

    private final MovieFileNameResolver resolver = new MovieFileNameResolver();

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
}
