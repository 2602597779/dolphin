package com.dolphin.alert;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 告警链路装配:注册 {@code dolphin.alert.*} 配置与钉钉通知器。
 */
@Configuration
@EnableConfigurationProperties(AlertProperties.class)
public class AlertConfig {

    @Bean
    public DingTalkNotifier dingTalkNotifier(AlertProperties properties) {
        return new DingTalkNotifier(properties.getDingtalk());
    }
}
