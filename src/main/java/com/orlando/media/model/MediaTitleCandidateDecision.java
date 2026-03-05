package com.orlando.media.model;

/**
 * 候选标题判别结果。
 */
public record MediaTitleCandidateDecision(String selectedTitle, Float confidence, String reason) {

    public boolean isUsable() {
        return selectedTitle != null && !selectedTitle.isBlank();
    }
}
