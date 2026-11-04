package com.dolphin.ticket.service;

import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.repo.HumanTaskRepository;
import com.dolphin.ticket.repo.TicketMessageEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link HumanTicketPool} 生命周期单测:入队 → 认领 → 完结,
 * 以及非法状态迁移(WAITING 直接完结/重复认领/完结后操作)被拒绝。
 * P0 起任务落库(真实 H2 内存库),同工单重复落队整行覆盖语义由主键 upsert 保证。
 */
@DataJpaTest
@Import(ConversationService.class)
class HumanTicketPoolTest {

    @Autowired
    private HumanTaskRepository repository;

    @Autowired
    private ConversationService conversationService;

    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final TicketService ticketService = mock(TicketService.class);

    private HumanTicketPool pool() {
        return new HumanTicketPool(repository, auditLogger, ticketService, conversationService);
    }

    @Test
    void addCreatesWaitingTask() {
        HumanTicketPool pool = pool();
        HumanTicketPool.HumanTask task = pool.add("T1", "高危诉求,需人工介入", "原始诉求:xxx");

        assertEquals(HumanTicketPool.STATUS_WAITING, task.status());
        assertEquals("T1", task.ticketId());
        assertEquals(1, pool.list(null).size());
        assertEquals(1, pool.list("WAITING").size());
        assertEquals(0, pool.list("CLAIMED").size());
    }

    @Test
    void claimMovesWaitingToClaimedWithOwner() {
        HumanTicketPool pool = pool();
        pool.add("T1", "原因", "详情");

        Optional<HumanTicketPool.HumanTask> claimed = pool.claim("T1", "专员A");
        assertTrue(claimed.isPresent());
        assertEquals(HumanTicketPool.STATUS_CLAIMED, claimed.get().status());
        assertEquals("专员A", claimed.get().owner());

        // 重复认领被拒(已不是 WAITING)
        assertTrue(pool.claim("T1", "专员B").isEmpty());
    }

    @Test
    void blankOwnerFallsBackToAnonymous() {
        HumanTicketPool pool = pool();
        pool.add("T1", "原因", "详情");
        assertEquals("anonymous", pool.claim("T1", " ").get().owner());
    }

    @Test
    void resolveRequiresClaimFirstAndClosesTicket() {
        HumanTicketPool pool = pool();
        pool.add("T1", "原因", "详情");

        // 未认领直接完结被拒
        assertTrue(pool.resolve("T1", "note").isEmpty());

        pool.claim("T1", "专员A");
        Optional<HumanTicketPool.HumanTask> resolved = pool.resolve("T1", "已人工处理恢复");
        assertTrue(resolved.isPresent());
        assertEquals(HumanTicketPool.STATUS_RESOLVED, resolved.get().status());
        // 完结联动工单状态转 CLOSED
        verify(ticketService).updateStatus("T1", TicketService.STATUS_CLOSED);
    }

    @Test
    void resolveAppendsHumanAnswerToThread() {
        HumanTicketPool pool = pool();
        pool.add("T1", "原因", "详情");
        pool.claim("T1", "专员A");
        pool.resolve("T1", "已人工处理恢复");

        // O2:人工完结把专员答复补进客户线程,线程闭环可回看
        var messages = conversationService.messages("T1");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo(TicketMessageEntity.ROLE_AGENT);
        assertThat(messages.get(0).content()).isEqualTo("人工处理完成: 已人工处理恢复");
    }

    @Test
    void resolveWithBlankNoteUsesDefaultAnswerAndDoesNotDuplicate() {
        HumanTicketPool pool = pool();
        pool.add("T1", "原因", "详情");
        pool.claim("T1", "专员A");
        pool.resolve("T1", " ");

        assertThat(conversationService.messages("T1"))
                .extracting(ConversationService.Message::content)
                .containsExactly("人工处理完成,工单已结单");

        // 重复 resolve 被状态过滤拦截,不重复落消息
        assertTrue(pool.resolve("T1", "again").isEmpty());
        assertThat(conversationService.messages("T1")).hasSize(1);
    }

    @Test
    void resolvedTicketCannotBeClaimedOrResolvedAgain() {
        HumanTicketPool pool = pool();
        pool.add("T1", "原因", "详情");
        pool.claim("T1", "专员A");
        pool.resolve("T1", "ok");

        assertTrue(pool.claim("T1", "专员B").isEmpty());
        assertTrue(pool.resolve("T1", "again").isEmpty());
    }

    @Test
    void unknownTicketClaimAndResolveReturnEmpty() {
        HumanTicketPool pool = pool();
        assertTrue(pool.claim("NOPE", "专员").isEmpty());
        assertTrue(pool.resolve("NOPE", "note").isEmpty());
        assertTrue(pool.get("NOPE").isEmpty());
    }

    @Test
    void reAddSameTicketReplacesWithWaiting() {
        HumanTicketPool pool = pool();
        pool.add("T1", "原因1", "详情1");
        pool.claim("T1", "专员A");
        // 同一工单再次升级(如图重跑):以最新一次为准,回到 WAITING
        pool.add("T1", "原因2", "详情2");

        assertEquals(1, pool.list(null).size());
        assertEquals("原因2", pool.get("T1").get().reason());
        assertEquals(HumanTicketPool.STATUS_WAITING, pool.get("T1").get().status());
        verify(auditLogger, org.mockito.Mockito.times(3)).log(anyString(), anyString(), anyString());
    }
}
