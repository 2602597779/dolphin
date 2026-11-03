package com.dolphin.router;

import com.dolphin.capability.CapabilityRegistry;
import com.dolphin.cloud.CloudApiProperties;
import com.dolphin.router.matcher.ExactMatcher;
import com.dolphin.router.matcher.KeywordMatcher;
import com.dolphin.router.matcher.RegexMatcher;
import com.dolphin.router.matcher.StickySessionMatcher;
import com.dolphin.ticket.infra.audit.AuditLogRepository;
import com.dolphin.ticket.infra.audit.AuditLogger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 路由器:责任链顺序(首个命中即停)与全链未命中兜底。
 * 不含 LLM 分类环(需模型服务,属集成测试范畴)。
 */
class IntentRouterTest {

    private final CapabilityRegistry registry = new CapabilityRegistry(
            List.of(TestHandlers.chat(), TestHandlers.ragQa(), TestHandlers.ticketFlow()));

    /** 与生产 @Order 顺序一致:粘性 → 精确 → 正则 → 关键词;审计 repo 为 mock(路由打点无需真实落库) */
    private final IntentRouter router = new IntentRouter(List.of(
            new StickySessionMatcher(),
            new ExactMatcher(registry),
            new RegexMatcher(registry),
            new KeywordMatcher(registry)),
            new AuditLogger(new CloudApiProperties(), mock(AuditLogRepository.class)));

    @Test
    void 精确短语优先于正则与关键词() {
        // "你好" 是 chat 的精确短语;即便消息同时含其他能力关键词也应精确命中
        RouteDecision d = router.route("你好", new DialogContext("r1"));
        assertThat(d.matchedBy()).isEqualTo("EXACT");
        assertThat(d.capabilityCode()).isEqualTo("chat");
    }

    @Test
    void 正则命中句式路由到工单() {
        RouteDecision d = router.route("帮我提个工单吧", new DialogContext("r2"));
        assertThat(d.matchedBy()).isEqualTo("REGEX");
        assertThat(d.capabilityCode()).isEqualTo("ticket-flow");
    }

    @Test
    void 粘性优先于一切规则匹配() {
        DialogContext ctx = new DialogContext("r3");
        ctx.setActiveCapability("ticket-flow");
        // 消息本身是 rag-qa 的关键词句式,但粘性应短路回工单
        RouteDecision d = router.route("这个流程怎么走", ctx);
        assertThat(d.matchedBy()).isEqualTo("STICKY");
        assertThat(d.capabilityCode()).isEqualTo("ticket-flow");
    }

    @Test
    void 全链未命中回落兜底chat() {
        RouteDecision d = router.route("量子力学和海豚有什么共同点", new DialogContext("r4"));
        assertThat(d.matchedBy()).isEqualTo("FALLBACK");
        assertThat(d.capabilityCode()).isEqualTo("chat");
        assertThat(d.confidence()).isZero();
    }
}
