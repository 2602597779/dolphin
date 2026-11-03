package com.dolphin.ticket.orchestration;

import com.dolphin.ticket.api.TicketController;
import com.dolphin.ticket.repo.ApprovalDecisionEntity;
import com.dolphin.ticket.repo.ApprovalDecisionRepository;
import com.dolphin.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 并发根治回归(spec 第七节):多工单并发提交/审批互不串扰。
 *
 * <p>历史缺陷:单例 CompiledGraph 共享模板 state,并发请求互相覆盖模板数据。
 * 根治方案(TicketGraphCompiler)每请求编译独立图,checkpoint 经 DB saver 按
 * threadId=工单号隔离。本测试全并发复现危险场景:
 * <ol>
 *   <li>两线程<b>同时</b>提交"重启"剧本工单(offline 桩分诊为 ECS 故障 → 诊断给出
 *       restartInstance 写操作建议 → 图在 approval 节点前中断),两单都应到达
 *       PENDING_APPROVAL 且工单号唯一(同毫秒并发 create 不碰撞);</li>
 *   <li>两线程<b>同时</b>审批,各自从 DB checkpoint 水合自己的图并 resume:
 *       各单应独立走完 审批→执行重启→验证→结单,终态 CLOSED、回复携带本单执行回执,
 *       而非被对方干扰成令牌校验失败(REJECTED→ESCALATED)或回复错乱;</li>
 *   <li>台账与决策留痕:两单的批准决策独立落库。</li>
 * </ol>
 *
 * <p>环境:offline profile(LLM/Embedding 全桩,零外部依赖);数据源覆盖为 H2 内存库,
 * 不触碰 ./data 文件库;sim 重启延迟压到 0.5s + 轮询 200ms 加速验证闭环。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dolphin-conc;DB_CLOSE_DELAY=-1",
        "dolphin.sim.restart-latency=PT0.5S",
        "dolphin.verify.poll-interval=PT0.2S",
        "dolphin.rag.vector-store-path=target/dolphin-conc-vector-store.json"
})
@ActiveProfiles("offline")
class TicketGraphConcurrencyTest {

    private static final long JOIN_TIMEOUT_SECONDS = 60;

    /** 重启剧本工单文案:命中 offline 桩的 restartInstance(需审批)剧本 */
    private static final String RESTART_MESSAGE = "ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复";

    @Autowired
    private TicketController ticketController;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private ApprovalDecisionRepository approvalDecisionRepository;

    @Test
    void concurrentSubmitAndApprove_ticketsDoNotCrosstalk() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // ---- 阶段一:两线程并发提交,都进审批中断 ----
            CountDownLatch submitGate = new CountDownLatch(1);
            List<CompletableFuture<Map<String, Object>>> submits = List.of(
                    CompletableFuture.supplyAsync(() -> submitAfter(submitGate), pool),
                    CompletableFuture.supplyAsync(() -> submitAfter(submitGate), pool));
            submitGate.countDown();
            List<Map<String, Object>> submitBodies = allOf(submits);

            String idA = (String) submitBodies.get(0).get("ticketId");
            String idB = (String) submitBodies.get(1).get("ticketId");
            assertThat(idA).isNotBlank();
            assertThat(idB).isNotBlank().isNotEqualTo(idA);
            for (Map<String, Object> body : submitBodies) {
                assertThat(body.get("status")).isEqualTo(TicketService.STATUS_PENDING_APPROVAL);
            }
            assertThat(ticketService.get(idA).orElseThrow().status())
                    .isEqualTo(TicketService.STATUS_PENDING_APPROVAL);
            assertThat(ticketService.get(idB).orElseThrow().status())
                    .isEqualTo(TicketService.STATUS_PENDING_APPROVAL);

            // ---- 阶段二:两线程并发审批,各自 resume 自己的 checkpoint ----
            CountDownLatch approveGate = new CountDownLatch(1);
            List<CompletableFuture<Map<String, Object>>> approves = List.of(
                    CompletableFuture.supplyAsync(() -> approveAfter(approveGate, idA), pool),
                    CompletableFuture.supplyAsync(() -> approveAfter(approveGate, idB), pool));
            approveGate.countDown();
            List<Map<String, Object>> approveBodies = allOf(approves);

            // 各单独立闭环:批准 → 执行重启(令牌门禁通过)→ 验证通过 → 结单
            assertThat(approveBodies.get(0).get("ticketId")).isEqualTo(idA);
            assertThat(approveBodies.get(1).get("ticketId")).isEqualTo(idB);
            for (Map<String, Object> body : approveBodies) {
                assertThat(body.get("status")).isEqualTo(TicketService.STATUS_CLOSED);
                String finalReply = String.valueOf(body.get("finalReply"));
                // 回复属于本单剧本:重启话术 + 携带本单执行回执(若串扰会出现令牌失败转人工或内容错乱)
                assertThat(finalReply).contains("i-abc123");
                assertThat(finalReply).contains("处理结果");
                assertThat(finalReply).contains("已向云平台提交实例 i-abc123");
            }
            assertThat(ticketService.get(idA).orElseThrow().status()).isEqualTo(TicketService.STATUS_CLOSED);
            assertThat(ticketService.get(idB).orElseThrow().status()).isEqualTo(TicketService.STATUS_CLOSED);

            // ---- 台账与决策留痕:两单各自的批准决策独立落库 ----
            Map<String, Boolean> decisions = approvalDecisionRepository.findAll().stream()
                    .collect(Collectors.toMap(ApprovalDecisionEntity::getTicketId,
                            ApprovalDecisionEntity::isApproved));
            assertThat(decisions).containsEntry(idA, true).containsEntry(idB, true);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 等闸门后提交重启剧本工单,返回响应体(直调传 Mock 请求=内部渠道,不走 HTTP 鉴权) */
    private Map<String, Object> submitAfter(CountDownLatch gate) {
        awaitGate(gate);
        return ticketController
                .submit(new TicketController.SubmitRequest(RESTART_MESSAGE), new MockHttpServletRequest())
                .getBody();
    }

    /** 等闸门后审批指定工单,返回响应体 */
    private Map<String, Object> approveAfter(CountDownLatch gate, String ticketId) {
        awaitGate(gate);
        return ticketController
                .approve(ticketId, new TicketController.ApproveRequest(true, "conc-test", "并发回归自动审批"),
                        new MockHttpServletRequest())
                .getBody();
    }

    private void awaitGate(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 聚合并发结果,任一异常/超时直接失败测试 */
    private List<Map<String, Object>> allOf(List<CompletableFuture<Map<String, Object>>> futures) throws Exception {
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return futures.stream()
                .map(f -> {
                    try {
                        return f.get(1, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }
}
