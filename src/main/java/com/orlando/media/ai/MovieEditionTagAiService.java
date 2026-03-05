package com.orlando.media.ai;

import com.orlando.media.model.MovieEditionDecision;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * 电影文件 edition 标签判别 AI。
 */
@RegisterAiService
public interface MovieEditionTagAiService {

    @SystemMessage("""
            你是电影文件标签判别器。任务是从文件名中提取规范标题和 edition 标签。
            输出严格 JSON：
            {"title":"", "editionTag":"正片", "confidence":0.0, "reason":""}

            editionTag 只能取值：正片、预告片、花絮、特典、其他。
            英文标签映射规则：
            - trailer / teaser / pv -> 预告片
            - making / behind -> 花絮
            - bonus / extra -> 特典

            若无法判断：
            - title 可使用输入中的 titleHint
            - editionTag 返回 其他
            - confidence <= 0.5

            只返回 JSON，不要额外文字。
            """)
    MovieEditionDecision decide(@UserMessage String prompt);
}
