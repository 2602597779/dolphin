package com.dolphin.cloud;

import com.dolphin.ticket.service.HttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * {@link HttpTransport} 的 Spring RestClient 实现:集中处理建连/读超时、鉴权头与重试策略,
 * 使 {@code HttpCloudApi} 保持纯映射。
 *
 * <p>重试策略(按 HTTP method 区分,配置见 {@code dolphin.cloud.retry.read-max-attempts}):
 * <ul>
 *   <li><b>GET(读)可有限重试</b>:覆盖两类瞬时故障——连接失败/读超时({@link ResourceAccessException})
 *       与 5xx 服务端错误;404 与其他 4xx 不重试(资源不存在/请求本身有错,重试无意义,
 *       "未生效→已生效"的等待由上层轮询节奏决定,不归 transport);</li>
 *   <li><b>POST(写)绝不重试,单次发出</b>:失败立即显式抛异常,是否重提由人工/审批决策
 *       (clientToken 幂等由受理方保证,提交方盲目重试会造成重复重启/重复退款)。</li>
 * </ul>
 *
 * <p>对外契约:凡是网关<b>有 HTTP 响应</b>的一律把状态码原样带回 {@link HttpTransport.HttpResult}
 * (含 4xx/5xx,语义判定归 HttpCloudApi);<b>无响应</b>(连接失败/超时,重试耗尽)才抛异常。
 * 鉴权:{@code none}(mock 网关) / {@code bearer}(固定 token);{@code ak-sk} 显式拒绝——
 * 没有统一签名标准,宁可启动失败也不以无凭证状态访问真实云。
 */
public class RestCloudTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(RestCloudTransport.class);

    private final RestClient restClient;
    private final String baseUrl;
    private final int readMaxAttempts;

    /** 生产构造器:超时/鉴权/重试参数全部来自 {@code dolphin.cloud.*} 配置 */
    public RestCloudTransport(CloudApiProperties properties) {
        this(buildRestClient(properties), properties.getBaseUrl(), properties.getRetry().getReadMaxAttempts());
    }

    /** 测试/定制构造器:注入外部构建的 RestClient(如 MockRestServiceServer 绑定) */
    RestCloudTransport(RestClient restClient, String baseUrl, int readMaxAttempts) {
        this.restClient = restClient;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.readMaxAttempts = Math.max(1, readMaxAttempts);
    }

    /** 组装生产 RestClient:JDK HttpClient 承载连接/读超时,鉴权头按 mode 配置 */
    static RestClient buildRestClient(CloudApiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        applyAuth(builder, properties.getAuth());
        return builder.build();
    }

    /** 按鉴权 mode 附加默认头;非法/未实现的 mode 在装配期显式失败 */
    static void applyAuth(RestClient.Builder builder, CloudApiProperties.Auth auth) {
        switch (auth.getMode()) {
            case "none" -> {
                // mock 网关不鉴权
            }
            case "bearer" -> builder.defaultHeader("Authorization", "Bearer " + auth.getToken());
            case "ak-sk" -> throw new IllegalStateException(
                    "dolphin.cloud.auth.mode=ak-sk 尚未实现签名算法:显式拒绝启动,"
                            + "绝不以无凭证状态访问真实云(凭证只从环境变量/密钥服务注入)");
            default -> throw new IllegalStateException(
                    "未知鉴权 mode: " + auth.getMode() + " —— 仅支持 none | bearer | ak-sk");
        }
    }

    @Override
    public HttpResult exchange(HttpCall call) {
        boolean isRead = "GET".equals(call.method());
        int maxAttempts = isRead ? readMaxAttempts : 1;
        for (int attempt = 1; ; attempt++) {
            try {
                ResponseEntity<String> response = restClient.method(HttpMethod.valueOf(call.method()))
                        .uri(buildUri(call))
                        .retrieve()
                        .toEntity(String.class);
                return new HttpResult(response.getStatusCode().value(), response.getBody());
            } catch (RestClientResponseException e) {
                // 网关有响应:读操作仅 5xx 重试;其余(含 404)原样带回,语义判定归 HttpCloudApi
                int status = e.getStatusCode().value();
                if (isRead && status >= 500 && attempt < maxAttempts) {
                    log.warn("[cloud-transport] 读操作 5xx,准备重试({}/{}): {} {} -> HTTP {}",
                            attempt, maxAttempts, call.method(), call.path(), status);
                    continue;
                }
                return new HttpResult(status, e.getResponseBodyAsString());
            } catch (ResourceAccessException e) {
                // 无响应(连接失败/读超时):仅读操作按配置重试,写操作立即显式失败
                if (!isRead || attempt >= maxAttempts) {
                    throw new IllegalStateException("云 API 连接失败(" + call.method() + " " + call.path()
                            + ",第 " + attempt + "/" + maxAttempts + " 次尝试): " + e.getMessage()
                            + (isRead ? "" : ";写操作不自动重试,请确认受理方状态后人工决定是否重提"), e);
                }
                log.warn("[cloud-transport] 读操作连接失败,准备重试({}/{}): {} {} -> {}",
                        attempt, maxAttempts, call.method(), call.path(), e.getMessage());
            }
        }
    }

    /** 拼接完整 URL:baseUrl + 路径(已模板替换) + 查询参数(UTF-8 编码) */
    private URI buildUri(HttpCall call) {
        StringBuilder url = new StringBuilder(baseUrl).append(call.path());
        if (!call.query().isEmpty()) {
            url.append('?');
            StringBuilder query = new StringBuilder();
            call.query().forEach((key, value) -> query.append(encode(key)).append('=').append(encode(value)).append('&'));
            url.append(query, 0, query.length() - 1);
        }
        return URI.create(url.toString());
    }

    private String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String baseUrl) {
        return baseUrl != null && baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }
}
