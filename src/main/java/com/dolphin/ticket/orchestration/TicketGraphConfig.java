package com.dolphin.ticket.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.dolphin.ticket.agent.DiagnosisAgent;
import com.dolphin.ticket.agent.KbAnswerAgent;
import com.dolphin.ticket.agent.ReplyAgent;
import com.dolphin.ticket.agent.TriageAgent;
import com.dolphin.ticket.action.ActionExecutor;
import com.dolphin.ticket.action.ActionProposal;
import com.dolphin.ticket.action.ActionRegistry;
import com.dolphin.ticket.rag.DocIngestService;
import com.dolphin.ticket.rag.TicketRetriever;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.TicketService;
import com.dolphin.ticket.orchestration.state.TicketState;
import com.dolphin.sla.SlaPriorityRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 云服务工单多智能体图编排。
 *
 * <p>流程:START → triage(分诊) → 条件边{咨询→kbAnswer, 故障→diagnosis, 高危/超范围→escalateHuman}
 * → reply(撰写终稿) → 条件边{诊断建议不可信→escalateHuman, 有待执行写操作→approval(人工审批, interruptBefore 中断), 无→followup}
 * → execute(执行写操作+真实验证) → 条件边{验证失败/被拒→escalateHuman, 通过→followup(结单)} → END;escalateHuman → END。
 *
 * <p>HITL(人在回路)实现方式(经运行时探针验证的 graph-core 1.0.0.2 语义):
 * <ul>
 *   <li>CompileConfig.interruptBefore("approval"):首次 invoke 跑到 approval 前停止并落 checkpoint;</li>
 *   <li>审批接口先调 {@link ApprovalService#recordDecision} 记录决策,再 updateState + resume 续跑;</li>
 *   <li>探针发现:节点 action 收到的是 CompiledGraph 共享模板 state,看不到 HumanFeedback/checkpoint
 *       对状态的更新(state.approved 为 null),但某节点返回的 key 对后续节点与条件边可见。
 *       因此 approval 节点从 ApprovalService(按工单号)读取决策并写回 APPROVED key,批准时同步签发
 *       一次性审批令牌;execute 节点从 ApprovalService 取令牌传入 ActionExecutor,门禁凭令牌放行;</li>
 *   <li>checkpoint(DB saver,按 threadId=工单号隔离)充当会话记忆,支撑中断-恢复。</li>
 * </ul>
 *
 * <p>并发根治(P0 阶段二):本配置只暴露 {@link StateGraph} 图定义 bean(无状态,可共享),
 * 编译收敛到 {@link TicketGraphCompiler#compile()}——每请求新建 CompiledGraph,
 * 各请求持有独立模板 state,彻底消除单例 CompiledGraph 共享模板的跨请求串扰。
 */
@Configuration
public class TicketGraphConfig {

    private static final Logger log = LoggerFactory.getLogger(TicketGraphConfig.class);

    /** 节点名常量 */
    public static final String NODE_TRIAGE = "triage";
    public static final String NODE_DIAGNOSIS = "diagnosis";
    public static final String NODE_KB_ANSWER = "kbAnswer";
    public static final String NODE_REPLY = "reply";
    public static final String NODE_APPROVAL = "approval";
    public static final String NODE_EXECUTE = "execute";
    public static final String NODE_FOLLOWUP = "followup";
    public static final String NODE_ESCALATE = "escalateHuman";

    @Bean
    public StateGraph ticketStateGraph(TriageAgent triageAgent,
                                       DiagnosisAgent diagnosisAgent,
                                       KbAnswerAgent kbAnswerAgent,
                                       ReplyAgent replyAgent,
                                       TicketRetriever ticketRetriever,
                                       TicketService ticketService,
                                       ApprovalService approvalService,
                                       ActionRegistry actionRegistry,
                                       ActionExecutor actionExecutor,
                                       DocIngestService docIngestService,
                                       com.dolphin.ticket.service.QualityReviewService qualityReviewService,
                                       com.dolphin.ticket.service.HumanTicketPool humanTicketPool) throws GraphStateException {

        // 状态工厂:注册所有共享 key 的合并策略;序列化器改用 Jackson(默认 Gson 不在 classpath)
        StateGraph graph = new StateGraph(() -> {
            OverAllState state = new OverAllState();
            state.registerKeyAndStrategy(TicketState.keyStrategies());
            return state;
        }, new TicketStateSerializer());

        // ---- 分诊:原始文本 → 分类结果 + SLA 优先级 ----
        graph.addNode(NODE_TRIAGE, node_async(state -> {
            String raw = TicketState.str(state, TicketState.RAW_MESSAGE, "");
            String ticketId = TicketState.str(state, TicketState.TICKET_ID, "");
            Map<String, Object> classification = triageAgent.classify(raw);
            // SLA 优先级:分诊结论 + 原文宕机信号的确定性规则推导(不依赖模型自觉),
            // 随分诊即时落库,支撑 SlaMonitor 超时巡检与指标 API 的优先级分布
            String priority = SlaPriorityRules.derive(
                    String.valueOf(classification.getOrDefault("type", "")), raw);
            classification.put("priority", priority);
            ticketService.updatePriority(ticketId, priority);
            log.debug("[graph] triage 结果: {} priority={}", classification, priority);
            return Map.of(TicketState.CLASSIFICATION, classification);
        }));

        // ---- 故障诊断:挂只读工具产出报告 + 结构化动作建议(PROPOSAL 契约行) ----
        graph.addNode(NODE_DIAGNOSIS, node_async(state -> {
            String raw = TicketState.str(state, TicketState.RAW_MESSAGE, "");
            Map<String, Object> classification = TicketState.map(state, TicketState.CLASSIFICATION);
            List<String> similar = ticketRetriever.retrieve(raw, 3);
            DiagnosisAgent.DiagnosisOutcome outcome =
                    diagnosisAgent.diagnose(raw, classification, String.join("\n", similar),
                            TicketState.str(state, TicketState.CONVERSATION_HISTORY, ""));
            Map<String, Object> out = new HashMap<>();
            out.put(TicketState.DIAGNOSIS_REPORT, outcome.report());
            out.put(TicketState.SIMILAR_TICKETS, similar);
            out.put(TicketState.PENDING_ACTION,
                    outcome.proposal() == null ? "" : outcome.proposal().toStateString());
            // 建议不可信(契约缺失/格式非法/低置信度)时置位,回复后路由直接收敛到转人工
            out.put(TicketState.PROPOSAL_INVALID, outcome.proposalInvalid());
            return out;
        }));

        // ---- 知识问答:咨询类工单直接生成答案 ----
        graph.addNode(NODE_KB_ANSWER, node_async(state -> {
            String raw = TicketState.str(state, TicketState.RAW_MESSAGE, "");
            Map<String, Object> classification = TicketState.map(state, TicketState.CLASSIFICATION);
            List<String> similar = ticketRetriever.retrieve(raw, 3);
            String answer = kbAnswerAgent.answer(raw, classification, similar,
                    TicketState.str(state, TicketState.CONVERSATION_HISTORY, ""));
            Map<String, Object> out = new HashMap<>();
            out.put(TicketState.REPLY_DRAFT, answer == null ? "" : answer);
            out.put(TicketState.SIMILAR_TICKETS, similar);
            return out;
        }));

        // ---- 回复撰写:把诊断报告/知识答案润色为客户可见终稿 ----
        graph.addNode(NODE_REPLY, node_async(state -> {
            String raw = TicketState.str(state, TicketState.RAW_MESSAGE, "");
            Map<String, Object> classification = TicketState.map(state, TicketState.CLASSIFICATION);
            String diagnosisReport = TicketState.str(state, TicketState.DIAGNOSIS_REPORT, "");
            String content = diagnosisReport.isBlank()
                    ? TicketState.str(state, TicketState.REPLY_DRAFT, "")
                    : diagnosisReport;
            String draft = replyAgent.compose(raw, classification, content,
                    TicketState.str(state, TicketState.CONVERSATION_HISTORY, ""));
            return Map.of(TicketState.REPLY_DRAFT, draft == null ? "" : draft);
        }));

        // ---- 人工审批:interruptBefore 在此节点前中断;恢复后从 ApprovalService 读决策 ----
        graph.addNode(NODE_APPROVAL, node_async(state -> {
            String ticketId = TicketState.str(state, TicketState.TICKET_ID, "");
            // 探针发现:此节点看不到 HumanFeedback/checkpoint 写入的 approved,故决策走 ApprovalService
            boolean approved = approvalService.isApproved(ticketId);
            // 批准时签发一次性审批令牌:绑定工单+动作+参数摘要,执行器凭令牌放行(拒绝不签发)
            if (approved) {
                ActionProposal proposal = ActionProposal.parse(
                        TicketState.str(state, TicketState.PENDING_ACTION, ""));
                if (proposal != null) {
                    approvalService.grantToken(ticketId, proposal);
                }
            }
            log.debug("[graph] approval 读取决策 ticketId={} approved={}", ticketId, approved);
            return Map.of(TicketState.APPROVED, approved);
        }));

        // ---- 执行:动作执行器统一管线(白名单→审批令牌门禁→幂等→提交→真实验证) ----
        graph.addNode(NODE_EXECUTE, node_async(state -> {
            String ticketId = TicketState.str(state, TicketState.TICKET_ID, "");
            ActionProposal proposal = ActionProposal.parse(TicketState.str(state, TicketState.PENDING_ACTION, ""));
            // 从 ApprovalService 取本工单令牌传入(免审批动作无令牌,传 null 即可)
            String approvalToken = proposal == null ? null : approvalService.peekToken(ticketId);
            ActionExecutor.Result execResult = proposal == null
                    ? null
                    : actionExecutor.execute(ticketId, proposal, approvalToken);
            return Map.of(
                    TicketState.EXECUTE_RESULT,
                    execResult == null ? "无待执行动作,未执行任何写操作" : execResult.message(),
                    // 终态供条件边分流:VERIFY_FAILED/REJECTED → 转人工,其余 → 结单
                    TicketState.EXECUTE_STATUS,
                    execResult == null ? ActionExecutor.Status.SUCCESS.name() : execResult.status().name());
        }));

        // ---- 结单:汇总最终回复并更新工单状态 ----
        graph.addNode(NODE_FOLLOWUP, node_async(state -> {
            String ticketId = TicketState.str(state, TicketState.TICKET_ID, "");
            String draft = TicketState.str(state, TicketState.REPLY_DRAFT, "");
            String executeResult = TicketState.str(state, TicketState.EXECUTE_RESULT, "");
            String finalReply = executeResult.isBlank() ? draft : draft + "\n\n处理结果:" + executeResult;
            ticketService.updateStatus(ticketId, TicketService.STATUS_CLOSED);
            approvalService.clear(ticketId);
            // 诊断回流:结单结论写入向量库,后续相似工单/告警可语义检索到本次处理经验
            // (Embedding 不可用时仅告警,不影响结单主流程)
            try {
                docIngestService.ingest("ticket-" + ticketId, finalReply);
            } catch (Exception e) {
                log.warn("[graph] 结单结论回流向量库失败 ticketId={}: {}", ticketId, e.getMessage());
            }
            // 异步质检:结单后审查终稿,不合格重开人工复核(独立线程池,不阻塞结单主链路)
            qualityReviewService.reviewAsync(ticketId, finalReply);
            return Map.of(TicketState.FINAL_REPLY, finalReply);
        }));

        // ---- 转人工:高危/超范围工单直接升级;写操作验证失败/被拒时携带执行详情升级 ----
        graph.addNode(NODE_ESCALATE, node_async(state -> {
            String ticketId = TicketState.str(state, TicketState.TICKET_ID, "");
            ticketService.updateStatus(ticketId, TicketService.STATUS_ESCALATED);
            String executeResult = TicketState.str(state, TicketState.EXECUTE_RESULT, "");
            // 归因:执行未通过 > 建议不可信 > 分诊判定(高危/超范围)
            String classificationType = String.valueOf(TicketState.map(state, TicketState.CLASSIFICATION)
                    .getOrDefault("type", ""));
            String reason = !executeResult.isBlank() ? "处置执行未通过(验证失败或被拒)"
                    : TicketState.bool(state, TicketState.PROPOSAL_INVALID) ? "诊断动作建议不可信"
                    : ("高危".equals(classificationType) || "超范围".equals(classificationType))
                            ? classificationType + "诉求,需人工介入"
                            : "流程收敛到转人工";
            String detail = executeResult.isBlank()
                    ? "原始诉求:" + TicketState.str(state, TicketState.RAW_MESSAGE, "")
                    : executeResult;
            // 落人工队列:专员经 /api/human-tickets 认领/完结
            humanTicketPool.add(ticketId, reason, detail);
            String finalReply = ("该工单已转人工专员处理(已进入人工队列,工单号 %s,原因:%s),请留意后续回复。"
                    .formatted(ticketId, reason))
                    + (executeResult.isBlank() ? "" : "执行与验证详情:" + executeResult);
            return Map.of(TicketState.FINAL_REPLY, finalReply);
        }));

        // ---- 边 ----
        graph.addEdge(StateGraph.START, NODE_TRIAGE);
        // 分诊后按类型路由
        graph.addConditionalEdges(NODE_TRIAGE,
                edge_async(state -> {
                    String type = String.valueOf(TicketState.map(state, TicketState.CLASSIFICATION)
                            .getOrDefault("type", "咨询"));
                    return switch (type) {
                        case "故障" -> "diagnosis";
                        case "高危", "超范围" -> "escalate";
                        default -> "kbAnswer";
                    };
                }),
                Map.of(
                        "kbAnswer", NODE_KB_ANSWER,
                        "diagnosis", NODE_DIAGNOSIS,
                        "escalate", NODE_ESCALATE));
        graph.addEdge(NODE_DIAGNOSIS, NODE_REPLY);
        graph.addEdge(NODE_KB_ANSWER, NODE_REPLY);
        // 回复后四分支:建议不可信→转人工;无动作→结单;需审批动作→approval→execute;免审批动作→直接 execute
        // 审批要求由动作注册表声明决定;未知动作保守进审批兜底(执行阶段会被白名单拒绝并留痕)
        graph.addConditionalEdges(NODE_REPLY,
                edge_async(state -> {
                    // 诊断建议不可信(契约缺失/格式非法/低置信度):失败路径统一收敛到转人工,绝不猜测执行
                    if (TicketState.bool(state, TicketState.PROPOSAL_INVALID)) {
                        return "invalid";
                    }
                    ActionProposal proposal = ActionProposal.parse(
                            TicketState.str(state, TicketState.PENDING_ACTION, ""));
                    if (proposal == null) {
                        return "noAction";
                    }
                    return actionRegistry.get(proposal.action())
                            .map(d -> d.requireApproval() ? "needApproval" : "autoExecute")
                            .orElse("needApproval");
                }),
                Map.of(
                        "needApproval", NODE_APPROVAL,
                        "autoExecute", NODE_EXECUTE,
                        "noAction", NODE_FOLLOWUP,
                        "invalid", NODE_ESCALATE));
        graph.addEdge(NODE_APPROVAL, NODE_EXECUTE);
        // 执行后按验证终态分流:验证失败/被拒 → 转人工跟进;成功/幂等跳过 → 正常结单
        graph.addConditionalEdges(NODE_EXECUTE,
                edge_async(state -> {
                    String st = TicketState.str(state, TicketState.EXECUTE_STATUS,
                            ActionExecutor.Status.SUCCESS.name());
                    return ActionExecutor.Status.VERIFY_FAILED.name().equals(st)
                            || ActionExecutor.Status.REJECTED.name().equals(st) ? "escalate" : "followup";
                }),
                Map.of("followup", NODE_FOLLOWUP, "escalate", NODE_ESCALATE));
        graph.addEdge(NODE_FOLLOWUP, StateGraph.END);
        graph.addEdge(NODE_ESCALATE, StateGraph.END);

        // 只构建图定义;编译收敛到 TicketGraphCompiler(每请求新建 CompiledGraph,并发根治)
        return graph;
    }
}
