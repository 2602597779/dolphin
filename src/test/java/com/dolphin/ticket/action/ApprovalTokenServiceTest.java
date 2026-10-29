package com.dolphin.ticket.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批令牌服务:签发→校验→一次性消费全链路,覆盖防伪/绑定/时效/重放四类拒绝路径。
 */
class ApprovalTokenServiceTest {

    private static final String SECRET = "unit-test-secret";

    private ApprovalTokenService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalTokenService(Duration.ofMinutes(10), SECRET);
    }

    private ActionProposal proposal(String action, String target) {
        return new ActionProposal(action, target, Map.of(), 1.0);
    }

    @Test
    void 签发后校验通过并消费() {
        var p = proposal("restartInstance", "i-abc");
        String token = service.issue("t-1", p);

        assertThat(token).contains(".");
        var result = service.validateAndConsume("t-1", p, token);
        assertThat(result.valid()).isTrue();
        assertThat(result.reason()).contains("令牌校验通过");
    }

    @Test
    void 换目标对象拿去执行被拒() {
        var approved = proposal("restartInstance", "i-abc");
        var attempted = proposal("restartInstance", "i-OTHER");
        String token = service.issue("t-1", approved);

        var result = service.validateAndConsume("t-1", attempted, token);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("参数摘要不匹配");
    }

    @Test
    void 换参数拿去执行被拒() {
        var approved = new ActionProposal("authorizeSecurityGroup", "sg-1", Map.of("port", "443"), 1.0);
        var attempted = new ActionProposal("authorizeSecurityGroup", "sg-1", Map.of("port", "8080"), 1.0);
        String token = service.issue("t-1", approved);

        var result = service.validateAndConsume("t-1", attempted, token);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("参数摘要不匹配");
    }

    @Test
    void 跨工单使用令牌被拒() {
        var p = proposal("restartInstance", "i-abc");
        String token = service.issue("t-1", p);

        var result = service.validateAndConsume("t-2", p, token);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("工单不匹配");
    }

    @Test
    void 换动作使用令牌被拒() {
        var approved = proposal("restartInstance", "o-9");
        var attempted = proposal("refundOrder", "o-9");
        String token = service.issue("t-1", approved);

        var result = service.validateAndConsume("t-1", attempted, token);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("动作不匹配");
    }

    @Test
    void 过期令牌被拒() throws InterruptedException {
        var shortLived = new ApprovalTokenService(Duration.ofMillis(50), SECRET);
        var p = proposal("restartInstance", "i-abc");
        String token = shortLived.issue("t-1", p);
        Thread.sleep(120);

        var result = shortLived.validateAndConsume("t-1", p, token);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("已过期");
    }

    @Test
    void 篡改签名被拒() {
        var p = proposal("restartInstance", "i-abc");
        String token = service.issue("t-1", p);
        int dot = token.indexOf('.');
        // 用篡改后的载荷(换工单号)拼接原签名:签名覆盖载荷,任何改动都验不过
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("t-999|restartInstance|x|1|9999999999999|n".getBytes(StandardCharsets.UTF_8));

        var result = service.validateAndConsume("t-999", p, tamperedPayload + "." + token.substring(dot + 1));
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("签名校验失败");
    }

    @Test
    void 格式非法令牌被拒() {
        var p = proposal("restartInstance", "i-abc");

        assertThat(service.validateAndConsume("t-1", p, "not-a-token").reason()).contains("格式非法");
        assertThat(service.validateAndConsume("t-1", p, "a.b.c").reason()).contains("格式非法");
        assertThat(service.validateAndConsume("t-1", p, "!!!.!!!").reason()).contains("格式非法");
    }

    @Test
    void 重放同一令牌第二次消费被拒() {
        var p = proposal("restartInstance", "i-abc");
        String token = service.issue("t-1", p);

        assertThat(service.validateAndConsume("t-1", p, token).valid()).isTrue();
        var replay = service.validateAndConsume("t-1", p, token);
        assertThat(replay.valid()).isFalse();
        assertThat(replay.reason()).contains("已被消费");
    }

    @Test
    void 未提供令牌被拒() {
        var p = proposal("restartInstance", "i-abc");

        assertThat(service.validateAndConsume("t-1", p, null).reason()).contains("未提供审批令牌");
        assertThat(service.validateAndConsume("t-1", p, "  ").reason()).contains("未提供审批令牌");
    }
}
