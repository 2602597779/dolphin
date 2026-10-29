package com.dolphin.ticket.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-6 全链路离线集成测试:{@code dolphin.cloud.provider=http} 装配 {@link HttpCloudApi},
 * 经真实 HTTP 栈(嵌入式 Tomcat + RestClient)调用<b>本进程</b>的 Mock 云端点({@code /__mock}),
 * 端到端验证数据源切换后"控制面受理 ≠ 数据面生效"契约不被破坏。
 *
 * <p>验证点:
 * <ol>
 *   <li>装配分支正确:容器中 {@link CloudApi} 是 HttpCloudApi 而非进程内模拟器;</li>
 *   <li>重启:受理即 restartGeneration 递增(残值 generation=0 的 RUNNING 不得被误判为已生效),
 *       状态经 HTTP 才延迟转 RUNNING;</li>
 *   <li>退款:受理回执与独立读路径对账分离——未落账时查询为 empty(绝不把受理伪装成完成),
 *       落账后 serialNo 可对账;"lost" 订单受理成功但流水永远查不到;</li>
 *   <li>安全组:受理即带 version 且单调递增;探测端点只有规则生效后才可解析;
 *       "void" 安全组规则在但探测丢失(404→empty);</li>
 *   <li>404 语义经真实 HTTP 传递:未知资源读路径返回 empty,不抛异常不伪装。</li>
 * </ol>
 *
 * <p>环境说明:dolphin.sim.* 延迟同时驱动 Mock 云端点(镜像模拟器参数),这里缩短到 500ms 加速测试;
 * spring.ai 指向必拒绝端口 + 单次重试,让 RAG 种子灌入离线快速失败(仅 WARN,不阻塞上下文);
 * 固定端口 18765 避开 ${local.server.port} 在单例绑定期不可用的问题,若本机占用请改端口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18765",
        "dolphin.cloud.provider=http",
        "dolphin.cloud.base-url=http://localhost:18765/__mock",
        "dolphin.sim.restart-latency=PT0.5S",
        "dolphin.sim.rule-propagation=PT0.5S",
        "dolphin.sim.refund-record-latency=PT0.5S",
        "spring.ai.openai.api-key=offline-it-dummy",
        "spring.ai.openai.base-url=http://127.0.0.1:1",
        "spring.ai.retry.max-attempts=1"
})
class HttpCloudApiIntegrationTest {

    /** 延迟参数 0.5s,统一等待 1.4s 保证状态迁移完成(留足 CI 抖动余量) */
    private static final long EFFECTIVE_WAIT_MILLIS = 1400;

    @Autowired
    private CloudApi cloudApi;

    @Test
    void context_wiresHttpCloudApi() {
        assertThat(cloudApi).isInstanceOf(HttpCloudApi.class);
    }

    @Test
    void restart_generationIncrementsImmediately_stateLags() throws InterruptedException {
        // 基线:变更前的 RUNNING 快照(generation=0)就是"残值"——只看 state 会误判
        CloudApi.InstanceSnapshot before = cloudApi.getInstanceSnapshot("it-restart-1");
        assertThat(before.state()).isEqualTo("RUNNING");
        assertThat(before.restartGeneration()).isZero();

        String receipt = cloudApi.restartInstance("it-restart-1");
        assertThat(receipt).contains("已向云平台提交");

        // 受理即递增(无竞态窗口):独立读路径必须看到 generation=1 才算"本次变更"
        CloudApi.InstanceSnapshot accepted = cloudApi.getInstanceSnapshot("it-restart-1");
        assertThat(accepted.restartGeneration()).isEqualTo(1);

        Thread.sleep(EFFECTIVE_WAIT_MILLIS);
        CloudApi.InstanceSnapshot after = cloudApi.getInstanceSnapshot("it-restart-1");
        assertThat(after.state()).isEqualTo("RUNNING");
        assertThat(after.restartGeneration()).isEqualTo(1); // 序号不因状态迁移而变
    }

