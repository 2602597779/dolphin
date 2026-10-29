package com.dolphin.ticket.service;

import com.dolphin.cloud.CloudApiProperties;
import com.dolphin.ticket.service.HttpTransport.HttpCall;
import com.dolphin.ticket.service.HttpTransport.HttpResult;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpCloudApi} 单测:用 fake transport 替身(不启动真实 HTTP 栈)覆盖
 * 契约方法 → HTTP 调用的纯映射、独立读路径 404→empty 语义、写操作单次提交绝不重试、
 * 非 2xx/非法响应显式失败不伪装成功。JSON 形状与 MockCloudController 实际产出一致。
 */
class HttpCloudApiTest {

    /** 替身 transport:记录收到的调用,按预置队列吐回响应 */
    static class FakeTransport implements HttpTransport {
        final List<HttpCall> calls = new ArrayList<>();
        final Deque<HttpResult> responses = new ArrayDeque<>();

        @Override
        public HttpResult exchange(HttpCall call) {
            calls.add(call);
            return responses.removeFirst();
        }
    }

    private final FakeTransport transport = new FakeTransport();
    private final HttpCloudApi api = new HttpCloudApi(transport, new CloudApiProperties());

    // ---- 快照映射 ----

    @Test
    void instanceSnapshot_mapsStateAndGeneration() {
        transport.responses.add(new HttpResult(200,
                "{\"state\":\"STARTING\",\"detail\":\"实例 i-1 状态:STARTING\",\"restartGeneration\":2}"));
        CloudApi.InstanceSnapshot snapshot = api.getInstanceSnapshot("i-1");
        assertEquals("STARTING", snapshot.state());
        assertEquals("实例 i-1 状态:STARTING", snapshot.detail());
        assertEquals(2, snapshot.restartGeneration());
        HttpCall call = transport.calls.get(0);
        assertEquals("GET", call.method());
        assertEquals("/v1/instances/i-1", call.path());
        assertTrue(call.query().isEmpty());
    }

    @Test
    void instanceStatus_derivesFromSnapshotReadPath() {
        transport.responses.add(new HttpResult(200,
                "{\"state\":\"RUNNING\",\"detail\":\"实例 i-1 状态:RUNNING\",\"restartGeneration\":0}"));
        assertEquals("实例 i-1 状态:RUNNING", api.getInstanceStatus("i-1"));
    }

    @Test
    void metrics_carriesMetricQuery() {
        transport.responses.add(new HttpResult(200, "{\"message\":\"指标数据\"}"));
        assertEquals("指标数据", api.getMetrics("i-1", "cpu"));
        HttpCall call = transport.calls.get(0);
        assertEquals("/v1/instances/i-1/metrics", call.path());
        assertEquals("cpu", call.query().get("metric"));
    }

    // ---- 独立读路径:404 → empty(不伪装已生效) ----

    @Test
    void refundRecord_404mapsToEmpty() {
        transport.responses.add(new HttpResult(404, ""));
        assertTrue(api.queryRefundRecord("o-1").isEmpty());
    }

    @Test
    void refundRecord_mapsSerialNoAndAcceptedAt() {
        transport.responses.add(new HttpResult(200,
                "{\"serialNo\":\"RF-2158065e\",\"acceptedAt\":\"2026-10-29T03:14:26.125398500Z\"}"));
        CloudApi.RefundRecord record = api.queryRefundRecord("o-1").orElseThrow();
        assertEquals("RF-2158065e", record.serialNo());
        assertEquals(Instant.parse("2026-10-29T03:14:26.125398500Z"), record.acceptedAt());
        assertEquals("/v1/orders/o-1/refunds/latest", transport.calls.get(0).path());
    }

    @Test
    void securityGroupRule_404mapsToEmpty() {
        transport.responses.add(new HttpResult(404, ""));
        assertTrue(api.querySecurityGroupRule("sg-1", 8080).isEmpty());
    }

    @Test
    void securityGroupRule_mapsVersionAndCarriesPortQuery() {
        transport.responses.add(new HttpResult(200,
                "{\"effectiveAt\":\"2026-10-29T03:14:27.414642800Z\",\"version\":7}"));
        CloudApi.SecurityGroupRule rule = api.querySecurityGroupRule("sg-1", 8080).orElseThrow();
        assertEquals(7, rule.version());
        assertEquals(Instant.parse("2026-10-29T03:14:27.414642800Z"), rule.effectiveAt());
        HttpCall call = transport.calls.get(0);
        assertEquals("/v1/security-groups/sg-1/rules", call.path());
        assertEquals("8080", call.query().get("port"));
    }

    @Test
    void probeEndpoint_mapsHostAndPort() {
        transport.responses.add(new HttpResult(200, "{\"host\":\"127.0.0.1\",\"port\":50840}"));
        InetSocketAddress endpoint = api.probeEndpoint("sg-1", 8080).orElseThrow();
        assertEquals("127.0.0.1", endpoint.getAddress().getHostAddress());
        assertEquals(50840, endpoint.getPort());
    }

    @Test
    void probeEndpoint_404mapsToEmpty() {
        transport.responses.add(new HttpResult(404, ""));
        assertTrue(api.probeEndpoint("void-sg", 8080).isEmpty());
    }

    // ---- 写方法:单次 POST,失败显式抛异常且绝不自动重试 ----

    @Test
    void restartInstance_postsExactlyOnce() {
        transport.responses.add(new HttpResult(200, "{\"message\":\"已向云平台提交重启指令\"}"));
        assertEquals("已向云平台提交重启指令", api.restartInstance("i-1"));
        assertEquals(1, transport.calls.size());
        assertEquals("POST", transport.calls.get(0).method());
        assertEquals("/v1/instances/i-1/reboot", transport.calls.get(0).path());
    }

    @Test
    void writeFailure_failsExplicitlyWithoutRetry() {
        transport.responses.add(new HttpResult(500, "boom"));
        assertThrows(IllegalStateException.class, () -> api.restartInstance("i-1"));
        assertEquals(1, transport.calls.size());
    }

    @Test
    void refundOrder_postsToOrderRefundPath() {
        transport.responses.add(new HttpResult(200, "{\"message\":\"订单 o-1 退款已受理,流水号 RF-1\"}"));
        assertEquals("订单 o-1 退款已受理,流水号 RF-1", api.refundOrder("o-1"));
        assertEquals("/v1/orders/o-1/refund", transport.calls.get(0).path());
    }

    @Test
    void authorizeSecurityGroup_postsPortAsQuery() {
        transport.responses.add(new HttpResult(200, "{\"message\":\"已添加规则\"}"));
        api.authorizeSecurityGroup("sg-1", 8080);
        HttpCall call = transport.calls.get(0);
        assertEquals("POST", call.method());
        assertEquals("/v1/security-groups/sg-1/rules", call.path());
        assertEquals("8080", call.query().get("port"));
    }

    // ---- 显式失败:非 2xx 非 404 抛异常、非法响应体解析期失败 ----

    @Test
    void readNon2xx_failsExplicitly() {
        transport.responses.add(new HttpResult(503, "unavailable"));
        assertThrows(IllegalStateException.class, () -> api.getInstanceSnapshot("i-1"));
    }

    @Test
    void malformedBody_failsExplicitly() {
        transport.responses.add(new HttpResult(200, "not-json"));
        assertThrows(IllegalStateException.class, () -> api.getInstanceSnapshot("i-1"));
    }
}
