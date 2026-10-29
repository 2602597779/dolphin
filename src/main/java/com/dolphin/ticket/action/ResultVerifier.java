package com.dolphin.ticket.action;

import com.dolphin.ticket.service.CloudApi;
import com.dolphin.ticket.service.CloudApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;

/**
 * 写操作结果验证器:实现 {@link VerifyMode} 三种验证范式的<b>真实等待与判定</b>。
 *
 * <p>核心原则:不信任提交返回值(受理≠完成),通过独立读路径核对实际效果;
 * 并且不能只看"当前值是否等于目标值",还必须证明"本次变更真的发生"——
 * 提交前用 {@link #capture} 采样基线(重启序号/规则版本/旧流水号),
 * 验证阶段要求观测到基线之后的变化,避免控制面状态刷新滞后或旧值造成的假阳性。
 *
 * <p>容忍时长取描述符声明的 {@link ActionDescriptor#verifyTimeout()},期间按 1.5 倍指数退避轮询:
 * <ul>
 *   <li>{@link VerifyMode#POLL_STATUS}:轮询实例状态,必须看到本次重启后的新世代 RUNNING;
 *       观测到 FAILED 终态立即判负,不等满超时;</li>
 *   <li>{@link VerifyMode#PROBE}:外部真实 TCP 连通性探测,且必须确认本次新下发的规则版本,
 *       防止变更前旧规则已放行造成的误判;</li>
 *   <li>{@link VerifyMode#RECONCILE}:轮询对账源,只认基线之后的新受理流水,旧流水不重复计入。</li>
 * </ul>
 *
 * <p>验证失败返回 verified=false,由执行器收敛为 VERIFY_FAILED 终态,图编排路由到转人工;
 * 轮询线程被中断时同样收敛为失败并恢复中断标志。
 */
@Component
public class ResultVerifier {

    /** 退避上限,避免长容忍时长动作把间隔拖得过长 */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    /** 验证结论:verified=false 时 detail 说明失败原因(面向结单/转人工) */
    public record Outcome(boolean verified, String detail) {}

    /**
     * 提交前基线:证明"本次变更真的发生"的比对锚点(受理≠完成的第二道防线)。
     *
     * <p>只校验终态会假阳性:实例可能在受理后仍返回变更前的 RUNNING,
     * 退款可能查到同一订单的历史流水,安全组可能早就有旧规则放行同一端口。
     * 因此按范式记录对应读数,验证时必须观测到基线之后的变化。
     */
    public record Baseline(long instanceGeneration, String refundSerialNo, long ruleVersion) {

        public static final Baseline EMPTY = new Baseline(0L, null, 0L);

        /** 面向审计日志的摘要 */
        public String summary() {
            return "restartGeneration=" + instanceGeneration
                    + " ruleVersion=" + ruleVersion
                    + " refundSerial=" + (refundSerialNo == null ? "无" : refundSerialNo);
        }
    }

    private final CloudApi cloudApiService;
    private final Duration pollInterval;
    private final Duration probeConnectTimeout;

    @Autowired
    public ResultVerifier(@Value("${dolphin.verify.poll-interval:PT2S}") Duration pollInterval,
                          @Value("${dolphin.verify.probe-connect-timeout:PT3S}") Duration probeConnectTimeout,
                          CloudApi cloudApiService) {
        this.pollInterval = pollInterval;
        this.probeConnectTimeout = probeConnectTimeout;
        this.cloudApiService = cloudApiService;
    }

    /** 测试用快速参数构造器(毫秒级轮询间隔) */
    public ResultVerifier() {
        this(Duration.ofMillis(30), Duration.ofMillis(500), new CloudApiService());
    }

    /**
     * 采样提交前基线:必须在提交指令<b>之前</b>调用,否则基线已被本次变更污染,验证将失去意义。
     * 端口等参数非法时返回 {@link Baseline#EMPTY},由 {@link #verify} 给出明确的失败原因。
     */
    public Baseline capture(ActionDescriptor desc, ActionProposal proposal) {
        return switch (desc.verifyMode()) {
            case POLL_STATUS -> new Baseline(
                    cloudApiService.getInstanceSnapshot(proposal.target()).restartGeneration(), null, 0L);
            case PROBE -> {
                Integer port = portOf(proposal);
                yield port == null ? Baseline.EMPTY
                        : new Baseline(0L, null,
                        cloudApiService.querySecurityGroupRule(proposal.target(), port)
                                .map(CloudApi.SecurityGroupRule::version).orElse(0L));
            }
            case RECONCILE -> new Baseline(0L,
                    cloudApiService.queryRefundRecord(proposal.target())
                            .map(CloudApi.RefundRecord::serialNo).orElse(null), 0L);
            case NONE -> Baseline.EMPTY;
        };
    }

    /** 按描述符声明的范式与容忍时长执行验证;超时/终态失败/中断均判负 */
    public Outcome verify(ActionDescriptor desc, ActionProposal proposal, Baseline baseline) {
        Instant deadline = Instant.now().plus(desc.verifyTimeout());
        return switch (desc.verifyMode()) {
            case POLL_STATUS -> pollInstanceStatus(proposal.target(), baseline, deadline);
            case PROBE -> {
                Integer port = portOf(proposal);
                yield port == null
                        ? new Outcome(false, "端口参数非法,无法探测: " + proposal.params().get("port"))
                        : probeConnectivity(proposal.target(), port, baseline, deadline);
            }
            case RECONCILE -> reconcileRefund(proposal.target(), baseline, deadline);
            case NONE -> new Outcome(true, "无需验证");
        };
    }

