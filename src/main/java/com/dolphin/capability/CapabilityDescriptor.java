package com.dolphin.capability;

import java.util.List;

/**
 * 能力描述符:每个能力分支的"名片",同时驱动两件事——
 * <ul>
 *   <li>规则匹配:exactPhrases / regexPatterns / keywords 供匹配器链使用;</li>
 *   <li>LLM 分类:intentDesc + examples 动态拼进路由 prompt,新能力注册即被路由"认识"。</li>
 * </ul>
 *
 * @param code          能力编码(路由 key,如 rag-qa / ticket-flow / chat)
 * @param name          能力名称(人读)
 * @param intentDesc    意图描述(供 LLM 分类判断)
 * @param examples      典型用户说法(few-shot,供 LLM 分类)
 * @param keywords      关键词规则(命中数越多置信度越高)
 * @param regexPatterns 正则规则(find 命中即路由)
 * @param exactPhrases  精确匹配短语(equalsIgnoreCase 命中即路由)
 */
public record CapabilityDescriptor(
        String code,
        String name,
        String intentDesc,
        List<String> examples,
        List<String> keywords,
        List<String> regexPatterns,
        List<String> exactPhrases) {

    public CapabilityDescriptor {
        examples = examples == null ? List.of() : List.copyOf(examples);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        regexPatterns = regexPatterns == null ? List.of() : List.copyOf(regexPatterns);
        exactPhrases = exactPhrases == null ? List.of() : List.copyOf(exactPhrases);
    }

    /** 便捷构造:常规能力用 keywords+examples 即可,规则项留空 */
    public static CapabilityDescriptor of(String code, String name, String intentDesc,
                                          List<String> examples, List<String> keywords) {
        return new CapabilityDescriptor(code, name, intentDesc, examples, keywords, List.of(), List.of());
    }
}
