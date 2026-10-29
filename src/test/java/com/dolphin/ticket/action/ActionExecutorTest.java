package com.dolphin.ticket.action;

import com.dolphin.cloud.CloudApiProperties;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.service.CloudApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作执行器统一管线:白名单→审批令牌门禁→幂等→提交→验证。
 */
class ActionExecutorTest {

    private ActionExecutor executor;
    private ApprovalTokenService tokenService;
    private CapturingAuditLogger audit;

    /** 捕获审计留痕,便于断言拒绝原因已写入审计 */
    private static class CapturingAuditLogger extends AuditLogger {
        final List<String> lines = new ArrayList<>();

        CapturingAuditLogger() {
            super(new CloudApiProperties());
        }

        @Override
        public void log(String ticketId, String action, String paramsSummary) {
            lines.add(ticketId + "|" + action + "|" + paramsSummary);
        }
    }

    @BeforeEach
    void setUp() {
        tokenService = new ApprovalTokenService(Duration.ofMinutes(10), "test-secret");
        audit = new CapturingAuditLogger();
        CloudApiService cloud = new CloudApiService();
        ResultVerifier verifier = new ResultVerifier(Duration.ofMillis(30), Duration.ofMillis(500), cloud);
        executor = new ActionExecutor(new ActionRegistry(), cloud, audit, tokenService, verifier);
    }

    private ActionProposal proposal(String action, String target) {
        return new ActionProposal(action, target, java.util.Map.of(), 1.0);
    }

    @Test
    void 白名单外的动作一律拒绝() {
        var result = executor.execute("t-1", proposal("dropDatabase", "db-1"), null);

        assertThat(result.status()).isEqualTo(ActionExecutor.Status.REJECTED);
        assertThat(result.message()).contains("白名单");
    }

    @Test
    void 需审批动作无有效令牌被门禁拒绝且审计留原因() {
        var result = executor.execute("t-1", proposal("restartInstance", "i-abc"), null);

        assertThat(result.status()).isEqualTo(ActionExecutor.Status.REJECTED);
        assertThat(result.message()).contains("未经人工审批").contains("未提供审批令牌");
        assertThat(audit.lines).anySatisfy(line -> assertThat(line)
                .contains("restartInstance:REJECTED").contains("审批令牌校验失败").contains("未提供审批令牌"));
    }

    @Test
    void 审批令牌通过后执行并真实轮询验证() {
        var p = proposal("restartInstance", "i-abc");
        String token = tokenService.issue("t-1", p);

        var result = executor.execute("t-1", p, token);

        assertThat(result.status()).isEqualTo(ActionExecutor.Status.SUCCESS);
        assertThat(result.message()).contains("重启指令").contains("轮询").contains("RUNNING").contains("序号");
        assertThat(audit.lines).anySatisfy(line -> assertThat(line)
                .contains("restartInstance:APPROVAL-TOKEN").contains("令牌校验通过"));
        // 提交前基线必须落审计:验证阶段的"本次变更"判定依赖它
        assertThat(audit.lines).anySatisfy(line -> assertThat(line)
                .contains("restartInstance:BASELINE").contains("restartGeneration=0"));
        assertThat(audit.lines).anySatisfy(line -> assertThat(line)
                .contains("restartInstance:VERIFY").contains("通过"));
    }

    @Test
    void 重放同一令牌第二次执行被拒() {
        var p = proposal("restartInstance", "i-abc");
        String token = tokenService.issue("t-1", p);
        var first = executor.execute("t-1", p, token);
        var replay = executor.execute("t-1", p, token);

        assertThat(first.status()).isEqualTo(ActionExecutor.Status.SUCCESS);
        assertThat(replay.status()).isEqualTo(ActionExecutor.Status.REJECTED);
        assertThat(replay.message()).contains("已被消费");
    }

    @Test
    void 令牌绑定不匹配拿去执行他单动作被拒() {
        var approved = proposal("restartInstance", "i-abc");
        String token = tokenService.issue("t-1", approved);

        var result = executor.execute("t-1", proposal("restartInstance", "i-victim"), token);

        assertThat(result.status()).isEqualTo(ActionExecutor.Status.REJECTED);
        assertThat(result.message()).contains("参数摘要不匹配");
    }

    @Test
    void 幂等拦截同工单同动作重复执行() {
        var p = proposal("refundOrder", "o-9");
        var first = executor.execute("t-1", p, tokenService.issue("t-1", p));
        var second = executor.execute("t-1", p, tokenService.issue("t-1", p));

        assertThat(first.status()).isEqualTo(ActionExecutor.Status.SUCCESS);
        assertThat(second.status()).isEqualTo(ActionExecutor.Status.SKIPPED);
        assertThat(second.message()).contains("幂等");
    }

    @Test
    void 免审批的低风险配置动作可直接执行() {
        var p = new ActionProposal("authorizeSecurityGroup", "sg-1",
                java.util.Map.of("port", "443"), 1.0);

        var result = executor.execute("t-1", p, null);

        assertThat(result.status()).isEqualTo(ActionExecutor.Status.SUCCESS);
        assertThat(result.message()).contains("TCP 443").contains("探测").contains("连通成功");
    }

    @Test
    void 验证失败收敛为VERIFY_FAILED并审计留痕() {
        // stuck 标记:重启受理后实例进入 FAILED 终态,轮询立即判负
        var p = proposal("restartInstance", "i-stuck-1");
        String token = tokenService.issue("t-1", p);

        var result = executor.execute("t-1", p, token);

        assertThat(result.status()).isEqualTo(ActionExecutor.Status.VERIFY_FAILED);
        assertThat(result.message()).contains("提交").contains("验证失败").contains("FAILED");
        assertThat(audit.lines).anySatisfy(line -> assertThat(line)
                .contains("restartInstance:VERIFY").contains("失败"));
    }

    @Test
    void 状态字符串与结构化建议互转() {
        ActionProposal parsed = ActionProposal.parse("authorizeSecurityGroup:sg-1:port=443");

        assertThat(parsed).isNotNull();
        assertThat(parsed.action()).isEqualTo("authorizeSecurityGroup");
        assertThat(parsed.target()).isEqualTo("sg-1");
        assertThat(parsed.params()).containsEntry("port", "443");
        assertThat(parsed.toStateString()).isEqualTo("authorizeSecurityGroup:sg-1:port=443");
        assertThat(ActionProposal.parse("")).isNull();
    }
}
