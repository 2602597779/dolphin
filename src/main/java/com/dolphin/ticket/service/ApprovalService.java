package com.dolphin.ticket.service;

import com.dolphin.ticket.action.ActionProposal;
import com.dolphin.ticket.repo.ApprovalDecisionEntity;
import com.dolphin.ticket.repo.ApprovalDecisionRepository;
import com.dolphin.ticket.action.ApprovalTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 人工审批决策服务(P0 起持久化)。
 *
 * <p>探针验证结论:graph-core 1.0.0.2 中,节点 action 收到的是 CompiledGraph 的共享模板 state,
 * 无法可靠看到 HumanFeedback/checkpoint 对状态的更新;因此审批决策不依赖图状态传递,
 * 而由本服务承载:approve 接口先 {@link #recordDecision} 写入决策,再 updateState+resume,
 * approval 节点执行时从本服务读取决策并写回状态 key。
 *
 * <p>决策不再是裸 boolean:{@link Decision} 携带审批人、时间与理由留痕;批准时经
 * {@link ApprovalTokenService} 签发一次性审批令牌(绑定工单+动作+参数摘要),
 * 执行链路凭令牌放行——拒绝不签发,伪造/过期/重放均无法通过执行器门禁。
 *
 * <p>持久化语义:决策按 ticket_id upsert 落库(重启后审批链路可续);
 * {@link #clear} 不再删行——决策与令牌全程留痕,仅置空令牌。
 * TODO:替换为真实审批系统(飞书审批/工单审批流)。
 */
@Component
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /**
     * 审批决策留痕。
     *
     * @param approved  是否批准
     * @param approver  审批人(REST 请求体或会话标识)
     * @param decidedAt 决策时间
     * @param reason    审批理由/备注(可空)
     * @param token     批准时签发的一次性审批令牌(拒绝或未签发为 null)
     */
    public record Decision(boolean approved, String approver, Instant decidedAt, String reason, String token) {
    }

    private final ApprovalDecisionRepository repository;
    private final ApprovalTokenService approvalTokenService;

    public ApprovalService(ApprovalDecisionRepository repository, ApprovalTokenService approvalTokenService) {
        this.repository = repository;
        this.approvalTokenService = approvalTokenService;
    }

    /** 记录某工单的人工审批决策(审批人/理由为空时以"未署名"兜底留痕),按工单号 upsert */
    @Transactional
    public void recordDecision(String ticketId, boolean approved, String approver, String reason) {
        String who = (approver == null || approver.isBlank()) ? "未署名" : approver;
        ApprovalDecisionEntity entity = repository.findByTicketId(ticketId)
                .orElseGet(() -> new ApprovalDecisionEntity(ticketId, approved, who, Instant.now(), reason, null));
        entity.setApproved(approved);
        entity.setApprover(who);
        entity.setDecidedAt(Instant.now());
        entity.setReason(reason);
        entity.setToken(null); // 新决策覆盖旧决策:旧令牌随之作废
        repository.save(entity);
        log.info("[approval] 决策留痕 ticketId={} approved={} approver={} reason={}", ticketId, approved, who, reason);
    }

    /**
     * 批准通过后为该工单的动作建议签发一次性令牌(拒绝决策不签发)。
     * 由 approval 节点在 resume 链路中调用——此时才能从图状态拿到权威的待执行动作。
     *
     * @return 令牌字符串;工单无批准决策时返回 null
     */
    @Transactional
    public String grantToken(String ticketId, ActionProposal proposal) {
        Optional<ApprovalDecisionEntity> found = repository.findByTicketId(ticketId);
        if (found.isEmpty() || !found.get().isApproved()) {
            return null;
        }
        String token = approvalTokenService.issue(ticketId, proposal);
        ApprovalDecisionEntity entity = found.get();
        entity.setToken(token);
        repository.save(entity);
        log.info("[approval] 令牌签发 ticketId={} action={} target={} approver={}",
                ticketId, proposal.action(), proposal.target(), entity.getApprover());
        return token;
    }

    /** 取本工单当前令牌(供执行节点传入执行器;消费与校验由执行器完成) */
    @Transactional(readOnly = true)
    public String peekToken(String ticketId) {
        return repository.findByTicketId(ticketId).map(ApprovalDecisionEntity::getToken).orElse(null);
    }

    /** 读取完整决策留痕(审计/测试用) */
    @Transactional(readOnly = true)
    public Optional<Decision> decisionOf(String ticketId) {
        return repository.findByTicketId(ticketId)
                .map(e -> new Decision(e.isApproved(), e.getApprover(), e.getDecidedAt(), e.getReason(), e.getToken()));
    }

    /** 是否已做出决策 */
    @Transactional(readOnly = true)
    public boolean hasDecision(String ticketId) {
        return repository.findByTicketId(ticketId).isPresent();
    }

    /** 读取决策,未决策时默认拒绝(安全兜底:写操作未经明确批准不执行) */
    @Transactional(readOnly = true)
    public boolean isApproved(String ticketId) {
        return repository.findByTicketId(ticketId)
                .map(ApprovalDecisionEntity::isApproved)
                .orElse(false);
    }

    /** 结单后清理令牌(决策行永久保留作审计留痕,避免令牌残留可查) */
    @Transactional
    public void clear(String ticketId) {
        repository.findByTicketId(ticketId).ifPresent(e -> {
            if (e.getToken() != null) {
                e.setToken(null);
                repository.save(e);
            }
        });
    }
}
