package com.dolphin.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力注册中心。
 *
 * <p>Spring 注入全部 {@link CapabilityHandler} 实现,启动时按描述符 code 构建映射;
 * 未注册/未知 code 一律回落兜底能力(chat)。模式参考生产级实现:
 * 策略自声明类型 + 工厂建映射 + 兜底策略。
 */
@Component
public class CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistry.class);

    /** 兜底能力编码:闲聊/通用对话 */
    public static final String FALLBACK_CODE = "chat";

    private final Map<String, CapabilityHandler> handlers = new HashMap<>();
    private final CapabilityHandler fallback;

    public CapabilityRegistry(List<CapabilityHandler> allHandlers) {
        for (CapabilityHandler handler : allHandlers) {
            String code = handler.descriptor().code();
            CapabilityHandler existing = handlers.put(code, handler);
            if (existing != null) {
                log.warn("能力编码重复注册: {},{} 被 {} 覆盖",
                        code, existing.getClass().getSimpleName(), handler.getClass().getSimpleName());
            }
            log.info("注册能力: {} -> {}", code, handler.getClass().getSimpleName());
        }
        fallback = handlers.get(FALLBACK_CODE);
        if (fallback == null) {
            throw new IllegalStateException("必须存在 code=" + FALLBACK_CODE + " 的兜底 CapabilityHandler");
        }
    }

    /** 按能力编码取处理器,未知 code 回落兜底 */
    public CapabilityHandler get(String code) {
        return handlers.getOrDefault(code, fallback);
    }

    /** 全部描述符,供 LLM 意图分类动态生成 prompt */
    public List<CapabilityDescriptor> descriptors() {
        return handlers.values().stream().map(CapabilityHandler::descriptor).toList();
    }

    public boolean isKnown(String code) {
        return handlers.containsKey(code);
    }
}
