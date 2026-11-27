package com.dolphin.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IM 回调接入装配:平台适配器 + 回调入口。
 *
 * <p>装配口径与 {@code ClusterConfig} / {@code NotifyConfig} 一致:接口 + 实现 + 装配处显式注册。
 * 后续接入 WeLink/飞书 = 新增一个 {@link ImCallbackAdapter} Bean,回调链路零改动。
 *
 * <p>适配器 Bean 常驻(不做条件装配):凭证缺失时由适配器自身拒绝请求,
 * 使"入口关闭"这件事可被观测(400 + 原因),而不是表现为 404 或启动失败。
 */
@Configuration
@EnableConfigurationProperties(ImCallbackProperties.class)
public class ImCallbackConfig {

    /** 钉钉参考适配器(自实现加解密,零第三方 SDK) */
    @Bean
    public ImCallbackAdapter dingTalkCallbackAdapter(ImCallbackProperties properties, ObjectMapper objectMapper) {
        return new DingTalkCallbackAdapter(properties, objectMapper);
    }
}
