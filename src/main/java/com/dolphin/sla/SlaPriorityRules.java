package com.dolphin.sla;

import java.util.List;

/**
 * SLA 优先级推导规则(阶段3):P1~P4 由分诊结论 + 原文宕机信号确定性推导,
 * <b>不依赖模型自觉输出优先级</b>——可审计、可复现、离线演示同样生效。
 *
 * <p>规则表(与 PLAN/README 口径一致):
 * <ul>
 *   <li>P1:故障 且 原文命中宕机类信号(连不上/宕机/无法连接/不可用…)</li>
 *   <li>P2:故障(其余)</li>
 *   <li>P4:咨询</li>
 *   <li>P3:其他类型(高危/超范围/未知)及未分级工单的默认档</li>
 * </ul>
 */
public final class SlaPriorityRules {

    public static final String P1 = "P1";
    public static final String P2 = "P2";
    public static final String P3 = "P3";
    public static final String P4 = "P4";

    /** P1 信号词:命中即视为宕机级故障 */
    private static final List<String> OUTAGE_SIGNALS = List.of(
            "连不上", "无法连接", "连接不上", "连不通", "宕机", "挂了", "不可用", "失联", "无法访问");

    private SlaPriorityRules() {
    }

    /**
     * 由分诊类型与原始文本推导优先级。
     *
     * @param type       分诊类型(咨询/故障/高危/超范围;null/未知按默认档)
     * @param rawMessage 工单原始文本(null 视为无宕机信号)
     */
    public static String derive(String type, String rawMessage) {
        if ("故障".equals(type)) {
            return hasOutageSignal(rawMessage) ? P1 : P2;
        }
        if ("咨询".equals(type)) {
            return P4;
        }
        return P3;
    }

    private static boolean hasOutageSignal(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        return OUTAGE_SIGNALS.stream().anyMatch(rawMessage::contains);
    }
}
