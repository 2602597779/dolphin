package com.dolphin.router.matcher;

import com.dolphin.capability.CapabilityDescriptor;
import com.dolphin.capability.CapabilityRegistry;
import com.dolphin.router.DialogContext;
import com.dolphin.router.IntentMatcher;
import com.dolphin.router.RouteDecision;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 第 1 级:精确短语匹配(equalsIgnoreCase),命中即高置信路由。
 */
@Component
@Order(1)
public class ExactMatcher implements IntentMatcher {

    private final CapabilityRegistry registry;

    public ExactMatcher(CapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<RouteDecision> match(String message, DialogContext ctx) {
        for (CapabilityDescriptor d : registry.descriptors()) {
            boolean hit = d.exactPhrases().stream().anyMatch(p -> p.equalsIgnoreCase(message.trim()));
            if (hit) {
                return Optional.of(new RouteDecision(d.code(), "EXACT", 0.99));
            }
        }
        return Optional.empty();
    }
}
