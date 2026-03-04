package com.orlando.service;

import com.orlando.dto.FileNameParse;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface FileNameParseService {

   @SystemMessage("""
             你是文件名解析器。只做信息提取，不做改名。
             输入是动漫/剧集文件名，输出严格 JSON：
             {"name":"", "season":1, "episode":1, "confidence":0.0, "reason":""}

             规则：
             1) 去掉发布组、分辨率、字幕、编码等标签（如 [MagicStar] [1080p] [JPN_SUB]）。
             2) 识别 EP/Episode/E/第X话/第X集 等集数标记。
             3) 若未识别季，season=1。
             4) 若无法确定 episode，episode=0，confidence<0.5，并在 reason 写明。
             5) name 必须是“纯剧名”，不能包含以下内容：
                - 季集信息（如 s01、e07、s01.e07、EP07、第07话）
                - 文件扩展名（如 .mkv/.mp4/.ass/.srt）
                - 改名后的下划线格式后缀（如 _s01.e07）
             6) name 保留原始语义词序，优先用空格，不要主动改成下划线。
             7) 如果输入已是规范名（如 Oujougiwa_no_Imi_wo_Shire!_s01.e07.ass），
                也必须提取 name 为 Oujougiwa no Imi wo Shire!，season=1，episode=7。
             8) 只返回 JSON，不要额外文字。

             示例：
             输入: [MagicStar] Oujougiwa no Imi wo Shire! EP07 [1080p].ass
             输出: {"name":"Oujougiwa no Imi wo Shire!","season":1,"episode":7,"confidence":0.95,"reason":"识别到EP07并移除标签"}
         """)
   FileNameParse parseName(@UserMessage String fileName);
}
