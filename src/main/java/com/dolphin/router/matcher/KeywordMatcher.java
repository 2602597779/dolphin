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
 * 第 3 级:关键词计数匹配。
 *
 * <p>对每个能力统计命中关键词个数,取得分最高且至少命中 1 个的能力;
 * 置信度随命中数递增(0.6 起步,上限 0.9)。多个能力得分相同时视为不确定,
 * 交给下一级 LLM 分类裁决。
 */
@Component
@Order(3)
public class KeywordMatcher implements IntentMatcher {

    private final CapabilityRegistry registry;

    public KeywordMatcher(CapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<RouteDecision> match(String message, DialogContext ctx) {
        String bestCode = null;
        int bestHits = 0;
        boolean tie = false;
        for (CapabilityDescriptor d : registry.descriptors()) {
            int hits = (int) d.keywords().stream().filter(message::contains).count();
            if (hits > bestHits) {
                bestHits = hits;
                bestCode = d.code();
                tie = false;
            } else if (hits == bestHits && hits > 0 && bestCode != null && !bestCode.equals(d.code())) {
                tie = true;
            }
        }
        if (bestCode == null || tie) {
            return Optional.empty();
        }
        double confidence = Math.min(0.6 + 0.1 * (bestHits - 1), 0.9);
        return Optional.of(new RouteDecision(bestCode, "KEYWORD", confidence));
    }
}
