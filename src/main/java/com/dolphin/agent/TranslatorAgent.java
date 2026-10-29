package com.dolphin.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * 子Agent:翻译专员。
 * 自身持有一个独立的 ChatClient(独立上下文),被 Supervisor 当作工具调用。
 */
@Service
public class TranslatorAgent {

    private final ChatClient client;

    public TranslatorAgent(ChatClient.Builder builder) {
        this.client = builder
                .defaultSystem("你是专业翻译助手。把用户提供的文本翻译成指定语言,只输出译文,不要解释。")
                .build();
    }

    @Tool(name = "translate", description = "将文本翻译成指定语言的专业翻译Agent")
    public String translate(
            @ToolParam(description = "需要翻译的原文") String text,
            @ToolParam(description = "目标语言,例如:英文、日文、法文") String language) {
        return client.prompt()
                .user("原文:" + text + System.lineSeparator() + "目标语言:" + language)
                .call()
                .content();
    }
}
