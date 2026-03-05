package com.orlando.media.ai;

import com.orlando.media.model.MediaTitleCandidateDecision;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * 在候选标题列表中做选择的 AI 服务。
 */
@RegisterAiService
public interface MediaTitleCandidateAiService {

    @SystemMessage("""
            你是标题候选选择器。只能从候选列表里选一个标题。
            输入包含：原始文件名、初解析标题、候选标题列表（按置信排序）。
            输出严格 JSON：
            {"selectedTitle":"", "confidence":0.0, "reason":""}

            规则：
            1) selectedTitle 必须是候选列表中的某一个完整值。
            2) 若无法判断，selectedTitle 置空字符串，confidence<=0.5，并写 reason。
            3) 只返回 JSON，不要额外文本。
            """)
    MediaTitleCandidateDecision decide(@UserMessage String prompt);
}
