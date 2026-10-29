package com.dolphin.ticket.service;

import com.dolphin.cloud.CloudApiProperties;
import com.dolphin.cloud.RestCloudTransport;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 数据源装配:决定 {@link CloudApi} 使用哪个实现,上层(执行器/验证器/只读工具)只依赖接口。
 *
 * <p>切换方式——配置项 {@code dolphin.cloud.provider}:
 * <ul>
 *   <li>{@code sim}(缺省,{@code matchIfMissing}):{@link CloudApiService} 进程内模拟器,
 *       offline 演示与单测的数据源;</li>
 *   <li>{@code http}:{@link HttpCloudApi} + {@link RestCloudTransport},经 HTTP 对接
 *       mock 云端点({@code /__mock})或真实云 API/平台网关,目标地址由
 *       {@code dolphin.cloud.base-url} 决定。</li>
 * </ul>
 *
 * <p><b>非法 provider 显式失败</b>:既不是 sim 也不是 http 的取值会在启动期直接抛异常拒绝启动
 * (见 {@link #logEffectiveConfig()}),绝不静默回退到模拟器——线上把真实变更错发进模拟器、
 * 或把演示流量打到真实云,都是不可接受的。
 *
 * <p><b>为什么实现类不再标 {@code @Component}</b>:两个实现同时被组件扫描会形成两个 {@link CloudApi}
 * 候选 bean,注入点(ResultVerifier/ActionExecutor/ResourceTools)会因候选不唯一而启动失败。
 * 这里用条件装配保证容器里<b>永远只有一个</b> {@link CloudApi} bean,切换是显式的一行配置,
 * 而不是隐式的 {@code @Primary} 优先级。
 */
@Configuration
@EnableConfigurationProperties(CloudApiProperties.class)
public class CloudApiConfig {

    private static final Logger log = LoggerFactory.getLogger(CloudApiConfig.class);

    /** 注入 {@link CloudApiProperties} 的副作用之一:启动即校验 {@code dolphin.cloud.*} 绑定,
     *  配置写错(如 Duration 格式非法)会在启动期直接失败,而不是等到第一次调用云端才暴露。 */
    private final CloudApiProperties properties;

    public CloudApiConfig(CloudApiProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动时打印生效的数据源配置:切换 provider 后看这一行就能确认"到底连的是谁",
     * 避免线上误把模拟器当成真实云(HITL 场景下这种误判代价很高)。
     */
    @PostConstruct
    void logEffectiveConfig() {
        String provider = properties.getProvider();
        if (!"sim".equals(provider) && !"http".equals(provider)) {
            throw new IllegalStateException("dolphin.cloud.provider 非法: " + provider
                    + " —— 仅支持 sim | http;显式失败拒绝启动,绝不静默回退到未知数据源");
        }
        log.info("云服务数据源配置: provider={}, http base-url={}, authMode={}, probeEnabled={}",
                provider, properties.getBaseUrl(),
                properties.getAuth().getMode(), properties.getProbe().isEnabled());
    }

    /**
     * 模拟器数据源(默认):{@code dolphin.sim.*} 延迟参数在此注入,
     * 使 {@link CloudApiService} 自身不耦合 Spring 注解,单测可直接 {@code new}。
     *
     * <p>Bean 名固定为 {@code simCloudApi},便于后续 mock 端点按名引用它
     * (与"当前生效的数据源 bean"分离,避免 provider=http 时自引用)。
     */
    @Bean("simCloudApi")
    @ConditionalOnProperty(name = "dolphin.cloud.provider", havingValue = "sim", matchIfMissing = true)
    public CloudApi simCloudApi(@Value("${dolphin.sim.restart-latency:PT3S}") Duration restartLatency,
                                @Value("${dolphin.sim.rule-propagation:PT1S}") Duration rulePropagation,
                                @Value("${dolphin.sim.refund-record-latency:PT1S}") Duration refundRecordLatency) {
        return new CloudApiService(restartLatency, rulePropagation, refundRecordLatency);
    }

    /**
     * HTTP 数据源:目标可以是本应用自己的 mock 云端点({@code /__mock},offline 全链路),
     * 也可以是真实云 API/平台网关——只由 {@code dolphin.cloud.base-url} 区分,代码零差异。
     * 超时/鉴权/重试在 {@link RestCloudTransport} 内按配置组装。
     */
    @Bean("httpCloudApi")
    @ConditionalOnProperty(name = "dolphin.cloud.provider", havingValue = "http")
    public CloudApi httpCloudApi() {
        log.info("装配 HTTP 云数据源: base-url={}, connectTimeout={}, readTimeout={}, readMaxAttempts={}",
                properties.getBaseUrl(), properties.getConnectTimeout(),
                properties.getReadTimeout(), properties.getRetry().getReadMaxAttempts());
        return new HttpCloudApi(new RestCloudTransport(properties), properties);
    }
}
