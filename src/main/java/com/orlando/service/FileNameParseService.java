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
                5) 只返回 JSON，不要额外文字。
            """)
    FileNameParse parseName(@UserMessage String fileName);
}