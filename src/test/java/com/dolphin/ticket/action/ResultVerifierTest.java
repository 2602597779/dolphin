package com.dolphin.ticket.action;

import com.dolphin.ticket.service.CloudApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结果验证器:三种验证范式的真实等待与判定(成功路径 + 失败路径 + 基线防假阳性)。
 *
 * <p>使用快速参数构造器(毫秒级延迟/轮询间隔),故障注入标记见 CloudApiService
 * (stuck=启动失败终态、hang=夯住永不就绪、void=规则下发丢失、lost=落账丢失)。
 *
 * <p>每个用例都在"提交变更之前"采样基线,与执行器的真实调用顺序一致:
 * 只校验终态会假阳性(旧 RUNNING/旧规则/旧流水),必须观测到基线之后的变化才算完成。
 */
class ResultVerifierTest {

    private CloudApiService cloud;
    private ResultVerifier verifier;

    @BeforeEach
    void setUp() {
        cloud = new CloudApiService();
        verifier = new ResultVerifier(Duration.ofMillis(30), Duration.ofMillis(500), cloud);
    }

    private ActionDescriptor desc(VerifyMode mode, Duration timeout) {
        return new ActionDescriptor("x", "x", RiskLevel.WRITE, false, true, mode, timeout);
    }

    private ActionProposal proposal(String target) {
        return new ActionProposal("x", target, Map.of("port", "8443"), 1.0);
    }

    @Test
    void 轮询观测到本次重启后的RUNNING() {
        var d = desc(VerifyMode.POLL_STATUS, Duration.ofSeconds(2));
        var p = proposal("i-1");
        ResultVerifier.Baseline baseline = verifier.capture(d, p);
        cloud.restartInstance("i-1");

        ResultVerifier.Outcome out = verifier.verify(d, p, baseline);

        assertThat(out.verified()).isTrue();
        assertThat(out.detail()).contains("RUNNING").contains("轮询").contains("序号");
    }

    @Test
    void 轮询看到变更前旧RUNNING不算完成() {
        var d = desc(VerifyMode.POLL_STATUS, Duration.ofMillis(400));
        var p = proposal("i-never-restarted");
        // 基线采样时实例就是 RUNNING(从未重启),若不比对基线会被直接误判为验证通过
        ResultVerifier.Baseline baseline = verifier.capture(d, p);

        ResultVerifier.Outcome out = verifier.verify(d, p, baseline);

        assertThat(out.verified()).isFalse();
        assertThat(out.detail()).contains("超时").contains("未观测到本次重启");
    }

    @Test
    void 轮询观测到FAILED终态立即判负不等满超时() {
        var d = desc(VerifyMode.POLL_STATUS, Duration.ofSeconds(30));
        var p = proposal("i-stuck-1");
        ResultVerifier.Baseline baseline = verifier.capture(d, p);
        cloud.restartInstance("i-stuck-1");
        long start = System.currentTimeMillis();

        ResultVerifier.Outcome out = verifier.verify(d, p, baseline);

        assertThat(out.verified()).isFalse();
        assertThat(out.detail()).contains("FAILED");
        assertThat(System.currentTimeMillis() - start).isLessThan(5_000);
    }

    @Test
    void 轮询容忍时长内未就绪判超时() {
        var d = desc(VerifyMode.POLL_STATUS, Duration.ofMillis(300));
        var p = proposal("i-hang-1");
        ResultVerifier.Baseline baseline = verifier.capture(d, p);
        cloud.restartInstance("i-hang-1");

        ResultVerifier.Outcome out = verifier.verify(d, p, baseline);

        assertThat(out.verified()).isFalse();
        assertThat(out.detail()).contains("超时").contains("STARTING");
    }

    @Test
    void 探测在规则生效后真实TCP连通且观测到新规则() {
        var d = desc(VerifyMode.PROBE, Duration.ofSeconds(5));
        var p = proposal("sg-1");
        ResultVerifier.Baseline baseline = verifier.capture(d, p);
        cloud.authorizeSecurityGroup("sg-1", 8443);

        ResultVerifier.Outcome out = verifier.verify(d, p, baseline);

        assertThat(out.verified()).isTrue();
        assertThat(out.detail()).contains("外部探测").contains("连通成功").contains("版本");
    }

    @Test
    void 探测已有旧规则连通但未新下发不算完成() {
        var d = desc(VerifyMode.PROBE, Duration.ofMillis(400));
        var p = proposal("sg-existing");
        cloud.authorizeSecurityGroup("sg-existing", 8443);
        // 基线里已存在放行规则;若本次没有新下发,端口即使连通也不能算本次变更生效
        ResultVerifier.Baseline baseline = verifier.capture(d, p);

        ResultVerifier.Outcome out = verifier.verify(d, p, baseline);

        assertThat(out.verified()).isFalse();
        assertThat(out.detail()).contains("超时").contains("未观测到本次新下发");
    }

    @Test
    void 探测规则下发丢失时超时判负() {
        var d = desc(VerifyMode.PROBE, Duration.ofMillis(300));
        var p = proposal("sg-void-1");
        ResultVerifier.Baseline baseline = verifier.capture(d, p);
        cloud.authorizeSecurityGroup("sg-void-1", 8443);

        ResultVerifier.Outcome out = verifier.verify(d, p, baseline);

        assertThat(out.verified()).isFalse();
        assertThat(out.detail()).contains("超时").contains("8443");
    }

    @Test
    void 对账确认本次新受理流水() {
        var d = desc(VerifyMode.RECONCILE, Duration.ofSeconds(5));
        var p = proposal("o-1");
        ResultVerifier.Baseline baseline = verifier.capture(d, p);
        cloud.refundOrder("o-1");

        ResultVerifier.Outcome out = verifier.verify(d, p, baseline);

        assertThat(out.verified()).isTrue();
        assertThat(out.detail()).contains("流水号").contains("基线");
    }

    @Test
    void 对账旧流水不重复计入() {
        var d = desc(VerifyMode.RECONCILE, Duration.ofMillis(400));
        var p = proposal("o-existing");
        cloud.refundOrder("o-existing");
        awaitRefundVisible("o-existing");
        // 基线里已有该订单的旧流水;若本次没有新受理,旧流水不能当作本次完成
        ResultVerifier.Baseline baseline = verifier.capture(d, p);

        ResultVerifier.Outcome out = verifier.verify(d, p, baseline);

        assertThat(out.verified()).isFalse();
        assertThat(out.detail()).contains("对账失败").contains("旧流水");
    }

    @Test
    void 对账落账丢失超时判负() {
        var d = desc(VerifyMode.RECONCILE, Duration.ofMillis(300));
        var p = proposal("o-lost-1");
        ResultVerifier.Baseline baseline = verifier.capture(d, p);
        cloud.refundOrder("o-lost-1");

        ResultVerifier.Outcome out = verifier.verify(d, p, baseline);

        assertThat(out.verified()).isFalse();
        assertThat(out.detail()).contains("对账失败");
    }

    @Test
    void 无需验证模式直接通过() {
        ResultVerifier.Outcome out = verifier.verify(
                desc(VerifyMode.NONE, Duration.ofSeconds(1)), proposal("x"), ResultVerifier.Baseline.EMPTY);

        assertThat(out.verified()).isTrue();
        assertThat(out.detail()).contains("无需验证");
    }

    /** 等待模拟异步落账的流水可见,供"旧流水"基线用例采样到真实历史值 */
    private void awaitRefundVisible(String orderId) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (cloud.queryRefundRecord(orderId).isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
