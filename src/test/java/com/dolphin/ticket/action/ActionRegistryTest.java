package com.dolphin.ticket.action;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动作注册表:内置目录完整性、审批策略数据化、重复注册 fail-fast。
 */
class ActionRegistryTest {

    @Test
    void 内置三个动作且审批策略由描述符数据决定() {
        ActionRegistry registry = new ActionRegistry();

        assertThat(registry.isKnown("restartInstance")).isTrue();
        assertThat(registry.isKnown("refundOrder")).isTrue();
        assertThat(registry.isKnown("authorizeSecurityGroup")).isTrue();

        // 生命周期与资金类必须审批;低风险可逆配置变更免审批
        assertThat(registry.get("restartInstance").orElseThrow().requireApproval()).isTrue();
        assertThat(registry.get("refundOrder").orElseThrow().risk()).isEqualTo(RiskLevel.FINANCIAL);
        assertThat(registry.get("authorizeSecurityGroup").orElseThrow().requireApproval()).isFalse();
    }

    @Test
    void 未知动作不在白名单() {
        assertThat(new ActionRegistry().isKnown("dropDatabase")).isFalse();
    }

    @Test
    void 重复注册同名动作启动期失败() {
        ActionRegistry registry = new ActionRegistry();
        var dup = new ActionDescriptor("restartInstance", "重复", RiskLevel.READ_ONLY,
                false, false, VerifyMode.NONE, Duration.ZERO);

        assertThatThrownBy(() -> registry.register(dup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restartInstance");
    }
}
