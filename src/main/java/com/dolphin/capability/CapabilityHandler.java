package com.dolphin.capability;

import com.dolphin.router.DialogContext;

/**
 * 能力处理器(策略接口)。
 *
 * <p>设计对齐 smalldolphin 的 AgentInvokeStrategy 模式:处理器自声明描述符,
 * 注册中心启动时收集所有实现构建 code → handler 映射;
 * <b>新增能力 = 新增一个实现类,路由代码零改动</b>。
 */
public interface CapabilityHandler {

    /** 能力描述符(含路由规则与 LLM 分类素材) */
    CapabilityDescriptor descriptor();

    /**
     * 处理消息。
     *
     * @param message 用户原始消息
     * @param ctx     会话上下文(可读写,如流程结束时清除粘性标记)
     * @return 面向用户的回复文本
     */
    String handle(String message, DialogContext ctx);
}
