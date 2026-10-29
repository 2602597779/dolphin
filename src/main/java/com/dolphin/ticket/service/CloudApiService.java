package com.dolphin.ticket.service;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 云服务 OpenAPI 门面:骨架阶段为进程内控制面/数据面<b>模拟器</b>,接口语义与真实云 API 一致,
 * 并忠实模拟"控制面受理 ≠ 数据面生效"的异步特性,使结果验证(轮询/探测/对账)观察到的是
 * 真实的延迟与状态迁移,而不是提交方的一句承诺。
 *
 * <p>模拟行为(延迟由配置控制,测试可注入毫秒级快速参数):
 * <ul>
 *   <li>重启:受理后实例进入 STARTING,经过 {@code dolphin.sim.restart-latency} 才转 RUNNING;
 *       实例 ID 含 "stuck" 模拟启动失败(终态 FAILED),含 "hang" 模拟夯住(永不就绪,验证超时);</li>
 *   <li>安全组:规则下发后经过 {@code dolphin.sim.rule-propagation} 才生效;生效后模拟数据面在本机
 *       回环地址建立真实监听端口,{@link #probeEndpoint} 返回该端点供外部探测做真实 TCP 连通性验证;
 *       安全组 ID 含 "void" 模拟规则下发丢失(探测超时);</li>
 *   <li>退款:受理流水号经过 {@code dolphin.sim.refund-record-latency} 才可查到(异步落账);
 *       订单 ID 含 "lost" 模拟落账丢失(对账超时)。</li>
 * </ul>
 *
 * <p>本类实现 {@link CloudApi} 契约,是<b>默认(provider=sim)数据源</b>:offline 演示与单测依赖它;
 * 接真实云 API 时实现同一契约并通过 {@code dolphin.cloud.provider=http} 切换,执行器/验证器零改动。
 *
 * <p><b>本类不是 Spring 组件</b>:Bean 由 {@link CloudApiConfig} 按 {@code dolphin.cloud.provider}
 * 装配(默认 sim),延迟参数 {@code dolphin.sim.*} 也在那里注入;单测直接 {@code new CloudApiService()} 即可。
 *
 * <p>真实实现应替换为各云厂商 SDK 签名调用;写操作(restart/refund)真实接入前必须走审批链路,
 * 禁止绕过 approval 节点直接调用。
 * TODO:接入真实云 API,并补充超时、重试、限流与凭证管理(凭证只从环境变量/密钥服务读取)。
 */
public class CloudApiService implements CloudApi {

    private final Duration restartLatency;
    private final Duration rulePropagation;
    private final Duration refundRecordLatency;

    /** 重启受理表:instanceId → 预期生效时刻(模拟控制面异步生效) */
    private final Map<String, Instant> restartingSince = new ConcurrentHashMap<>();
    /** 重启受理序号:instanceId → 次数,验证据此拒绝变更前的旧 RUNNING(防假阳性) */
    private final Map<String, Long> restartGenerations = new ConcurrentHashMap<>();
    /** 退款受理流水:orderId → 记录(acceptedAt 之后对外可查) */
    private final Map<String, RefundRecord> refundLedger = new ConcurrentHashMap<>();
    /** 安全组规则表:sgId → (port → 规则快照) */
    private final Map<String, Map<Integer, SecurityGroupRule>> securityGroupRules = new ConcurrentHashMap<>();
    /** 规则下发全局序号:每次下发递增,供验证比对本次下发前后的规则版本 */
    private final AtomicLong ruleVersionSeq = new AtomicLong();
    /** 模拟数据面监听器:key = "sgId|port"(规则生效后按需建立,代表受保护实例上的服务端口) */
    private final Map<String, ServerSocket> dataplaneListeners = new ConcurrentHashMap<>();

    /** 生产用构造器:延迟由 {@link CloudApiConfig} 从 {@code dolphin.sim.*} 注入 */
    public CloudApiService(Duration restartLatency, Duration rulePropagation, Duration refundRecordLatency) {
        this.restartLatency = restartLatency;
        this.rulePropagation = rulePropagation;
        this.refundRecordLatency = refundRecordLatency;
    }

    /** 测试用快速参数构造器(毫秒级延迟,加速单元测试) */
    public CloudApiService() {
        this(Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMillis(50));
    }

    /** 查询实例运行状态(人读文本,供 LLM 只读工具) */
    @Override
    public String getInstanceStatus(String instanceId) {
        return getInstanceSnapshot(instanceId).detail();
    }

    /** 查询实例状态快照(机器可判定,供结果验证轮询) */
    @Override
    public InstanceSnapshot getInstanceSnapshot(String instanceId) {
        String state = "RUNNING";
        Instant readyAt = restartingSince.get(instanceId);
        if (readyAt != null) {
            if (Instant.now().isBefore(readyAt)) {
                state = "STARTING";
            } else if (isStuck(instanceId)) {
                state = "FAILED";
            } else if (isHang(instanceId)) {
                state = "STARTING";
            }
        }
        String detail = switch (state) {
            case "STARTING" -> "实例 " + instanceId + " 状态:STARTING,重启进行中(模拟控制面数据)";
            case "FAILED" -> "实例 " + instanceId + " 状态:FAILED,启动失败(模拟控制面数据)";
            default -> "实例 " + instanceId + " 状态:RUNNING,CPU 平均负载 92%,内存占用 88%(模拟控制面数据)";
        };
        return new InstanceSnapshot(state, detail, restartGenerations.getOrDefault(instanceId, 0L));
    }

    /** 查询实例监控指标(mock) */
    @Override
    public String getMetrics(String instanceId, String metricName) {
        // TODO: 替换为真实云 API DescribeMetricList
        return "实例 " + instanceId + " 指标 " + metricName + ":最近 1 小时持续高于阈值 85%(mock 数据)";
    }

    /** 重启实例——写操作,只允许审批通过后由 execute 节点调用 */
    @Override
    public String restartInstance(String instanceId) {
        // TODO: 替换为真实云 API RebootInstance(带 ClientToken 幂等)
        restartingSince.put(instanceId, Instant.now().plus(restartLatency));
        restartGenerations.merge(instanceId, 1L, Long::sum);
        return "已向云平台提交实例 " + instanceId + " 的重启指令,预计 " + restartLatency.toSeconds() + "s 内生效";
    }

    /** 订单退款——写操作,只允许审批通过后由 execute 节点调用 */
    @Override
    public String refundOrder(String orderId) {
        // TODO: 替换为真实计费/订单系统接口
        RefundRecord record = new RefundRecord("RF-" + UUID.randomUUID().toString().substring(0, 8),
                Instant.now().plus(refundRecordLatency));
        refundLedger.put(orderId, record);
        return "订单 " + orderId + " 退款申请已受理,流水号 " + record.serialNo() + "(3-5 个工作日原路退回)";
    }

    /**
     * 对账查询:退款受理流水(acceptedAt 之后可见,模拟异步落账;订单 ID 含 "lost" 模拟落账丢失)。
     * 对账走独立读路径,不信任 {@link #refundOrder} 的提交返回值。
     */
    @Override
    public Optional<RefundRecord> queryRefundRecord(String orderId) {
        RefundRecord record = refundLedger.get(orderId);
        if (record == null || isLost(orderId)) {
            return Optional.empty();
        }
        return Instant.now().isBefore(record.acceptedAt()) ? Optional.empty() : Optional.of(record);
    }

    /** 安全组放行端口——低风险写操作,由 ActionExecutor 按注册表策略调用 */
    @Override
    public String authorizeSecurityGroup(String securityGroupId, int port) {
        // TODO: 替换为真实云 API AuthorizeSecurityGroup
        securityGroupRules.computeIfAbsent(securityGroupId, k -> new ConcurrentHashMap<>())
                .put(port, new SecurityGroupRule(Instant.now().plus(rulePropagation),
                        ruleVersionSeq.incrementAndGet()));
        return "已向安全组 " + securityGroupId + " 添加规则:放行 TCP " + port;
    }

    /** 查询安全组规则快照(独立读路径,供验证比对提交前后规则版本;不信任提交返回值) */
    @Override
    public Optional<SecurityGroupRule> querySecurityGroupRule(String securityGroupId, int port) {
        Map<Integer, SecurityGroupRule> rules = securityGroupRules.get(securityGroupId);
        if (rules == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(rules.get(port));
    }

    /**
     * 解析探测端点:规则生效后返回模拟数据面的真实监听地址(本机回环 + 临时端口),
     * 供外部探测做真实 TCP 连通性验证;规则不存在/未生效/下发丢失(ID 含 "void")返回 empty。
     */
    @Override
    public Optional<InetSocketAddress> probeEndpoint(String securityGroupId, int port) {
        Map<Integer, SecurityGroupRule> rules = securityGroupRules.get(securityGroupId);
        if (rules == null || isVoid(securityGroupId)) {
            return Optional.empty();
        }
        SecurityGroupRule rule = rules.get(port);
        if (rule == null || Instant.now().isBefore(rule.effectiveAt())) {
            return Optional.empty();
        }
        ServerSocket listener = dataplaneListeners.computeIfAbsent(
                securityGroupId + "|" + port, k -> bindEphemeralLoopback());
        return Optional.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
    }

    private ServerSocket bindEphemeralLoopback() {
        try {
            return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 释放模拟数据面监听器 */
    @PreDestroy
    void shutdown() {
        dataplaneListeners.values().forEach(s -> {
            try {
                s.close();
            } catch (IOException ignored) {
                // 关闭失败不影响停机
            }
        });
    }

    // ---- 故障注入标记(演示/测试失败路径用) ----

    private boolean isStuck(String id) {
        return id != null && id.contains("stuck");
    }

    private boolean isHang(String id) {
        return id != null && id.contains("hang");
    }

    private boolean isVoid(String id) {
        return id != null && id.contains("void");
    }

    private boolean isLost(String id) {
        return id != null && id.contains("lost");
    }
}
