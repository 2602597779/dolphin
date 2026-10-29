package com.dolphin.capability.handler;

import com.dolphin.agent.DolphinSupervisor;
import com.dolphin.capability.CapabilityDescriptor;
import com.dolphin.capability.CapabilityHandler;
import com.dolphin.router.DialogContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 兜底能力:通用对话/原子任务(翻译、摘要),委托现有 DolphinSupervisor。
 */
@Component
public class ChatFallbackHandler implements CapabilityHandler {

    private final DolphinSupervisor supervisor;

    public ChatFallbackHandler(DolphinSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                "chat", "通用对话",
                "闲聊、翻译、文本摘要等通用请求,不属于知识问答也不属于故障工单时使用",
                List.of("帮我把这段话翻译成英文", "总结一下这篇文章", "你好,你能做什么"),
                List.of("翻译", "总结", "摘要"),
                List.of(),
                List.of("你好", "hi", "hello"));
    }

    @Override
    public String handle(String message, DialogContext ctx) {
        try {
            return supervisor.chat(message);
        } catch (Exception e) {
            return "通用对话服务暂时不可用(模型调用失败):" + e.getMessage();
        }
    }
}
