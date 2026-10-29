package com.dolphin.ticket.action;

import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.service.CloudApi;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作执行器:写操作链路上唯一的执行入口,LLM 到此为止,以下全是确定性代码。
 *
 * <p>统一管线(所有动作共用,无动作特化分支):
 * <ol>
 *   <li>白名单校验:动作必须在 {@link ActionRegistry} 中,否则拒绝;</li>
 *   <li>审批门禁:描述符 requireApproval=true 时必须携带有效审批令牌且
 *       {@link ApprovalTokenService#validateAndConsume} 通过(二次防御,即使图编排被绕过,
 *       这里也会拒绝;boolean 决策已升级为防伪/绑定/限时/一次性令牌,拒绝原因写审计);</li>
 *   <li>幂等拦截:同一工单+动作+目标只生效一次,防止重复 resume 造成重复重启;</li>
 *   <li>基线采样:提交前记录重启序号/规则版本/旧流水号,供验证阶段证明"本次变更真的发生";</li>
 *   <li>提交指令:调用云服务门面(真实实现=带 ClientToken 幂等 token 的签名 API 调用);</li>
 *   <li>结果验证:按描述符的 {@link VerifyMode} 由 {@link ResultVerifier} 真实轮询/探测/对账,
 *       并比对提交前基线,"受理≠完成";验证失败收敛为 {@link Status#VERIFY_FAILED},图编排据此路由转人工;</li>
 *   <li>全程审计留痕。</li>
 * </ol>
 *
 * <p>TODO:幂等表换数据库持久化(重启进程后仍可去重);云 API 仍为模拟器(见 CloudApi 默认实现 CloudApiService);
 * 拒绝事件上报风控系统。
 */
@Component
public class ActionExecutor {

    /** 执行终态:VERIFY_FAILED=提交成功但验证未通过(实际效果未达成),由图编排路由转人工 */
    public enum Status { SUCCESS, REJECTED, SKIPPED, VERIFY_FAILED }

    /** 执行结果:状态 + 面向结单/审批界面的说明 */
    public record Result(Status status, String message) {
        static Result success(String message) { return new Result(Status.SUCCESS, message); }
        static Result rejected(String message) { return new Result(Status.REJECTED, message); }
        static Result skipped(String message) { return new Result(Status.SKIPPED, message); }
        static Result verifyFailed(String message) { return new Result(Status.VERIFY_FAILED, message); }
    }

    private final ActionRegistry registry;
    private final CloudApi cloudApiService;
    private final AuditLogger auditLogger;
    private final ApprovalTokenService approvalTokenService;
    private final ResultVerifier resultVerifier;

    /** 幂等去重表:key = ticketId|action|target。骨架内存实现,见类注释 TODO */
    private final Map<String, Result> executed = new ConcurrentHashMap<>();

    public ActionExecutor(ActionRegistry registry, CloudApi cloudApiService, AuditLogger auditLogger,
                          ApprovalTokenService approvalTokenService, ResultVerifier resultVerifier) {
        this.registry = registry;
        this.cloudApiService = cloudApiService;
        this.auditLogger = auditLogger;
        this.approvalTokenService = approvalTokenService;
        this.resultVerifier = resultVerifier;
    }

    /**
     * 执行一个动作建议。
     *
     * @param ticketId      工单号(审计与幂等维度)
     * @param proposal      结构化动作建议(来自诊断环节)
     * @param approvalToken 审批令牌(来自 ApprovalService,人工批准时签发;免审批动作可为 null)
     */
    public Result execute(String ticketId, ActionProposal proposal, String approvalToken) {
        // 1. 白名单校验:不在动作目录内的建议一律拒绝(兜底也应先转人工,而非猜测执行)
        var descOpt = registry.get(proposal.action());
        if (descOpt.isEmpty()) {
            auditLogger.log(ticketId, proposal.action() + ":REJECTED", "动作不在白名单 target=" + proposal.target());
            return Result.rejected("拒绝执行:动作不在白名单内 → " + proposal.action());
        }
        var desc = descOpt.get();

        // 2. 审批门禁:按描述符声明判定,需审批动作必须持有绑定本工单本动作的有效令牌(一次性消费)
        if (desc.requireApproval()) {
            var tokenResult = approvalTokenService.validateAndConsume(ticketId, proposal, approvalToken);
            if (!tokenResult.valid()) {
                auditLogger.log(ticketId, desc.name() + ":REJECTED",
                        "审批令牌校验失败:" + tokenResult.reason() + " target=" + proposal.target());
                return Result.rejected("拒绝执行:" + desc.displayName() + "属于" + desc.risk()
                        + "级写操作,未经人工审批不得执行(原因:" + tokenResult.reason() + ")");
            }
            auditLogger.log(ticketId, desc.name() + ":APPROVAL-TOKEN",
                    tokenResult.reason() + " target=" + proposal.target());
        }

        // 3. 幂等拦截:重复执行直接返回首次结果,不产生第二次副作用
        String dedupeKey = ticketId + "|" + desc.name() + "|" + proposal.target();
        if (desc.idempotent()) {
            Result prev = executed.get(dedupeKey);
            if (prev != null) {
                auditLogger.log(ticketId, desc.name() + ":SKIPPED", "幂等拦截,已执行过 key=" + dedupeKey);
                return Result.skipped("同一工单同一动作已执行过,本次跳过(幂等)。首次结果:" + prev.message());
            }
        }

        auditLogger.log(ticketId, desc.name(), "target=" + proposal.target()
                + " approval=" + (desc.requireApproval() ? "令牌已验证" : "免审批") + " risk=" + desc.risk());

        // 4. 变更前基线:必须在提交之前采样,验证阶段用它证明本次变更真的发生(受理≠完成)
        ResultVerifier.Baseline baseline;
        try {
            baseline = resultVerifier.capture(desc, proposal);
        } catch (Exception e) {
            // 没有基线就无法证明变更生效:宁可拒绝执行并转人工,也不做无法验证的盲写
            auditLogger.log(ticketId, desc.name() + ":BASELINE-FAILED", "基线采样异常:" + e.getMessage());
            return Result.rejected("拒绝执行:无法采样" + desc.displayName() + "的变更前基线(" + e.getMessage()
                    + "),不能保证验证闭环,已转人工");
        }
        auditLogger.log(ticketId, desc.name() + ":BASELINE",
                baseline.summary() + " target=" + proposal.target());

        // 5. 提交指令(受理)
        String submitted = switch (desc.name()) {
            case "restartInstance" -> cloudApiService.restartInstance(proposal.target());
            case "refundOrder" -> cloudApiService.refundOrder(proposal.target());
            case "authorizeSecurityGroup" -> cloudApiService.authorizeSecurityGroup(
                    proposal.target(), Integer.parseInt(String.valueOf(proposal.params().getOrDefault("port", "443"))));
            default -> throw new IllegalStateException("注册表动作缺少处理器: " + desc.name());
        };

        // 6. 结果验证:不信任受理返回值,按描述符范式真实轮询/探测/对账并比对基线(受理≠完成)
        ResultVerifier.Outcome verification;
        try {
            verification = resultVerifier.verify(desc, proposal, baseline);
        } catch (Exception e) {
            // 验证基础设施异常(I/O/中断)按验证失败收敛,交由转人工,不让异常冲垮结单链路
            verification = new ResultVerifier.Outcome(false, "验证过程异常:" + e.getMessage());
        }
        auditLogger.log(ticketId, desc.name() + ":VERIFY",
                (verification.verified() ? "通过:" : "失败:") + verification.detail());

        Result result = verification.verified()
                ? Result.success("提交:" + submitted + " | 验证:" + verification.detail())
                : Result.verifyFailed("提交:" + submitted + " | 验证失败:" + verification.detail() + " → 转人工跟进");
        // 幂等表记录提交事实(含验证失败):同一工单+动作+目标的副作用只允许发生一次
        if (desc.idempotent()) {
            executed.put(dedupeKey, result);
        }
        return result;
    }
}
