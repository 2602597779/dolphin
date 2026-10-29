package com.dolphin.router.matcher;

import com.dolphin.capability.CapabilityDescriptor;
import com.dolphin.capability.CapabilityRegistry;
import com.dolphin.router.DialogContext;
import com.dolphin.router.IntentMatcher;
import com.dolphin.router.RouteDecision;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 第 2 级:正则匹配(find 语义),适合"帮我提个工单"类句式。
 */
@Component
@Order(2)
public class RegexMatcher implements IntentMatcher {

    private final CapabilityRegistry registry;

    public RegexMatcher(CapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<RouteDecision> match(String message, DialogContext ctx) {
        for (CapabilityDescriptor d : registry.descriptors()) {
            boolean hit = d.regexPatterns().stream()
                    .anyMatch(p -> Pattern.compile(p).matcher(message).find());
            if (hit) {
                return Optional.of(new RouteDecision(d.code(), "REGEX", 0.9));
            }
        }
        return Optional.empty();
    }
}
