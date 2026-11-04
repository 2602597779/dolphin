package com.dolphin.sla;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 阶段3 优先级推导规则:确定性规则表,直接断言矩阵 */
class SlaPriorityRulesTest {

    @Test
    void faultWithOutageSignalIsP1() {
        assertThat(SlaPriorityRules.derive("故障", "ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复"))
                .isEqualTo(SlaPriorityRules.P1);
        assertThat(SlaPriorityRules.derive("故障", "实例宕机了")).isEqualTo(SlaPriorityRules.P1);
        assertThat(SlaPriorityRules.derive("故障", "网站无法访问")).isEqualTo(SlaPriorityRules.P1);
    }

    @Test
    void plainFaultIsP2() {
        assertThat(SlaPriorityRules.derive("故障", "安全组 sg-demo 没放行 443 端口"))
                .isEqualTo(SlaPriorityRules.P2);
    }

    @Test
    void consultationIsP4() {
        assertThat(SlaPriorityRules.derive("咨询", "SLB 健康检查不通过一般是什么原因?"))
                .isEqualTo(SlaPriorityRules.P4);
    }

    @Test
    void otherOrNullTypeFallsBackToP3() {
        assertThat(SlaPriorityRules.derive("高危", "删库跑路")).isEqualTo(SlaPriorityRules.P3);
        assertThat(SlaPriorityRules.derive("超范围", "帮我写周报")).isEqualTo(SlaPriorityRules.P3);
        assertThat(SlaPriorityRules.derive(null, "随便")).isEqualTo(SlaPriorityRules.P3);
        assertThat(SlaPriorityRules.derive("未知类型", "随便")).isEqualTo(SlaPriorityRules.P3);
    }

    @Test
    void nullMessageHasNoOutageSignal() {
        // 原文缺失不误判 P1,故障仍按 P2
        assertThat(SlaPriorityRules.derive("故障", null)).isEqualTo(SlaPriorityRules.P2);
    }
}
