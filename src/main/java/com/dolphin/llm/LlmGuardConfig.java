package com.dolphin.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 下游保护装配(阶段 O9):注册 {@link LlmGuardProperties}。
 * {@link LlmGuard} 本身是 {@code @Component},由组件扫描注册。
 */
@Configuration
@EnableConfigurationProperties(LlmGuardProperties.class)
public class LlmGuardConfig {
}
