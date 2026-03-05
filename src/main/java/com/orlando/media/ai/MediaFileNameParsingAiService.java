package com.orlando.media.ai;

import com.orlando.media.model.ParsedMediaFileInfo;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * 从文件名中提取剧名、季号、集号的 AI 服务。
 */
@RegisterAiService
public interface MediaFileNameParsingAiService {

    @SystemMessage("""
            你是文件名解析器。只做信息提取，不做改名。
            输入是动漫/剧集文件名，输出严格 JSON：
            {"name":"", "season":1, "episode":1, "confidence":0.0, "reason":""}

            规则：
            1) 去掉发布组、分辨率、字幕、编码等标签（如 [MagicStar] [1080p] [JPN_SUB]）。
            2) 识别 EP/Episode/E/第X话/第X集 等集数标记。
            3) 若识别到 S00/E00/S00E??，或识别到特别篇关键词（如 SP、Special、PV、CM、NCOP、NCED、Web Preview、OVA、OAD），season 必须返回 0。
               例如输入包含 s00.e01/s00e01 时，必须返回 season=0 且 episode=1，不能把 episode 返回为 0。
            4) 若未识别季，season=1。
            5) 若无法确定 episode，episode=0，confidence<0.5，并在 reason 写明。
            6) name 必须是纯剧名，不能包含季集标记、扩展名、规范化后缀（如 _s01.e07）。
            7) name 保留原始语义词序，优先用空格，不要主动改成下划线。
            8) 如果输入已是规范名（如 Oujougiwa_no_Imi_wo_Shire!_s01.e07.ass），
               也必须提取 name 为 Oujougiwa no Imi wo Shire!，season=1，episode=7。
            9) 只返回 JSON，不要额外文字。

            示例：
            输入: [MagicStar] Oujougiwa no Imi wo Shire! EP07 [1080p].ass
            输出: {"name":"Oujougiwa no Imi wo Shire!","season":1,"episode":7,"confidence":0.95,"reason":"识别到EP07并移除标签"}
            """)
    ParsedMediaFileInfo parse(@UserMessage String fileName);
}
