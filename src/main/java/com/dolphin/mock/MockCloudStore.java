package com.dolphin.mock;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * Mock 云端点的内存状态机:扮演"远端云控制面",行为与进程内模拟器
 * {@link com.dolphin.ticket.service.CloudApiService} 逐条镜像——
 * provider=http 的数据源(S2-4 HttpCloudApi)与全链路集成测试(S2-6)通过 HTTP 访问本状态机,
 * 观察到的"控制面受理 ≠ 数据面生效"语义必须与 provider=sim 完全一致,保证切换数据源时
 * 上层(执行器/验证器)无需任何改动。
 *
 * <p>模拟行为(延迟复用 {@code dolphin.sim.*} 配置,与模拟器同一套参数,测试缩短延迟时两端同时加速):
 * <ul>
 *   <li>重启:受理即 restartGeneration+1 并转 STARTING,经过 restart-latency 转 RUNNING;
 *       实例 ID 含 "stuck" 模拟启动失败(终态 FAILED),含 "hang" 模拟夯住(永远 STARTING);</li>
 *   <li>安全组:规则受理即返回(带单调递增 version),经过 rule-propagation 才生效;生效后在本机
 *       回环地址建立真实监听端口,PROBE 解析返回该端点供真实 TCP 连通性验证;
 *       安全组 ID 含 "void" 模拟规则下发丢失(探测永远 404);</li>
 *   <li>退款:受理即生成 serialNo 流水,经过 refund-record-latency 才对外可查(异步落账);
 *       订单 ID 含 "lost" 模拟落账丢失(对账永远 404)。</li>
 * </ul>
 *
 * <p><b>404 语义 = 资源不存在/不可见</b>,仅用于独立读路径(退款流水/规则查询/探测端点),
 * Controller 层据此把 404 映射给调用方;实例快照始终 200——与模拟器"未知实例 = RUNNING、
 * generation 0"一致,快照接口本身无 Optional 语义。
 */
@Component
public class MockCloudStore {

    /** 实例状态快照(state 为机器可判定值) */
    public record Snapshot(String state, String detail, long restartGeneration) {
    }

    /** 退款受理流水(acceptedAt 之后对外可查,模拟异步落账) */
    public record RefundEntry(String serialNo, Instant acceptedAt) {
    }

    /** 安全组规则(version 为单调递增的下发序号) */
    public record RuleEntry(Instant effectiveAt, long version) {
    }

    private final Duration restartLatency;
    private final Duration rulePropagation;
    private final Duration refundRecordLatency;

    /** 重启受理表:instanceId → 预期生效时刻 */
    private final Map<String, Instant> restartingSince = new ConcurrentHashMap<>();
    /** 重启受理序号:instanceId → 次数,验证据此拒绝变更前的旧 RUNNING(防假阳性) */
    private final Map<String, Long> restartGenerations = new ConcurrentHashMap<>();
    /** 退款受理流水:orderId → 记录 */
    private final Map<String, RefundEntry> refundLedger = new ConcurrentHashMap<>();
    /** 安全组规则表:sgId → (port → 规则) */
    private final Map<String, Map<Integer, RuleEntry>> securityGroupRules = new ConcurrentHashMap<>();
    /** 规则下发全局序号:每次下发递增 */
    private final AtomicLong ruleVersionSeq = new AtomicLong();
    /** 模拟数据面监听器:key = "sgId|port"(规则生效后按需建立) */
    private final Map<String, ServerSocket> dataplaneListeners = new ConcurrentHashMap<>();

    /** 延迟参数复用 {@code dolphin.sim.*}:与模拟器同一套配置,两端语义与节奏保持一致 */
    public MockCloudStore(@Value("${dolphin.sim.restart-latency:PT3S}") Duration restartLatency,
                          @Value("${dolphin.sim.rule-propagation:PT1S}") Duration rulePropagation,
                          @Value("${dolphin.sim.refund-record-latency:PT1S}") Duration refundRecordLatency) {
        this.restartLatency = restartLatency;
        this.rulePropagation = rulePropagation;
        this.refundRecordLatency = refundRecordLatency;
    }

