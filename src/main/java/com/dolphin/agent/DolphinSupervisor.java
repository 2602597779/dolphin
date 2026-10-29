package com.dolphin.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Supervisor:小海豚。
 * 负责理解用户意图,决定调用哪个子Agent(translate / summarize),并汇总结果。
 */
@Service
public class DolphinSupervisor {

    private final ChatClient chat;

    public DolphinSupervisor(ChatClient.Builder builder,
                             TranslatorAgent translator,
                             SummarizerAgent summarizer) {
        this.chat = builder
                .defaultSystem("""
                        你是"小海豚",一个多智能体系统的协调者(Supervisor)。
                        你手下有两个专员Agent可供工具调用:
                        - translate:专业翻译
                        - summarize:文本摘要
                        请根据用户请求选择合适的工具完成任务,最终用中文向用户汇报结果。
                        不需要工具就能回答的问题,直接回答即可。""")
                .defaultTools(translator, summarizer)
                .build();
    }

    public String chat(String message) {
        return chat.prompt().user(message).call().content();
    }
}