    /** POLL_STATUS:必须观测到本次重启后的新世代,再判 RUNNING;FAILED 终态立即判负 */
    private Outcome pollInstanceStatus(String instanceId, Baseline baseline, Instant deadline) {
        long attempt = 0;
        Duration interval = pollInterval;
        Instant start = Instant.now();
        while (true) {
            attempt++;
            CloudApi.InstanceSnapshot snapshot = cloudApiService.getInstanceSnapshot(instanceId);
            String state = snapshot.state();
            long generation = snapshot.restartGeneration();
            // 先证明本次受理已落到新世代:旧世代的 RUNNING 只是变更前残值,不能算完成
            if (generation > baseline.instanceGeneration()) {
                if ("RUNNING".equals(state)) {
                    return new Outcome(true, "第" + attempt + "次轮询观测到本次重启(序号 "
                            + baseline.instanceGeneration() + "→" + generation + ")后状态 RUNNING,耗时 "
                            + Duration.between(start, Instant.now()).toSeconds() + "s");
                }
                if ("FAILED".equals(state)) {
                    return new Outcome(false, "第" + attempt + "次轮询观测到本次重启后终态 FAILED(启动失败),停止等待");
                }
            }
            if (Instant.now().plus(interval).isAfter(deadline)) {
                return new Outcome(false, "轮询超时:容忍时长内未观测到本次重启完成(末次状态 " + state
                        + ",重启序号 " + generation + ",基线 " + baseline.instanceGeneration() + ")");
            }
            sleep(interval);
            interval = backoff(interval);
        }
    }

    /** PROBE:外部真实 TCP 探测 + 本次新下发规则版本核对;端点未就绪/旧规则均退避重试 */
    private Outcome probeConnectivity(String securityGroupId, int port, Baseline baseline, Instant deadline) {
        long attempt = 0;
        Duration interval = pollInterval;
        String lastError = "端点未就绪(规则未生效)";
        while (true) {
            attempt++;
            long version = cloudApiService.querySecurityGroupRule(securityGroupId, port)
                    .map(CloudApi.SecurityGroupRule::version).orElse(0L);
            InetSocketAddress endpoint = cloudApiService.probeEndpoint(securityGroupId, port).orElse(null);
            if (endpoint != null) {
                try (Socket socket = new Socket()) {
                    socket.connect(endpoint, (int) probeConnectTimeout.toMillis());
                    // 能连通还不够:必须确认本次新下发的规则版本,否则可能是变更前旧规则造成的假阳性
                    if (version > baseline.ruleVersion()) {
                        return new Outcome(true, "外部探测 " + endpoint.getHostString() + ":" + endpoint.getPort()
                                + " 连通成功(第" + attempt + "次尝试,观测到本次新规则版本 "
                                + baseline.ruleVersion() + "→" + version + ")");
                    }
                    lastError = "端口已连通但未观测到本次新下发的规则(规则版本仍为 " + version + ")";
                } catch (IOException e) {
                    lastError = "连接失败:" + e.getMessage();
                }
            }
            if (Instant.now().plus(interval).isAfter(deadline)) {
                return new Outcome(false, "外部探测超时:目标端口 " + port
                        + " 在容忍时长内未达成本次变更(" + lastError + ")");
            }
            sleep(interval);
            interval = backoff(interval);
        }
    }

    /** RECONCILE:只认基线之后的新受理流水;旧流水重复出现不算完成,资金到账仍异步跟进 */
    private Outcome reconcileRefund(String orderId, Baseline baseline, Instant deadline) {
        long attempt = 0;
        Duration interval = pollInterval;
        String lastSeen = "无记录";
        while (true) {
            attempt++;
            CloudApi.RefundRecord record = cloudApiService.queryRefundRecord(orderId).orElse(null);
            if (record != null) {
                if (baseline.refundSerialNo() == null || !baseline.refundSerialNo().equals(record.serialNo())) {
                    return new Outcome(true, "对账确认:订单 " + orderId + " 新的受理流水号 " + record.serialNo()
                            + "(基线 " + baselineText(baseline.refundSerialNo())
                            + ";资金到账仍异步跟进,不在本单等待范围)");
                }
                lastSeen = "仅查到变更前旧流水 " + record.serialNo();
            }
            if (Instant.now().plus(interval).isAfter(deadline)) {
                return new Outcome(false, "对账失败:容忍时长内未观测到订单 " + orderId + " 的新受理流水("
                        + lastSeen + ",基线 " + baselineText(baseline.refundSerialNo()) + ")");
            }
            sleep(interval);
            interval = backoff(interval);
        }
    }

    /** 1.5 倍指数退避,封顶 {@link #MAX_BACKOFF} */
    private Duration backoff(Duration current) {
        Duration next = current.multipliedBy(3).dividedBy(2);
        return next.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : next;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("验证轮询被中断", e);
        }
    }

    private String baselineText(String serialNo) {
        return serialNo == null ? "无" : serialNo;
    }

    /** 解析端口参数;非法值返回 null,由调用方收敛为验证失败而不是抛异常冲垮链路 */
    private Integer portOf(ActionProposal proposal) {
        try {
            return Integer.parseInt(String.valueOf(proposal.params().getOrDefault("port", "443")));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
