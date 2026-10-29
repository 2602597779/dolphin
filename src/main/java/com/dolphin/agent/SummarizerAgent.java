package com.dolphin.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * 子Agent:摘要专员。
 */
@Service
public class SummarizerAgent {

    private final ChatClient client;

    public SummarizerAgent(ChatClient.Builder builder) {
        this.client = builder
                .defaultSystem("你是文本摘要助手。用简洁的中文提炼用户给定文本的要点,输出不超过三句话。")
                .build();
    }

    @Tool(name = "summarize", description = "对长文本进行要点提炼的摘要Agent")
    public String summarize(
            @ToolParam(description = "需要摘要的长文本") String text) {
        return client.prompt()
                .user(text)
                .call()
                .content();
    }
}
