package com.dolphin.ticket.agent;

import com.dolphin.ticket.action.ActionProposal;
import com.dolphin.ticket.action.ActionRegistry;
import com.dolphin.ticket.tool.DiagnosisTools;
import com.dolphin.ticket.tool.ResourceTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 故障诊断 Agent:结合云资源只读工具与相似工单检索,产出诊断报告 + 结构化动作建议。
 *
 * <p>只挂只读工具(ResourceTools/DiagnosisTools);写操作链路(ActionExecutor)严禁注册到此,
 * 保证模型无法自主触发高危动作——模型只产出动作建议,执行由确定性代码完成。
 *
 * <p>动作建议通过报告末尾的契约行传递(与 prompts/diagnosis.st 约定一致):
 * <ul>
 *   <li>{@code PROPOSAL:NONE} — 无需写操作;</li>
 *   <li>{@code PROPOSAL:{"action":...,"target":...,"params":...,"confidence":...}} — 结构化建议。</li>
 * </ul>
 * 契约行缺失、JSON 不合法、必填字段为空、confidence 低于 {@link #MIN_CONFIDENCE}
 * 一律判定为 proposalInvalid=true,由图路由收敛到"转人工",绝不猜测执行。
 * 动作名是否真的可执行,仍由 {@link ActionRegistry} 白名单在执行期兜底(二次防御)。
 */
@Component
public class DiagnosisAgent {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisAgent.class);

    /** 契约行前缀:报告最后一行以此开头 */
    static final String PROPOSAL_PREFIX = "PROPOSAL:";

    /** 置信度阈值:低于该值的动作建议视为不可信,降级转人工 */
    static final double MIN_CONFIDENCE = 0.6;

    /**
     * 诊断产出。
     *
     * @param report          诊断报告正文(已剥离契约行,供回复 Agent 润色)
     * @param proposal        结构化动作建议;NONE 或无写操作时为 null
     * @param proposalInvalid 建议不可信(调用失败/契约缺失/格式非法/低置信度),应转人工
     */
    public record DiagnosisOutcome(String report, ActionProposal proposal, boolean proposalInvalid) {
    }

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public DiagnosisAgent(ChatClient.Builder chatClientBuilder,
                          ResourceTools resourceTools,
                          DiagnosisTools diagnosisTools,
                          ActionRegistry actionRegistry,
                          ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 白名单动作目录注入系统提示词:注册表是"模型能建议什么"的唯一事实来源
        String whitelist = actionRegistry.descriptors().stream()
                .map(d -> "- " + d.name() + ":" + d.displayName())
                .collect(Collectors.joining("\n"));
        this.chatClient = chatClientBuilder
                .defaultSystem(PromptLoader.load("diagnosis.st")
                        + "\n可建议的白名单动作(action 只能取以下动作名之一):\n" + whitelist)
                .defaultTools(resourceTools, diagnosisTools)
                .build();
    }

    /**
     * 诊断一条故障工单。
     *
     * @param rawMessage     用户原始工单文本
     * @param classification 分诊结果 {product, type, urgency}
     * @param similarTickets 相似历史工单列表(可能为空,Agent 也可自行调用检索工具)
     * @return 诊断报告 + 结构化动作建议
     */
    public DiagnosisOutcome diagnose(String rawMessage, Map<String, Object> classification, String similarTickets) {
        String user = "工单原文:" + rawMessage
                + "\n分诊结果:产品=" + classification.getOrDefault("product", "未知")
                + ",紧急度=" + classification.getOrDefault("urgency", "低")
                + "\n已检索到的相似工单:\n" + (similarTickets == null || similarTickets.isBlank() ? "无" : similarTickets);
        // TODO: 多轮 ReAct 上限控制与工具调用审计聚合
        String content;
        try {
            content = chatClient.prompt().user(user).call().content();
        } catch (Exception e) {
            log.warn("诊断 Agent 调用失败,本单判定为建议不可信(转人工): {}", e.getMessage());
            return new DiagnosisOutcome("", null, true);
        }
        return toOutcome(content, objectMapper);
    }

    /**
     * 按契约解析模型输出:剥离 PROPOSAL 行得到报告正文,并把 PROPOSAL 行解析为结构化建议。
     * package 静态方法,便于单测直接覆盖解析分支。
     */
    static DiagnosisOutcome toOutcome(String content, ObjectMapper mapper) {
        if (content == null || content.isBlank()) {
            return new DiagnosisOutcome("", null, true);
        }
        StringBuilder report = new StringBuilder();
        String proposalRaw = null;
        int proposalLines = 0;
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(PROPOSAL_PREFIX)) {
                proposalLines++;
                if (proposalRaw == null) {
                    proposalRaw = trimmed.substring(PROPOSAL_PREFIX.length()).trim();
                }
                continue;
            }
            report.append(line).append('\n');
        }
        String reportText = report.toString().strip();

        // 契约行缺失或出现多行:模型未遵守输出契约,不可信
        if (proposalRaw == null || proposalLines > 1) {
            log.warn("诊断输出契约行异常(缺失或多行),判定为不可信: proposalLines={}", proposalLines);
            return new DiagnosisOutcome(reportText, null, true);
        }
        if ("NONE".equalsIgnoreCase(proposalRaw)) {
            return new DiagnosisOutcome(reportText, null, false);
        }
        try {
            JsonNode node = mapper.readTree(proposalRaw);
            String action = text(node, "action");
            String target = text(node, "target");
            double confidence = node.path("confidence").isNumber() ? node.path("confidence").asDouble() : -1;
            if (action.isEmpty() || target.isEmpty() || confidence < MIN_CONFIDENCE) {
                log.warn("诊断动作建议字段缺失或低置信度,判定为不可信: action={} target={} confidence={}",
                        action, target, confidence);
                return new DiagnosisOutcome(reportText, null, true);
            }
            Map<String, Object> params = new LinkedHashMap<>();
            JsonNode paramsNode = node.path("params");
            if (paramsNode.isObject()) {
                paramsNode.fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
            }
            ActionProposal proposal = new ActionProposal(action, target, params, confidence);
            return new DiagnosisOutcome(reportText, proposal, false);
        } catch (Exception e) {
            log.warn("诊断动作建议 JSON 解析失败,判定为不可信: {}", e.getMessage());
            return new DiagnosisOutcome(reportText, null, true);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText().trim() : "";
    }
}
