package com.dolphin.ticket.orchestration.state;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单共享状态定义。
 *
 * <p>graph-core(1.0.0.2)的状态本质是 {@link OverAllState} 内部的一个 Map,
 * 这里用常量 key + 静态辅助 getter 的薄封装方式约定各节点之间传递的数据,
 * 并通过 {@link #keyStrategies()} 向图注册每个 key 的合并策略(骨架阶段全部用覆盖写 ReplaceStrategy)。
 *
 * <p>注意:节点返回值里只有注册过策略的 key 才会被写入状态,未注册的 key 会被静默丢弃。
 */
public final class TicketState {

    /** 工单号 */
    public static final String TICKET_ID = "ticketId";

    /** 用户提交的原始工单文本 */
    public static final String RAW_MESSAGE = "rawMessage";

    /** 分诊分类结果,Map 结构:{product, type, urgency}(产品/类型/紧急度) */
    public static final String CLASSIFICATION = "classification";

    /** 故障诊断报告(文本) */
    public static final String DIAGNOSIS_REPORT = "diagnosisReport";

    /** 历史相似工单列表(List<String>) */
    public static final String SIMILAR_TICKETS = "similarTickets";

    /** 回复草稿(文本) */
    public static final String REPLY_DRAFT = "replyDraft";

    /** 待执行的写操作(如 "restart:i-123"),空字符串表示无需审批 */
    public static final String PENDING_ACTION = "pendingAction";

    /** 诊断动作建议不可信(Boolean):契约行缺失/格式非法/低置信度,路由收敛到转人工 */
    public static final String PROPOSAL_INVALID = "proposalInvalid";

    /** 人工审批结果(Boolean) */
    public static final String APPROVED = "approved";

    /** 写操作执行结果(文本) */
    public static final String EXECUTE_RESULT = "executeResult";

    /** 写操作执行终态(ActionExecutor.Status.name(),图编排据此把验证失败/被拒分流到转人工) */
    public static final String EXECUTE_STATUS = "executeStatus";

    /** 最终结单回复(文本) */
    public static final String FINAL_REPLY = "finalReply";

    private TicketState() {
    }

    /**
     * 注册所有状态 key 的合并策略,供 OverAllStateFactory 使用。
     */
    public static Map<String, KeyStrategy> keyStrategies() {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        for (String key : List.of(TICKET_ID, RAW_MESSAGE, CLASSIFICATION, DIAGNOSIS_REPORT,
                SIMILAR_TICKETS, REPLY_DRAFT, PENDING_ACTION, PROPOSAL_INVALID, APPROVED,
                EXECUTE_RESULT, EXECUTE_STATUS, FINAL_REPLY)) {
            strategies.put(key, new ReplaceStrategy());
        }
        return strategies;
    }

    /** 读取字符串值,缺失返回 null */
    public static String str(OverAllState state, String key) {
        return state.value(key).map(String::valueOf).orElse(null);
    }

    /** 读取字符串值,缺失返回默认值 */
    public static String str(OverAllState state, String key, String defaultValue) {
        String v = str(state, key);
        return v == null ? defaultValue : v;
    }

    /** 读取 Map 值(如 classification),缺失返回空 Map */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(OverAllState state, String key) {
        Object v = state.value(key).orElse(null);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    /** 读取 List 值(如 similarTickets),缺失返回空列表 */
    @SuppressWarnings("unchecked")
    public static List<Object> list(OverAllState state, String key) {
        Object v = state.value(key).orElse(null);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    /** 读取 Boolean 值(如 approved),缺失返回 false */
    public static boolean bool(OverAllState state, String key) {
        Object v = state.value(key).orElse(null);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }
}
