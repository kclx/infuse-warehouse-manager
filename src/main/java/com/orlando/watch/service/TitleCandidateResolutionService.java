package com.orlando.watch.service;

import java.util.ArrayList;
import java.util.List;

import com.orlando.media.ai.MediaTitleCandidateAiService;
import com.orlando.media.model.MediaTitleCandidateDecision;
import com.orlando.repository.MediaAssetRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 标题候选判别服务：先查库候选，再由 AI 在候选中选择并归并为规范标题。
 */
@ApplicationScoped
@Slf4j
public class TitleCandidateResolutionService {

    @Inject
    TitleCandidateSearchService titleCandidateSearchService;

    @Inject
    MediaTitleCandidateAiService mediaTitleCandidateAiService;

    public String resolveCanonicalTitle(String rawFileName, String parsedName) {
        List<MediaAssetRepository.TitleCount> candidates = titleCandidateSearchService.searchCandidates(parsedName);
        if (candidates.isEmpty()) {
            return null;
        }

        List<String> candidateTitles = new ArrayList<>();
        for (MediaAssetRepository.TitleCount candidate : candidates) {
            candidateTitles.add(candidate.title());
        }

        MediaTitleCandidateDecision decision = decideFromCandidates(rawFileName, parsedName, candidateTitles);
        if (decision == null || !decision.isUsable()) {
            return null;
        }

        String selectedTitle = decision.selectedTitle().trim();
        if (!candidateTitles.contains(selectedTitle)) {
            log.warn("候选标题 AI 返回了候选列表外的标题，忽略并回退: {}", selectedTitle);
            return null;
        }

        String canonicalTitle = titleCandidateSearchService.chooseCanonicalTitle(selectedTitle, candidates);
        log.info("候选标题判别命中: selected={}, canonical={}", selectedTitle, canonicalTitle);
        return canonicalTitle;
    }

    private MediaTitleCandidateDecision decideFromCandidates(String rawFileName, String parsedName, List<String> candidateTitles) {
        try {
            String prompt = """
                    原始文件名: %s
                    初解析标题: %s
                    候选标题列表(必须从这里选): %s
                    """.formatted(rawFileName, parsedName, candidateTitles);
            return mediaTitleCandidateAiService.decide(prompt);
        } catch (Exception ex) {
            log.warn("候选标题 AI 判别失败，回退原流程: file={}", rawFileName, ex);
            return null;
        }
    }
}
