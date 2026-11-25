package com.dolphin.notify;

import com.dolphin.alert.DingTalkNotifier;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通知通道装配:按 {@code dolphin.notify.channel} 条件装配唯一 {@link NotifySender} bean。
 *
 * <p>direct(默认,含配置缺失)→ 同步直发;mq → 进程内 MQ 语义实现。
 * 调用方({@code SlaMonitor}/{@code AlertDiagnosisService})只依赖接口,切换通道零改动;
 * offline 演示与既有测试默认走 direct,行为与改造前完全一致。
 */
@Configuration
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyConfig {

    /** mq 通道:有界缓冲 + 单消费者限速 + 失败重试 + 死信;destroyMethod 关停消费者线程 */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "dolphin.notify.channel", havingValue = "mq")
    public NotifySender mqNotifySender(DingTalkNotifier dingTalkNotifier,
                                       NotifyProperties properties,
                                       MeterRegistry meterRegistry) {
        return new MqNotifySender(new DirectNotifySender(dingTalkNotifier), properties, meterRegistry);
    }

    /** direct 通道(默认,含配置缺失):同步直发,既有语义 */
    @Bean
    @ConditionalOnProperty(name = "dolphin.notify.channel", havingValue = "direct", matchIfMissing = true)
    public NotifySender directNotifySender(DingTalkNotifier dingTalkNotifier) {
        return new DirectNotifySender(dingTalkNotifier);
    }
}
