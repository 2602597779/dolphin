package com.dolphin.cloud;

import com.dolphin.ticket.service.HttpTransport.HttpCall;
import com.dolphin.ticket.service.HttpTransport.HttpResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link RestCloudTransport} 单测:用 MockRestServiceServer 替代真实网络栈,重点验证
 * <b>仅读重试</b>策略——GET 对连接失败/5xx 按配置有限重试、404 不重试原样带回;
 * POST(写)单次发出绝不重试;鉴权头按 mode 附加,非法 mode 装配期显式失败。
 */
class RestCloudTransportTest {

    private static final String BASE = "http://mock-gateway/__mock";

    private MockRestServiceServer server;

    /** 构建 transport 并绑定 MockRestServiceServer(替代真实网络) */
    private RestCloudTransport transportWithServer(int readMaxAttempts) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new RestCloudTransport(builder.build(), BASE, readMaxAttempts);
    }

    // ---- 基础映射 ----

    @Test
    void get_mapsStatusBodyAndRequestLine() {
        RestCloudTransport transport = transportWithServer(1);
        server.expect(requestTo(BASE + "/v1/instances/i-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"state\":\"RUNNING\"}", MediaType.APPLICATION_JSON));
        HttpResult result = transport.exchange(HttpCall.get("/v1/instances/i-1", Map.of()));
        assertEquals(200, result.status());
        assertEquals("{\"state\":\"RUNNING\"}", result.body());
        server.verify();
    }

    @Test
    void get_carriesUrlEncodedQuery() {
        RestCloudTransport transport = transportWithServer(1);
        server.expect(requestTo(BASE + "/v1/security-groups/sg-1/rules?port=8080"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        transport.exchange(HttpCall.get("/v1/security-groups/sg-1/rules", Map.of("port", "8080")));
        server.verify();
    }

    // ---- 读重试:5xx/连接失败按配置重试,404 不重试 ----

    @Test
    void get_retriesOn5xxThenSucceeds() {
        RestCloudTransport transport = transportWithServer(2);
        server.expect(requestTo(BASE + "/v1/instances/i-1")).andRespond(withServerError());
        server.expect(requestTo(BASE + "/v1/instances/i-1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        HttpResult result = transport.exchange(HttpCall.get("/v1/instances/i-1", Map.of()));
        assertEquals(200, result.status());
        server.verify();
    }

    @Test
    void get_5xxExhaustedReturnsLastStatus() {
        RestCloudTransport transport = transportWithServer(2);
        server.expect(requestTo(BASE + "/v1/instances/i-1")).andRespond(withServerError());
        server.expect(requestTo(BASE + "/v1/instances/i-1")).andRespond(withServerError());
        HttpResult result = transport.exchange(HttpCall.get("/v1/instances/i-1", Map.of()));
        assertEquals(500, result.status());
        server.verify();
    }

    @Test
    void get_404ReturnedAsIsWithoutRetry() {
        RestCloudTransport transport = transportWithServer(3);
        server.expect(requestTo(BASE + "/v1/orders/o-1/refunds/latest"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        HttpResult result = transport.exchange(HttpCall.get("/v1/orders/o-1/refunds/latest", Map.of()));
        assertEquals(404, result.status());
        server.verify(); // 只有 1 个 expectation:若发生重试 verify 将失败
    }

    @Test
    void get_connectionErrorRetriedUntilSuccess() {
        RestCloudTransport transport = transportWithServer(2);
        server.expect(requestTo(BASE + "/v1/instances/i-1"))
                .andRespond(withException(new ConnectException("connection reset")));
        server.expect(requestTo(BASE + "/v1/instances/i-1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        HttpResult result = transport.exchange(HttpCall.get("/v1/instances/i-1", Map.of()));
        assertEquals(200, result.status());
        server.verify();
    }

    @Test
    void get_connectionErrorExhaustedThrowsExplicitly() {
        RestCloudTransport transport = transportWithServer(2);
        server.expect(requestTo(BASE + "/v1/instances/i-1"))
                .andRespond(withException(new ConnectException("timeout")));
        server.expect(requestTo(BASE + "/v1/instances/i-1"))
                .andRespond(withException(new ConnectException("timeout")));
        assertThrows(IllegalStateException.class,
                () -> transport.exchange(HttpCall.get("/v1/instances/i-1", Map.of())));
        server.verify();
    }

    // ---- 写操作:单次发出,连接失败/5xx 都绝不重试 ----

    @Test
    void post_failsFastOnConnectionError_neverRetries() {
        RestCloudTransport transport = transportWithServer(3);
        server.expect(requestTo(BASE + "/v1/instances/i-1/reboot"))
                .andRespond(withException(new ConnectException("connection reset")));
        assertThrows(IllegalStateException.class,
                () -> transport.exchange(HttpCall.post("/v1/instances/i-1/reboot", Map.of())));
        server.verify(); // 只有 1 个 expectation:若写操作重试,第二次请求会触发断言失败
    }

    @Test
    void post_5xxReturnedAsIsForExplicitFailureUpstream() {
        RestCloudTransport transport = transportWithServer(3);
        server.expect(requestTo(BASE + "/v1/instances/i-1/reboot"))
                .andRespond(withServerError());
        HttpResult result = transport.exchange(HttpCall.post("/v1/instances/i-1/reboot", Map.of()));
        assertEquals(500, result.status()); // 状态码原样带回,判定与"绝不重试"语义归 HttpCloudApi
        server.verify();
    }

    // ---- 鉴权:mode 决定请求头,非法值显式失败 ----

    @Test
    void bearerAuth_sendsAuthorizationHeader() {
        CloudApiProperties.Auth auth = new CloudApiProperties.Auth();
        auth.setMode("bearer");
        auth.setToken("t-123");
        RestClient.Builder builder = RestClient.builder();
        RestCloudTransport.applyAuth(builder, auth);
        server = MockRestServiceServer.bindTo(builder).build();
        RestCloudTransport transport = new RestCloudTransport(builder.build(), BASE, 1);
        server.expect(requestTo(BASE + "/v1/instances/i-1"))
                .andExpect(header("Authorization", "Bearer t-123"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        transport.exchange(HttpCall.get("/v1/instances/i-1", Map.of()));
        server.verify();
    }

    @Test
    void akSkAuth_failsFastInsteadOfSilentlyUnauthenticated() {
        CloudApiProperties.Auth auth = new CloudApiProperties.Auth();
        auth.setMode("ak-sk");
        auth.setAccessKey("ak");
        auth.setSecretKey("sk");
        assertThrows(IllegalStateException.class, () -> RestCloudTransport.applyAuth(RestClient.builder(), auth));
    }

    @Test
    void unknownAuthMode_failsFast() {
        CloudApiProperties.Auth auth = new CloudApiProperties.Auth();
        auth.setMode("weird");
        assertThrows(IllegalStateException.class, () -> RestCloudTransport.applyAuth(RestClient.builder(), auth));
    }
}
