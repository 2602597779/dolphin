package com.dolphin.ticket.action;

import java.util.Map;

/**
 * 结构化动作建议:诊断/分诊环节产出,执行环节消费。
 *
 * <p>LLM 的职责到"建议"为止——它只能产出本结构(动作名必须在 {@link ActionRegistry} 白名单内),
 * 从执行开始全部由确定性代码完成,模型幻觉不可能直接变成线上动作。
 *
 * @param action     动作名,须命中注册表
 * @param target     操作对象(实例 ID/订单号/安全组 ID)
 * @param params     附加参数(如端口号)
 * @param confidence 建议置信度(0~1);低于阈值的建议应降级转人工。
 *                   结构化来源(DiagnosisAgent PROPOSAL 行)给出真实值;
 *                   状态通道字符串反解析({@link #parse})无置信度信息,按 1.0 处理
 */
public record ActionProposal(
        String action,
        String target,
        Map<String, Object> params,
        double confidence
) {

    /**
     * 解析状态通道里的待执行动作字符串,格式 "actionName:target" 或 "actionName:target:key=value"。
     *
     * @return 解析结果;raw 为空白时返回 null(表示无待执行动作)
     */
    public static ActionProposal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(":");
        String action = parts[0];
        String target = parts.length > 1 ? parts[1] : "";
        Map<String, Object> params = Map.of();
        for (int i = 2; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) {
                params = Map.of(parts[i].substring(0, eq), parts[i].substring(eq + 1));
            }
        }
        return new ActionProposal(action, target, params, 1.0);
    }

    /** 序列化为状态通道字符串(与 {@link #parse} 互逆) */
    public String toStateString() {
        StringBuilder sb = new StringBuilder(action).append(':').append(target);
        params.forEach((k, v) -> sb.append(':').append(k).append('=').append(v));
        return sb.toString();
    }
}
