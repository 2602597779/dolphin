package com.dolphin.ticket.service;

import com.dolphin.cloud.CloudApiProperties;
import com.dolphin.ticket.service.HttpTransport.HttpCall;
import com.dolphin.ticket.service.HttpTransport.HttpResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * {@link CloudApi} 的 HTTP 数据源实现:把契约方法<b>纯映射</b>为对
 * {@code dolphin.cloud.base-url} 的 REST 调用,路径模板来自 {@code dolphin.cloud.paths}——
 * 换网关/换 API 版本/换鉴权方式全部只改配置,本类不改。
 *
 * <p>职责边界(实现必须保持):
 * <ul>
 *   <li>本类只做映射与状态码语义判定;建连/超时/鉴权/重试在 {@link HttpTransport} 实现层;</li>
 *   <li><b>写方法(POST)单次发出,绝不自动重试</b>:失败显式抛异常,是否重提由人工/审批决策
 *       (clientToken 幂等由受理方保证,提交方盲目重试会造成重复重启/重复退款);</li>
 *   <li>独立读路径({@code queryRefundRecord}/{@code querySecurityGroupRule}/{@code probeEndpoint})
 *       遇 404 返回 {@link Optional#empty}(资源不存在/未生效的契约语义);
 *       其余非 2xx 一律抛异常——<b>显式失败优于静默用错数据源</b>,禁止把错误伪装成查询结果;</li>
 *   <li>读方法走独立 GET,不回显写方法提交时的返回值(受理≠生效的前提)。</li>
 * </ul>
 *
 * <p>本类不是 Spring 组件:装配在 {@code CloudApiConfig} 按 {@code dolphin.cloud.provider=http}
 * 分支完成(见 RestCloudTransport 步骤);单测直接 new + fake transport 替身。
 */
public class HttpCloudApi implements CloudApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpTransport transport;
    private final CloudApiProperties properties;

    public HttpCloudApi(HttpTransport transport, CloudApiProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    // ---- 只读方法(供 LLM 只读工具 / 结果验证轮询) ----

    @Override
    public String getInstanceStatus(String instanceId) {
        return getInstanceSnapshot(instanceId).detail();
    }

    @Override
    public InstanceSnapshot getInstanceSnapshot(String instanceId) {
        HttpResult result = get(properties.getPaths().getInstance(), "{instanceId}", instanceId, null, null);
        SnapshotDto dto = parse(result, SnapshotDto.class, "查询实例快照 " + instanceId);
        return new InstanceSnapshot(dto.state(), dto.detail(), dto.restartGeneration());
    }

    @Override
    public String getMetrics(String instanceId, String metricName) {
        HttpResult result = get(properties.getPaths().getInstanceMetrics(), "{instanceId}", instanceId,
                "metric", metricName);
        return parse(result, ReceiptDto.class, "查询指标 " + instanceId).message();
    }

    // ---- 写方法(POST 单次发出,绝不自动重试) ----

    @Override
    public String restartInstance(String instanceId) {
        return post(properties.getPaths().getInstanceReboot(), "{instanceId}", instanceId, null, null,
                "重启实例 " + instanceId);
    }

    @Override
    public String refundOrder(String orderId) {
        return post(properties.getPaths().getOrderRefund(), "{orderId}", orderId, null, null,
                "订单退款 " + orderId);
    }

    @Override
    public String authorizeSecurityGroup(String securityGroupId, int port) {
        return post(properties.getPaths().getSecurityGroupRules(), "{sgId}", securityGroupId,
                "port", String.valueOf(port), "安全组放行 " + securityGroupId + ":" + port);
    }

    // ---- 独立读路径(404 → empty,不信任写提交返回值) ----

    @Override
    public Optional<RefundRecord> queryRefundRecord(String orderId) {
        HttpResult result = get(properties.getPaths().getOrderRefundLatest(), "{orderId}", orderId, null, null);
        if (result.isNotFound()) {
            return Optional.empty();
        }
        RefundDto dto = parse(result, RefundDto.class, "对账退款流水 " + orderId);
        return Optional.of(new RefundRecord(dto.serialNo(), Instant.parse(dto.acceptedAt())));
    }

    @Override
    public Optional<SecurityGroupRule> querySecurityGroupRule(String securityGroupId, int port) {
        HttpResult result = get(properties.getPaths().getSecurityGroupRules(), "{sgId}", securityGroupId,
                "port", String.valueOf(port));
        if (result.isNotFound()) {
            return Optional.empty();
        }
        RuleDto dto = parse(result, RuleDto.class, "查询安全组规则 " + securityGroupId + ":" + port);
        return Optional.of(new SecurityGroupRule(Instant.parse(dto.effectiveAt()), dto.version()));
    }

    @Override
    public Optional<InetSocketAddress> probeEndpoint(String securityGroupId, int port) {
        HttpResult result = get(properties.getPaths().getSecurityGroupProbe(), "{sgId}", securityGroupId,
                "port", String.valueOf(port));
        if (result.isNotFound()) {
            return Optional.empty();
        }
        ProbeDto dto = parse(result, ProbeDto.class, "解析探测端点 " + securityGroupId + ":" + port);
        return Optional.of(new InetSocketAddress(dto.host(), dto.port()));
    }

    // ---- HTTP 细节(私有,保持映射纯度) ----

    /** GET:非 2xx 且非 404 显式抛异常;404 原样带回由调用方按契约判定(Optional 读路径→empty) */
    private HttpResult get(String template, String placeholder, String value, String queryKey, String queryValue) {
        HttpResult result = transport.exchange(
                HttpCall.get(expand(template, placeholder, value), query(queryKey, queryValue)));
        if (!result.isOk() && !result.isNotFound()) {
            throw new IllegalStateException("云 API 读操作失败(HTTP " + result.status() + "): " + result.body());
        }
        return result;
    }

    /** POST(写):非 2xx 显式抛异常,单次发出不重试,绝不让上层误以为"已受理" */
    private String post(String template, String placeholder, String value, String queryKey, String queryValue,
                        String op) {
        HttpResult result = transport.exchange(
                HttpCall.post(expand(template, placeholder, value), query(queryKey, queryValue)));
        if (!result.isOk()) {
            throw new IllegalStateException(op + " 提交失败(HTTP " + result.status() + "): " + result.body()
                    + ";写操作不自动重试,请确认受理方状态后人工决定是否重提");
        }
        return parse(result, ReceiptDto.class, op).message();
    }

    private Map<String, String> query(String key, String value) {
        return key == null ? Map.of() : Map.of(key, value);
    }

    /** 路径模板占位符替换(每个模板恰好一个占位符) */
    private String expand(String template, String placeholder, String value) {
        return template.replace(placeholder, value);
    }

    private <T> T parse(HttpResult result, Class<T> type, String op) {
        try {
            return MAPPER.readValue(result.body(), type);
        } catch (IOException e) {
            throw new IllegalStateException(op + " 响应解析失败(网关契约变更?): " + e.getMessage()
                    + ", body=" + result.body(), e);
        }
    }

    // ---- HTTP DTO(与 mock 云端点/真实网关的 JSON 契约一一对应,@JsonProperty 显式绑定,
    // 不依赖编译器 -parameters,契约字段变更在解析期显式失败) ----

    record SnapshotDto(@JsonProperty("state") String state,
                       @JsonProperty("detail") String detail,
                       @JsonProperty("restartGeneration") long restartGeneration) {
    }

    record ReceiptDto(@JsonProperty("message") String message) {
    }

    record RefundDto(@JsonProperty("serialNo") String serialNo,
                     @JsonProperty("acceptedAt") String acceptedAt) {
    }

    record RuleDto(@JsonProperty("effectiveAt") String effectiveAt,
                   @JsonProperty("version") long version) {
    }

    record ProbeDto(@JsonProperty("host") String host,
                    @JsonProperty("port") int port) {
    }
}
