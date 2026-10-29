package com.dolphin.router;

import com.dolphin.router.matcher.StickySessionMatcher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话粘性匹配器:占用即短路、换话题解除、空闲放行。
 */
class StickySessionMatcherTest {

    private final StickySessionMatcher matcher = new StickySessionMatcher();

    @Test
    void 会话被流程占用时直接短路回原能力() {
        DialogContext ctx = new DialogContext("s1");
        ctx.setActiveCapability("ticket-flow");

        var d = matcher.match("随便说点什么", ctx);
        assertThat(d).isPresent();
        assertThat(d.get().capabilityCode()).isEqualTo("ticket-flow");
        assertThat(d.get().matchedBy()).isEqualTo("STICKY");
    }

    @Test
    void 换话题话术解除粘性并放行() {
        DialogContext ctx = new DialogContext("s2");
        ctx.setActiveCapability("ticket-flow");
        ctx.setPendingTicketId("T123");

        var d = matcher.match("换个话题吧", ctx);
        assertThat(d).isEmpty();
        assertThat(ctx.getActiveCapability()).isNull();
        assertThat(ctx.getPendingTicketId()).isNull();
    }

    @Test
    void 无进行中流程时不干预() {
        DialogContext ctx = new DialogContext("s3");
        assertThat(matcher.match("帮我提个工单", ctx)).isEmpty();
    }
}
