package com.dolphin.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link DingTalkNotifier} 单测:加签算法、errcode 判定、未配置降级、发送失败降级
 * (MockRestServiceServer 替代真实网络,失败只留痕绝不向诊断主流程抛异常)。
 */
class DingTalkNotifierTest {

    private MockRestServiceServer server;

    private DingTalkNotifier notifierWithServer(String webhookUrl, String secret) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new DingTalkNotifier(builder.build(), webhookUrl, secret, new ObjectMapper());
    }

    @Test
    void notConfiguredFallsBackToLogAndReturnsFalse() {
        DingTalkNotifier notifier = notifierWithServer("", "");
        assertFalse(notifier.isConfigured());
        // 不应发起任何 HTTP 请求(无 server.expect,verify 由 sendMarkdown 内部降级保证)
        assertFalse(notifier.sendMarkdown("t", "body"));
    }

    @Test
    void sendsMarkdownWithoutSignWhenSecretBlank() {
        String url = "https://oapi.dingtalk.com/robot/send?access_token=abc";
        DingTalkNotifier notifier = notifierWithServer(url, "");
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("markdown")))
                .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));
        assertTrue(notifier.sendMarkdown("标题", "**正文**"));
        server.verify();
    }

    @Test
    void appendsTimestampAndSignWhenSecretConfigured() {
        String url = "https://oapi.dingtalk.com/robot/send?access_token=abc";
        DingTalkNotifier notifier = notifierWithServer(url, "SEC-secret");
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .requestTo(org.hamcrest.Matchers.startsWith(url + "&timestamp=")))
                .andRespond(withSuccess("{\"errcode\":0}", MediaType.APPLICATION_JSON));
        assertTrue(notifier.sendMarkdown("标题", "正文"));
        server.verify();
    }

    @Test
    void serverErrorDegradesToFalseWithoutThrowing() {
        DingTalkNotifier notifier = notifierWithServer("https://oapi.dingtalk.com/robot/send?access_token=abc", "");
        server.expect(requestTo("https://oapi.dingtalk.com/robot/send?access_token=abc"))
                .andRespond(withServerError());
        assertFalse(notifier.sendMarkdown("标题", "正文"));
        server.verify();
    }

    @Test
    void errcodeNonZeroMeansFailure() {
        DingTalkNotifier notifier = notifierWithServer("https://oapi.dingtalk.com/robot/send?access_token=abc", "");
        server.expect(requestTo("https://oapi.dingtalk.com/robot/send?access_token=abc"))
                .andRespond(withSuccess("{\"errcode\":310000,\"errmsg\":\"sign not match\"}",
                        MediaType.APPLICATION_JSON));
        assertFalse(notifier.sendMarkdown("标题", "正文"));
        server.verify();
    }

    @Test
    void signUrlProducesDecodableTimestampAndSignature() throws Exception {
        String signed = DingTalkNotifier.signUrl(
                "https://oapi.dingtalk.com/robot/send?access_token=abc", "SEC-secret", 1700000000000L);
        assertTrue(signed.startsWith("https://oapi.dingtalk.com/robot/send?access_token=abc&timestamp=1700000000000&sign="));
        String sign = URLDecoder.decode(
                signed.substring(signed.indexOf("&sign=") + "&sign=".length()), StandardCharsets.UTF_8);
        // 同一输入的 HMAC-SHA256 结果确定:用长度与字符集做 sanity 断言(base64 编码,44 字符)
        assertEquals(44, sign.length());
        assertEquals(signed, DingTalkNotifier.signUrl(
                "https://oapi.dingtalk.com/robot/send?access_token=abc", "SEC-secret", 1700000000000L));
    }
}
