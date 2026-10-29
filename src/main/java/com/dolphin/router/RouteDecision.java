package com.dolphin.router;

/**
 * 路由决策结果。
 *
 * @param capabilityCode 目标能力编码
 * @param matchedBy      命中方式(STICKY / EXACT / REGEX / KEYWORD / LLM / FALLBACK),用于观测与调试
 * @param confidence     置信度 0~1
 */
public record RouteDecision(String capabilityCode, String matchedBy, double confidence) {
}
