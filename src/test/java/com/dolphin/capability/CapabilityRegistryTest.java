package com.dolphin.capability;

import com.dolphin.router.TestHandlers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 能力注册中心:映射构建、未知 code 兜底、缺失 chat 启动失败。
 */
class CapabilityRegistryTest {

    @Test
    void 按描述符code建立映射并可查询() {
        CapabilityRegistry registry = new CapabilityRegistry(
                List.of(TestHandlers.chat(), TestHandlers.ragQa(), TestHandlers.ticketFlow()));

        assertThat(registry.get("rag-qa").descriptor().code()).isEqualTo("rag-qa");
        assertThat(registry.get("ticket-flow").descriptor().code()).isEqualTo("ticket-flow");
        assertThat(registry.isKnown("chat")).isTrue();
        assertThat(registry.descriptors()).hasSize(3);
    }

    @Test
    void 未知code回落chat兜底() {
        CapabilityRegistry registry = new CapabilityRegistry(
                List.of(TestHandlers.chat(), TestHandlers.ragQa()));

        assertThat(registry.get("not-exist")).isSameAs(registry.get("chat"));
    }

    @Test
    void 缺少chat兜底处理器时启动失败() {
        assertThatThrownBy(() -> new CapabilityRegistry(List.of(TestHandlers.ragQa())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat");
    }
}
