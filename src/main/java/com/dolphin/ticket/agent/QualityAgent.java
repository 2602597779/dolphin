package com.dolphin.ticket.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 质检 Agent:对结单终稿做合规与语气审查(结单后异步执行,不占用主图同步链路)。
 *
 * <p>输出契约(与 prompts/quality.st 约定一致):模型输出最后一行必须是
 * {@code VERDICT:PASS} 或 {@code VERDICT:REJECT},其余内容为理由与修改建议。
 * 契约行缺失或多行视为输出不可信({@code contractValid=false}),只留痕不重开工单——
 * 质检解析故障不应惩罚正常结单。
 *
 * <p>接线方式见 {@code QualityReviewService}:REJECT → 工单重开为 ESCALATED 人工复核。
 */
@Component
public class QualityAgent {

    static final String VERDICT_PREFIX = "VERDICT:";
    static final String VERDICT_PASS = "PASS";
    static final String VERDICT_REJECT = "REJECT";

    /** 质检结论。
     *
     * @param passed        是否通过(PASS=true,REJECT=false,契约无效=false)
     * @param contractValid 契约行是否合法(缺失/多行/调用失败均视为 false)
     * @param opinion       质检意见(已剥离契约行的理由与建议文本)
     */
    public record Verdict(boolean passed, boolean contractValid, String opinion) {
    }

    private final ChatClient chatClient;

    public QualityAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(PromptLoader.load("quality.st"))
                .build();
    }

    /**
     * 审查结单终稿。
     *
     * @param finalReply 结单终稿
     * @return 结构化质检结论
     */
    public Verdict review(String finalReply) {
        String content;
        try {
            content = chatClient.prompt().user("请审查以下结单终稿:\n" + finalReply).call().content();
        } catch (Exception e) {
            return new Verdict(false, false, "质检 Agent 调用失败: " + e.getMessage());
        }
        return parse(content);
    }

    /**
     * 按契约解析质检输出:剥离 VERDICT 行得到意见正文,并解析结论。
     * package 静态,便于单测直接覆盖解析分支。
     */
    static Verdict parse(String content) {
        if (content == null || content.isBlank()) {
            return new Verdict(false, false, "质检输出为空");
        }
        StringBuilder opinion = new StringBuilder();
        String verdictRaw = null;
        int verdictLines = 0;
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, VERDICT_PREFIX, 0, VERDICT_PREFIX.length())) {
                verdictLines++;
                if (verdictRaw == null) {
                    verdictRaw = trimmed.substring(VERDICT_PREFIX.length()).trim();
                }
                continue;
            }
            opinion.append(line).append('\n');
        }
        if (verdictRaw == null || verdictLines > 1) {
            return new Verdict(false, false, "质检契约行缺失或多行,输出不可信:\n" + opinion.toString().strip());
        }
        if (VERDICT_PASS.equalsIgnoreCase(verdictRaw)) {
            return new Verdict(true, true, opinion.toString().strip());
        }
        if (VERDICT_REJECT.equalsIgnoreCase(verdictRaw)) {
            return new Verdict(false, true, opinion.toString().strip());
        }
        return new Verdict(false, false, "未知质检结论(" + verdictRaw + "),输出不可信:\n" + opinion.toString().strip());
    }
}
