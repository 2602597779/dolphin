package com.dolphin.sla;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * SLA 装配:注册 {@code dolphin.sla.*} 配置与统一时钟。
 *
 * <p>时钟以 Bean 注入({@link Clock#systemUTC()}):业务时间统一取 UTC,
 * 单测/演示可注入固定时钟或加速时钟,不依赖真实睡眠。
 */
@Configuration
@EnableConfigurationProperties(SlaProperties.class)
public class SlaConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
