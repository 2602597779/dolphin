package com.dolphin.ticket.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 回复撰写 Agent:把诊断报告/知识答案润色成面向客户的最终回复草稿。
 */
@Component
public class ReplyAgent {

    private final ChatClient chatClient;

    public ReplyAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(PromptLoader.load("reply.st"))
                .build();
    }

    /**
     * 生成回复草稿。
     *
     * @param rawMessage     用户原始工单文本
     * @param classification 分诊结果
     * @param content        诊断报告或知识答案等处理内容
     * @return 回复草稿文本
     */
    public String compose(String rawMessage, Map<String, Object> classification, String content) {
        String user = "客户工单原文:" + rawMessage
                + "\n分诊结果:" + classification
                + "\n内部处理内容:\n" + content;
        // TODO: 敏感信息脱敏(实例 ID/金额等按策略打码)
        return chatClient.prompt().user(user).call().content();
    }
}
