package com.dolphin.ticket.action;

import java.time.Duration;

/**
 * 动作描述符:动作目录(白名单)中一个可执行动作的完整元数据声明。
 *
 * <p>设计原则:审批要求、幂等策略、验证方式都是<b>数据</b>而不是散落在流程里的 if-else,
 * 新增一类写操作 = 注册一个描述符 + 实现一个处理器,编排代码零改动。
 *
 * @param name           动作唯一名(与 {@link ActionProposal#action()} 对应)
 * @param displayName    人类可读名称(审批界面/审计日志展示)
 * @param risk           风险级别
 * @param requireApproval 是否需要人工审批(可逆低风险配置类动作可为 false)
 * @param idempotent     是否幂等(幂等动作允许失败重试,且执行器做重复执行拦截)
 * @param verifyMode     执行后的结果验证范式
 * @param verifyTimeout  验证容忍时长(由动作的生效延迟特性决定,如 DNS 需按 TTL 放宽)
 */
public record ActionDescriptor(
        String name,
        String displayName,
        RiskLevel risk,
        boolean requireApproval,
        boolean idempotent,
        VerifyMode verifyMode,
        Duration verifyTimeout
) {
}
