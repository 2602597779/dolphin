package com.dolphin.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 钉钉群机器人通知器(自定义机器人 markdown 消息,支持加签)。
 *
 * <p>降级策略:webhook URL 未配置 → 通知内容降级写审计日志并返回 false,
 * 调用方不得因此中断诊断主流程;发送失败(I/O/非 errcode=0 响应)→ 同样只留痕不抛异常。
 *
 * <p>加签协议:timestamp + "\n" + secret 作 HmacSHA256 消息,secret 作密钥,
 * 结果 base64 后 URL 编码,追加 {@code &timestamp=...&sign=...} 查询参数。
 */
public class DingTalkNotifier {

    private static final Logger log = LoggerFactory.getLogger(DingTalkNotifier.class);

    private final RestClient restClient;
    private final String webhookUrl;
    private final String secret;
    private final ObjectMapper objectMapper;

    /** 生产构造器:按 dolphin.alert.dingtalk.* 配置构建(5s 读超时) */
    public DingTalkNotifier(AlertProperties.DingTalk config) {
        this(defaultRestClient(config.getTimeout()), config.getWebhookUrl(), config.getSecret(), new ObjectMapper());
    }

    /** 测试/定制构造器:注入外部 RestClient 与凭证 */
    DingTalkNotifier(RestClient restClient, String webhookUrl, String secret, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.secret = secret == null ? "" : secret;
        this.objectMapper = objectMapper;
    }

    static RestClient defaultRestClient(Duration timeout) {
        return RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(timeout)
                                .build()))
                .build();
    }

    /** webhook 是否已配置(未配置时 sendMarkdown 走日志降级) */
    public boolean isConfigured() {
        return !webhookUrl.isBlank();
    }

    /**
     * 发送 markdown 消息。
     *
     * @return true=钉钉确认接收(errcode=0);false=未配置或发送失败(已留痕,不抛异常)
     */
    public boolean sendMarkdown(String title, String text) {
        if (!isConfigured()) {
            log.info("[alert-notify] 未配置钉钉 webhook,降级日志通知。title={}\n{}", title, text);
            return false;
        }
        try {
            URI uri = URI.create(signUrl(webhookUrl, secret, System.currentTimeMillis()));
            Map<String, Object> body = Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("title", title, "text", text));
            String resp = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseErrcode(resp);
        } catch (Exception e) {
            log.warn("[alert-notify] 钉钉通知发送失败(已降级,不影响诊断流程): {}", e.getMessage());
            return false;
        }
    }

    /** 解析钉钉响应 errcode:0 才算成功 */
    boolean parseErrcode(String respBody) {
        if (respBody == null) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(respBody);
            return node.path("errcode").asInt(-1) == 0;
        } catch (Exception e) {
            log.warn("[alert-notify] 钉钉响应解析失败: {}", respBody);
            return false;
        }
    }

    /** 加签 URL:secret 为空时原样返回,否则追加 timestamp/sign 查询参数 */
    static String signUrl(String webhookUrl, String secret, long timestamp) {
        if (secret == null || secret.isBlank()) {
            return webhookUrl;
        }
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
            String sep = webhookUrl.contains("?") ? "&" : "?";
            return webhookUrl + sep + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception e) {
            throw new IllegalStateException("钉钉加签失败: " + e.getMessage(), e);
        }
    }
}
