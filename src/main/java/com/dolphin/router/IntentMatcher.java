package com.dolphin.router;

import java.util.Optional;

/**
 * 意图匹配器(责任链接口)。
 *
 * <p>实现类通过 {@code @Order} 决定执行顺序:会话粘性 → 精确 → 正则 → 关键词 → LLM 分类。
 * 命中返回决策,未命中返回 empty 交给下一环,最后一环未命中由 IntentRouter 兜底。
 */
public interface IntentMatcher {

    Optional<RouteDecision> match(String message, DialogContext ctx);
}
