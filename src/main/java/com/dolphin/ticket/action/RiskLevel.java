package com.dolphin.ticket.action;

/**
 * 动作风险级别:决定审批与风控策略的元数据(供注册表与审批门禁消费)。
 */
public enum RiskLevel {

    /** 只读查询,无副作用,免审批 */
    READ_ONLY,

    /** 一般写操作(资源/配置变更),是否审批由注册表按可逆性决定 */
    WRITE,

    /** 资金类写操作,一律强制审批 */
    FINANCIAL
}
