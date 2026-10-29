package com.dolphin.router;

import com.dolphin.capability.CapabilityDescriptor;
import com.dolphin.capability.CapabilityHandler;

import java.util.List;

/**
 * 测试夹具:构造无副作用的 CapabilityHandler,以及路由测试常用的三个描述符。
 */
public final class TestHandlers {

    private TestHandlers() {
    }

    /** 返回固定文本的哑处理器 */
    public record Dummy(CapabilityDescriptor descriptor) implements CapabilityHandler {
        @Override
        public String handle(String message, DialogContext ctx) {
            return "dummy:" + descriptor().code();
        }
    }

    public static Dummy chat() {
        return new Dummy(new CapabilityDescriptor("chat", "通用对话", "兜底",
                List.of(), List.of(), List.of(), List.of("你好")));
    }

    public static Dummy ragQa() {
        return new Dummy(new CapabilityDescriptor("rag-qa", "知识问答", "知识类问题",
                List.of("ECS 怎么重置密码"), List.of("怎么", "如何", "流程"),
                List.of(), List.of()));
    }

    public static Dummy ticketFlow() {
        return new Dummy(new CapabilityDescriptor("ticket-flow", "故障工单", "报障",
                List.of("帮我提个工单"), List.of("工单", "故障"),
                List.of("帮我?(提|发|创建)一?个?工单"), List.of()));
    }
}
