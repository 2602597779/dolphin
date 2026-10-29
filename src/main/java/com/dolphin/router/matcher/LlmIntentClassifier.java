package com.dolphin.router.matcher;

import com.dolphin.capability.CapabilityDescriptor;
import com.dolphin.capability.CapabilityRegistry;
import com.dolphin.router.DialogContext;
import com.dolphin.router.IntentMatcher;
import com.dolphin.router.RouteDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 第 4 级:LLM 意图分类(规则链都没命中时的语义兜底判定)。
 *
 * <p>关键设计:分类 prompt 由 {@link CapabilityRegistry} 的描述符<b>动态生成</b>
 * (intentDesc + examples),新增能力无需改动此类。
 * 结构化输出 capabilityCode/confidence,低置信或非法 code 一律放弃,交 IntentRouter 回落兜底。
 */
@Component
@Order(4)
public class LlmIntentClassifier implements IntentMatcher {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);

    /** LLM 分类置信度下限,低于此值不采信 */
    private static final double ACCEPT_THRESHOLD = 0.55;

    /** LLM 结构化输出目标 */
    record IntentChoice(String capabilityCode, double confidence, String reason) {
    }

    private final ChatClient chat;
    private final CapabilityRegistry registry;

    public LlmIntentClassifier(ChatClient.Builder builder, CapabilityRegistry registry) {
        this.registry = registry;
        this.chat = builder.defaultSystem(buildRouterPrompt(registry)).build();
    }

    @Override
    public Optional<RouteDecision> match(String message, DialogContext ctx) {
        try {
            IntentChoice choice = chat.prompt().user(message).call().entity(IntentChoice.class);
            if (choice == null || choice.capabilityCode() == null) {
                return Optional.empty();
            }
            if (!registry.isKnown(choice.capabilityCode())) {
                log.warn("[router] LLM 返回未知能力编码: {},放弃", choice.capabilityCode());
                return Optional.empty();
            }
            if (choice.confidence() < ACCEPT_THRESHOLD) {
                log.debug("[router] LLM 置信度过低({}),放弃: {}", choice.confidence(), choice.reason());
                return Optional.empty();
            }
            return Optional.of(new RouteDecision(choice.capabilityCode(), "LLM", choice.confidence()));
        } catch (Exception e) {
            // LLM 不可用(无 Key/网络异常)时静默降级到规则外的兜底,不阻塞对话
            log.warn("[router] LLM 意图分类失败,回落兜底: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 由注册表动态生成分类 prompt:能力清单 + 示例说法 */
    private static String buildRouterPrompt(CapabilityRegistry registry) {
        String capabilityList = registry.descriptors().stream()
                .map(d -> "- %s(%s):%s%n  典型说法:%s".formatted(
                        d.code(), d.name(), d.intentDesc(),
                        d.examples().stream().collect(Collectors.joining(";"))))
                .collect(Collectors.joining("\n"));
        return """
                你是意图路由器,负责判断用户消息应分发到哪个能力分支。
                可选能力清单:
                %s

                判断规则:
                1. capabilityCode 只能取上述清单中的 code;
                2. 消息与任何能力都不明显匹配时,选 chat 并给出低置信度;
                3. confidence 为 0~1 的语义匹配把握度;reason 用一句话说明依据。
                """.formatted(capabilityList);
    }
}
