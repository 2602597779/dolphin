package com.dolphin.ticket.agent;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词加载器:从 classpath:prompts/xxx.st 读取系统提示词并缓存。
 *
 * <p>不用 Spring AI 的 PromptTemplate 渲染,避免 .st 文件里的字面大括号被模板引擎吃掉;
 * 变量注入统一由 Agent 代码做字符串拼接。
 */
public final class PromptLoader {

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptLoader() {
    }

    /**
     * 加载提示词文件内容。
     *
     * @param name 文件名(不含路径),如 "triage.st"
     * @return 提示词文本
     */
    public static String load(String name) {
        return CACHE.computeIfAbsent(name, k -> {
            try {
                return new ClassPathResource("prompts/" + k).getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("加载提示词失败: prompts/" + k, e);
            }
        });
    }
}
