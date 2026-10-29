package com.dolphin.router.matcher;

import com.dolphin.router.DialogContext;
import com.dolphin.router.IntentMatcher;
import com.dolphin.router.RouteDecision;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 第 0 级:会话粘性短路。
 *
 * <p>会话存在进行中的能力流程时,消息直接回原流程,避免"工单跑到一半说句话
 * 被路由去闲聊"。含显式换话题词则解除粘性,交还后续匹配器。
 */
@Component
@Order(0)
public class StickySessionMatcher implements IntentMatcher {

    /** 解除粘性的显式话术 */
    private static final List<String> RESET_WORDS = List.of("换个话题", "重新开始", "退出", "不用了");

    @Override
    public Optional<RouteDecision> match(String message, DialogContext ctx) {
        String active = ctx.getActiveCapability();
        if (active == null) {
            return Optional.empty();
        }
        if (RESET_WORDS.stream().anyMatch(message::contains)) {
            ctx.setActiveCapability(null);
            ctx.setPendingTicketId(null);
            return Optional.empty();
        }
        return Optional.of(new RouteDecision(active, "STICKY", 1.0));
    }
}
