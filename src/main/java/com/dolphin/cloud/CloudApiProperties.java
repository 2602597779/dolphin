package com.dolphin.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 云服务数据源配置({@code dolphin.cloud.*})。
 *
 * <p>用途:把"接哪个云、怎么接、超时多久、凭证从哪来、路径长什么样"全部外置到配置,
 * 使 {@code HttpCloudApi}(S2-4 落地)只有映射逻辑,换网关/换版本/换鉴权方式都只改配置不改代码。
 *
 * <p><b>骨架期所有值都是 mock</b>:{@link #baseUrl} 默认指向本应用自己的 {@code /__mock} 端点,
 * 凭证默认 {@code mode=none},不依赖任何外网环境即可把链路跑通;接真实云时统一替换这几个配置项。
 *
 * <p>安全约定:凭证字段只从环境变量/密钥服务注入({@code DOLPHIN_CLOUD_*}),
 * 禁止在 {@code application.yml} 里写死明文。
 */
@ConfigurationProperties(prefix = "dolphin.cloud")
public class CloudApiProperties {

    /** 数据源实现:sim=进程内模拟器(默认) | http=HTTP 对接真实云 API/平台网关 */
    private String provider = "sim";

    /** http 实现的目标网关根地址(骨架期指向本应用 mock 端点) */
    private String baseUrl = "http://localhost:8080/__mock";

    /** 建立连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 读响应超时 */
    private Duration readTimeout = Duration.ofSeconds(10);

    private final Retry retry = new Retry();
    private final Auth auth = new Auth();
    private final Probe probe = new Probe();
    private final Paths paths = new Paths();

    /** 重试策略:只对读方法生效,写方法绝不自动重试(避免重复重启/重复退款) */
    public static class Retry {
        /** 读方法最大尝试次数(含首次) */
        private int readMaxAttempts = 2;

        public int getReadMaxAttempts() {
            return readMaxAttempts;
        }

        public void setReadMaxAttempts(int readMaxAttempts) {
            this.readMaxAttempts = readMaxAttempts;
        }
    }

    /** 鉴权方式配置 */
    public static class Auth {
        /** none=不鉴权(骨架期 mock) | bearer=固定 token | ak-sk=AK/SK 签名 */
        private String mode = "none";
        private String token = "mock-token";
        private String accessKey = "";
        private String secretKey = "";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }
    }

    /** 连通性探测配置 */
    public static class Probe {
        /** 关闭后 PROBE 范式失去外部探测目标,需相应调整动作描述符(见 ActionsRegistration 说明) */
        private boolean enabled = true;
        /** 探测目标兜底主机:真实环境填实例可达地址 */
        private String targetHost = "127.0.0.1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTargetHost() {
            return targetHost;
        }

        public void setTargetHost(String targetHost) {
            this.targetHost = targetHost;
        }
    }

    /**
     * 接口路径模板(占位符 {@code {instanceId}}/{@code {orderId}}/{@code {sgId}} 由实现方替换)。
     * 集中配置的目的:真实网关的路径风格通常与模拟器不同,改这里即可,不必动 Java 代码。
     */
    public static class Paths {
        private String instance = "/v1/instances/{instanceId}";
        private String instanceReboot = "/v1/instances/{instanceId}/reboot";
        private String instanceMetrics = "/v1/instances/{instanceId}/metrics";
        private String orderRefund = "/v1/orders/{orderId}/refund";
        private String orderRefundLatest = "/v1/orders/{orderId}/refunds/latest";
        private String securityGroupRules = "/v1/security-groups/{sgId}/rules";
        private String securityGroupProbe = "/v1/security-groups/{sgId}/probe";

        public String getInstance() {
            return instance;
        }

        public void setInstance(String instance) {
            this.instance = instance;
        }

        public String getInstanceReboot() {
            return instanceReboot;
        }

        public void setInstanceReboot(String instanceReboot) {
            this.instanceReboot = instanceReboot;
        }

        public String getInstanceMetrics() {
            return instanceMetrics;
        }

        public void setInstanceMetrics(String instanceMetrics) {
            this.instanceMetrics = instanceMetrics;
        }

        public String getOrderRefund() {
            return orderRefund;
        }

        public void setOrderRefund(String orderRefund) {
            this.orderRefund = orderRefund;
        }

        public String getOrderRefundLatest() {
            return orderRefundLatest;
        }

        public void setOrderRefundLatest(String orderRefundLatest) {
            this.orderRefundLatest = orderRefundLatest;
        }

        public String getSecurityGroupRules() {
            return securityGroupRules;
        }

        public void setSecurityGroupRules(String securityGroupRules) {
            this.securityGroupRules = securityGroupRules;
        }

        public String getSecurityGroupProbe() {
            return securityGroupProbe;
        }

        public void setSecurityGroupProbe(String securityGroupProbe) {
            this.securityGroupProbe = securityGroupProbe;
        }
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Retry getRetry() {
        return retry;
    }

    public Auth getAuth() {
        return auth;
    }

    public Probe getProbe() {
        return probe;
    }

    public Paths getPaths() {
        return paths;
    }
}