    @Test
    void restart_stuckInstanceEventuallyFailed() throws InterruptedException {
        cloudApi.restartInstance("it-stuck-1");
        Thread.sleep(EFFECTIVE_WAIT_MILLIS);
        CloudApi.InstanceSnapshot snapshot = cloudApi.getInstanceSnapshot("it-stuck-1");
        assertThat(snapshot.state()).isEqualTo("FAILED");
        assertThat(snapshot.restartGeneration()).isEqualTo(1);
    }

    @Test
    void refund_reconciliationIsIndependentOfReceipt() throws InterruptedException {
        // 受理成功 ≠ 可对账:未落账前独立读路径必须 empty
        String receipt = cloudApi.refundOrder("it-refund-1");
        assertThat(receipt).contains("已受理");
        assertThat(cloudApi.queryRefundRecord("it-refund-1")).isEmpty();

        Thread.sleep(EFFECTIVE_WAIT_MILLIS);
        Optional<CloudApi.RefundRecord> record = cloudApi.queryRefundRecord("it-refund-1");
        assertThat(record).isPresent();
        assertThat(record.get().serialNo()).startsWith("RF-");
        assertThat(record.get().acceptedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void refund_lostOrder_acceptedButLedgerNeverVisible() throws InterruptedException {
        String receipt = cloudApi.refundOrder("it-lost-order");
        assertThat(receipt).contains("已受理");
        Thread.sleep(EFFECTIVE_WAIT_MILLIS);
        assertThat(cloudApi.queryRefundRecord("it-lost-order")).isEmpty();
    }

    @Test
    void securityGroup_versionMonotonic_probeGatedByEffectiveness() throws InterruptedException {
        assertThat(cloudApi.querySecurityGroupRule("it-sg", 18080)).isEmpty();

        String receipt = cloudApi.authorizeSecurityGroup("it-sg", 18080);
        assertThat(receipt).contains("放行 TCP 18080");

        // 受理即返回规则(带 version 供验证比对),但生效前探测端点必须解析不到
        Optional<CloudApi.SecurityGroupRule> accepted = cloudApi.querySecurityGroupRule("it-sg", 18080);
        assertThat(accepted).isPresent();
        long version = accepted.get().version();
        assertThat(version).isPositive(); // version 为跨安全组全局单调序号,具体取值取决于历史下发次数
        assertThat(cloudApi.probeEndpoint("it-sg", 18080)).isEmpty();

        Thread.sleep(EFFECTIVE_WAIT_MILLIS);
        Optional<InetSocketAddress> endpoint = cloudApi.probeEndpoint("it-sg", 18080);
        assertThat(endpoint).isPresent();
        assertThat(endpoint.get().getPort()).isPositive();

        // 再次下发:version 单调递增,可区分"本次规则"与"变更前已存在的旧规则"
        cloudApi.authorizeSecurityGroup("it-sg", 18080);
        assertThat(cloudApi.querySecurityGroupRule("it-sg", 18080).orElseThrow().version())
                .isGreaterThan(version);
    }

    @Test
    void securityGroup_voidMarked_ruleAcceptedButProbeLost() throws InterruptedException {
        cloudApi.authorizeSecurityGroup("void-it-sg", 18081);
        Thread.sleep(EFFECTIVE_WAIT_MILLIS);
        assertThat(cloudApi.querySecurityGroupRule("void-it-sg", 18081)).isPresent();
        assertThat(cloudApi.probeEndpoint("void-it-sg", 18081)).isEmpty();
    }

    @Test
    void unknownResources_readPathsReturnEmpty_notExceptions() {
        assertThat(cloudApi.queryRefundRecord("it-unknown-order")).isEmpty();
        assertThat(cloudApi.querySecurityGroupRule("it-unknown-sg", 1)).isEmpty();
        assertThat(cloudApi.probeEndpoint("it-unknown-sg", 1)).isEmpty();
    }
}
