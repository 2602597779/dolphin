package com.dolphin.router;

import com.dolphin.capability.CapabilityRegistry;
import com.dolphin.ticket.infra.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 意图路由器:按 @Order 依次执行匹配器责任链,首个命中即决策。
 *
 * <p>链路:会话粘性 → 精确 → 正则 → 关键词 → LLM 语义分类 → 兜底(chat)。
 * 规则层在前保证快与省(LLM 只在必要时调用),LLM 层保证泛化能力。
 * 每次路由决策经 AuditLogger 留痕,支撑"这句话为什么去了那个分支"的追溯。
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private final List<IntentMatcher> matcherChain;
    private final AuditLogger auditLogger;

    /** Spring 按 @Order 注入有序链 */
    public IntentRouter(List<IntentMatcher> matcherChain, AuditLogger auditLogger) {
        this.matcherChain = matcherChain;
        this.auditLogger = auditLogger;
    }

    public RouteDecision route(String message, DialogContext ctx) {
        for (IntentMatcher matcher : matcherChain) {
            Optional<RouteDecision> decision = matcher.match(message, ctx);
            if (decision.isPresent()) {
                log.debug("[router] {} 命中: {}", matcher.getClass().getSimpleName(), decision.get());
                audit(ctx, decision.get());
                return decision.get();
            }
        }
        RouteDecision fallback = new RouteDecision(CapabilityRegistry.FALLBACK_CODE, "FALLBACK", 0.0);
        log.debug("[router] 全链未命中,兜底: {}", fallback);
        audit(ctx, fallback);
        return fallback;
    }

    private void audit(DialogContext ctx, RouteDecision decision) {
        auditLogger.log(ctx.getSessionId(), "intent-route",
                "%s by %s (confidence=%.2f)".formatted(decision.capabilityCode(), decision.matchedBy(), decision.confidence()));
    }
}
