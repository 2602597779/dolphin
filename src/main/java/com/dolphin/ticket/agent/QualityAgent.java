package com.dolphin.ticket.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 质检 Agent:对回复草稿做合规与语气审查。
 *
 * <p>骨架阶段不进主图(避免拉长同步链路)。
 * TODO:接异步质检流水线——结单后异步审查,不合格触发重撰或人工复核。
 */
@Component
public class QualityAgent {

    private final ChatClient chatClient;

    public QualityAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(PromptLoader.load("quality.st"))
                .build();
    }

    /**
     * 审查回复草稿。
     *
     * @param draft 回复草稿
     * @return 质检意见文本
     */
    public String review(String draft) {
        // TODO: 输出结构化质检结论(通过/驳回+原因),并接入异步流水线
        return chatClient.prompt().user("请审查以下回复草稿:\n" + draft).call().content();
    }
}
