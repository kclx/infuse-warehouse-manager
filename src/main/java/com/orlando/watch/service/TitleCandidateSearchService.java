package com.orlando.watch.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orlando.repository.MediaAssetRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 从 media_asset 中按关键词召回标题候选，并提供规范标题归并能力。
 */
@ApplicationScoped
public class TitleCandidateSearchService {

    private static final int DEFAULT_LIMIT = 20;
    private static final Pattern HAN_TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final Pattern EN_TOKEN_PATTERN = Pattern.compile("[A-Za-z]+");

    @Inject
    MediaAssetRepository mediaAssetRepository;

    public List<MediaAssetRepository.TitleCount> searchCandidates(String parsedName) {
        List<String> keywords = extractKeywords(parsedName);
        if (keywords.isEmpty()) {
            return List.of();
        }
        return mediaAssetRepository.findTitleCandidatesByKeywords(keywords, DEFAULT_LIMIT);
    }

    public String chooseCanonicalTitle(String selectedTitle, List<MediaAssetRepository.TitleCount> candidates) {
        if (selectedTitle == null || selectedTitle.isBlank() || candidates == null || candidates.isEmpty()) {
            return selectedTitle;
        }

        String selectedKey = normalizeForCompare(selectedTitle);
        MediaAssetRepository.TitleCount best = null;
        for (MediaAssetRepository.TitleCount candidate : candidates) {
            String candidateKey = normalizeForCompare(candidate.title());
            if (!isRelated(selectedKey, candidateKey)) {
                continue;
            }
            if (best == null || candidate.count() > best.count()) {
                best = candidate;
            }
        }
        return best == null ? selectedTitle : best.title();
    }

    public List<String> extractKeywords(String parsedName) {
        if (parsedName == null || parsedName.isBlank()) {
            return List.of();
        }

        Set<String> keywords = new LinkedHashSet<>();
        Matcher hanMatcher = HAN_TOKEN_PATTERN.matcher(parsedName);
        while (hanMatcher.find()) {
            keywords.add(hanMatcher.group());
        }

        Matcher enMatcher = EN_TOKEN_PATTERN.matcher(parsedName);
        while (enMatcher.find()) {
            String token = enMatcher.group().toLowerCase(Locale.ROOT);
            if (token.length() >= 2) {
                keywords.add(token);
            }
        }

        return new ArrayList<>(keywords);
    }

    private boolean isRelated(String selectedKey, String candidateKey) {
        if (selectedKey.isBlank() || candidateKey.isBlank()) {
            return false;
        }
        if (selectedKey.equals(candidateKey)) {
            return true;
        }
        return selectedKey.contains(candidateKey) || candidateKey.contains(selectedKey);
    }

    private String normalizeForCompare(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }
}
