package com.dolphin.ticket.service;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;

/**
 * 云服务 OpenAPI 契约(数据源可插拔抽象)。
 *
 * <p>用途:让"提交写操作 / 独立读路径核对"这件事与具体数据来源解耦——
 * <ul>
 *   <li>{@link CloudApiService}:进程内模拟器,忠实模拟"控制面受理 ≠ 数据面生效",
 *       供 offline 演示与单测使用(默认实现);</li>
 *   <li>{@code HttpCloudApi}:HTTP 实现(骨架已就绪,见 {@code CloudApiConfig}),
 *       通过 {@code dolphin.cloud.provider=http} 切换,接入真实云厂商/内部平台网关;</li>
 *   <li>后续可再扩展云厂商 SDK 实现,只需实现本接口,执行器与验证器零改动。</li>
 * </ul>
 *
 * <p><b>接口设计约定(实现方必须遵守)</b>:
 * <ol>
 *   <li>读方法必须走<b>独立读路径</b>,不得回显写方法提交时的返回值(受理≠完成的前提);</li>
 *   <li>读方法返回机器可判定结构({@link InstanceSnapshot}/{@link SecurityGroupRule}/{@link RefundRecord}),
 *       其中 {@code restartGeneration}/{@code version}/{@code serialNo} 必须能区分"本次变更"与"变更前残值";</li>
 *   <li>查询类方法查不到时返回 {@link Optional#empty()},不得抛异常伪装"已生效";</li>
 *   <li>写方法只返回受理回执文本,真正是否生效由 {@code ResultVerifier} 轮询/探测/对账判定。</li>
 * </ol>
 */
public interface CloudApi {

    /**
     * 实例状态快照:state 为机器可判定值(RUNNING/STARTING/FAILED),detail 面向人,
     * restartGeneration 为重启序号(每次受理 +1),供结果验证区分"本次重启后的新状态"与"变更前残值"。
     */
    record InstanceSnapshot(String state, String detail, long restartGeneration) {
    }

    /** 退款受理记录(对账数据源:只证明"已受理",资金到账另计) */
    record RefundRecord(String serialNo, Instant acceptedAt) {
    }

    /**
     * 安全组规则:effectiveAt 为生效时刻,version 为单调递增的下发序号,
     * 供结果验证区分"本次下发的规则"与"变更前已存在的旧规则"。
     */
    record SecurityGroupRule(Instant effectiveAt, long version) {
    }

    /** 查询实例运行状态(人读文本,供 LLM 只读工具) */
    String getInstanceStatus(String instanceId);

    /** 查询实例状态快照(机器可判定,供结果验证轮询) */
    InstanceSnapshot getInstanceSnapshot(String instanceId);

    /** 查询实例监控指标(人读文本,供 LLM 只读工具) */
    String getMetrics(String instanceId, String metricName);

    /** 重启实例——写操作,只允许审批通过后由 ActionExecutor 调用 */
    String restartInstance(String instanceId);

    /** 订单退款——写操作,只允许审批通过后由 ActionExecutor 调用 */
    String refundOrder(String orderId);

    /** 安全组放行端口——写操作,审批要求由动作描述符声明 */
    String authorizeSecurityGroup(String securityGroupId, int port);

    /** 对账查询退款受理流水(独立读路径,不信任提交返回值) */
    Optional<RefundRecord> queryRefundRecord(String orderId);

    /** 查询安全组规则快照(独立读路径,供验证比对提交前后规则版本) */
    Optional<SecurityGroupRule> querySecurityGroupRule(String securityGroupId, int port);

    /** 解析探测端点:外部 TCP 连通性验证的目标地址;规则未生效/不存在返回 empty */
    Optional<InetSocketAddress> probeEndpoint(String securityGroupId, int port);
}
