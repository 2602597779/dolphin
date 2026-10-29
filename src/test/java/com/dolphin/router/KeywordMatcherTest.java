package com.dolphin.router;

import com.dolphin.capability.CapabilityRegistry;
import com.dolphin.router.matcher.KeywordMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词匹配器:计分、置信度递增、平票弃权。
 */
class KeywordMatcherTest {

    private final CapabilityRegistry registry =
            new CapabilityRegistry(List.of(TestHandlers.chat(), TestHandlers.ragQa(), TestHandlers.ticketFlow()));
    private final KeywordMatcher matcher = new KeywordMatcher(registry);
    private final DialogContext ctx = new DialogContext("t");

    @Test
    void 单关键词命中得基础置信度() {
        Optional<RouteDecision> d = matcher.match("这个流程是什么", ctx);
        assertThat(d).isPresent();
        assertThat(d.get().capabilityCode()).isEqualTo("rag-qa");
        assertThat(d.get().confidence()).isEqualTo(0.6);
    }

    @Test
    void 多关键词命中置信度递增() {
        Optional<RouteDecision> d = matcher.match("这个怎么弄,流程如何走", ctx);
        assertThat(d).isPresent();
        assertThat(d.get().capabilityCode()).isEqualTo("rag-qa");
        assertThat(d.get().confidence()).isGreaterThan(0.6);
    }

    @Test
    void 得分最高者胜出() {
        Optional<RouteDecision> d = matcher.match("工单故障了,服务挂了", ctx);
        assertThat(d).isPresent();
        // ticket-flow 命中 3 个(工单+故障+挂了) > rag-qa 命中 0 个
        assertThat(d.get().capabilityCode()).isEqualTo("ticket-flow");
        assertThat(d.get().confidence()).isGreaterThan(0.6);
    }

    @Test
    void 平票时弃权交给下一级() {
        Optional<RouteDecision> d = matcher.match("工单流程", ctx);
        // ticket-flow(工单) 与 rag-qa(流程) 各 1 分,平票 → empty
        assertThat(d).isEmpty();
    }

    @Test
    void 无命中返回空() {
        assertThat(matcher.match("今天天气不错", ctx)).isEmpty();
    }
}