    /** 实例快照:状态迁移与 generation 逻辑镜像 {@link com.dolphin.ticket.service.CloudApiService#getInstanceSnapshot} */
    public Snapshot snapshot(String instanceId) {
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
            case "STARTING" -> "实例 " + instanceId + " 状态:STARTING,重启进行中(mock 云端点数据)";
            case "FAILED" -> "实例 " + instanceId + " 状态:FAILED,启动失败(mock 云端点数据)";
            default -> "实例 " + instanceId + " 状态:RUNNING,CPU 平均负载 92%,内存占用 88%(mock 云端点数据)";
        };
        return new Snapshot(state, detail, restartGenerations.getOrDefault(instanceId, 0L));
    }

    /** 重启受理:generation 受理即 +1,状态迁移延迟(受理≠生效) */
    public String reboot(String instanceId) {
        restartingSince.put(instanceId, Instant.now().plus(restartLatency));
        restartGenerations.merge(instanceId, 1L, Long::sum);
        return "已向云平台提交实例 " + instanceId + " 的重启指令,预计 " + restartLatency.toSeconds() + "s 内生效";
    }

    /** 实例监控指标(mock 数据,与模拟器同文本) */
    public String metrics(String instanceId, String metricName) {
        return "实例 " + instanceId + " 指标 " + metricName + ":最近 1 小时持续高于阈值 85%(mock 数据)";
    }

    /** 退款受理:生成 serialNo 流水,落账延迟后才可查 */
    public String refund(String orderId) {
        RefundEntry entry = new RefundEntry("RF-" + UUID.randomUUID().toString().substring(0, 8),
                Instant.now().plus(refundRecordLatency));
        refundLedger.put(orderId, entry);
        return "订单 " + orderId + " 退款申请已受理,流水号 " + entry.serialNo() + "(3-5 个工作日原路退回)";
    }

    /** 退款流水对账:未受理/未落账/落账丢失(ID 含 "lost")返回 empty → Controller 映射 404 */
    public Optional<RefundEntry> latestRefund(String orderId) {
        RefundEntry entry = refundLedger.get(orderId);
        if (entry == null || isLost(orderId)) {
            return Optional.empty();
        }
        return Instant.now().isBefore(entry.acceptedAt()) ? Optional.empty() : Optional.of(entry);
    }

    /** 安全组放行端口:受理即返回(version 单调递增),生效延迟 */
    public String authorize(String securityGroupId, int port) {
        securityGroupRules.computeIfAbsent(securityGroupId, k -> new ConcurrentHashMap<>())
                .put(port, new RuleEntry(Instant.now().plus(rulePropagation), ruleVersionSeq.incrementAndGet()));
        return "已向安全组 " + securityGroupId + " 添加规则:放行 TCP " + port;
    }

    /** 规则查询:已受理未生效也返回(version 供验证比对),不存在返回 empty → Controller 映射 404 */
    public Optional<RuleEntry> rule(String securityGroupId, int port) {
        Map<Integer, RuleEntry> rules = securityGroupRules.get(securityGroupId);
        if (rules == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(rules.get(port));
    }

    /**
     * 探测端点解析:规则生效后返回模拟数据面的真实监听地址(本机回环 + 临时端口);
     * 规则不存在/未生效/下发丢失(ID 含 "void")返回 empty → Controller 映射 404。
     */
    public Optional<InetSocketAddress> probe(String securityGroupId, int port) {
        Map<Integer, RuleEntry> rules = securityGroupRules.get(securityGroupId);
        if (rules == null || isVoid(securityGroupId)) {
            return Optional.empty();
        }
        RuleEntry rule = rules.get(port);
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

    // ---- 故障注入标记(与模拟器同一套,演示/测试失败路径用) ----

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
