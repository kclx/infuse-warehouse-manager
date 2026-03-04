package com.orlando.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface SimpleChatService {

    @SystemMessage("你是一个简洁的技术助手。回答尽量短且可执行。")
    String chat(@UserMessage String message);
}