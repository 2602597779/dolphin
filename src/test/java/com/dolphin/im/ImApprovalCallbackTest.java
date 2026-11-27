package com.dolphin.im;

import com.dolphin.im.repo.ImCallbackRecordEntity;
import com.dolphin.im.repo.ImCallbackRecordRepository;
import com.dolphin.notify.NotifySender;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.TicketRoundService;
import com.dolphin.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IM 审批回调入口(平台无关)的端到端编排测试。
 *
 * <p>用真实装配(真实 TicketService / ApprovalService / 去重服务 / 审计,跑 H2)验证回调编排:
 * 路由 → 验签 → 幂等回放 → 状态闸门 → 落决策 → 续跑图 → 平台应答。
 * 图执行线程池被替换为 mock,因此 resume 任务不会真的跑图——这也正是断言点:
 * 回调只负责"把待审批工单推进到 PROCESSING 并把决策落库",图链路本身由既有测试覆盖。
 *
 * <p>平台协议用测试适配器(一段 KV 文本 + 一个签名头)模拟,与钉钉协议解耦;
 * 钉钉协议本身由 {@link DingTalkCallbackAdapterTest} / {@link DingTalkCryptoTest} 覆盖,
 * 本类只额外验证"真实钉钉适配器未配凭证时入口默认关闭"。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dolphin-im-callback;DB_CLOSE_DELAY=-1",
        "dolphin.rag.vector-store-path=target/dolphin-im-callback-vector-store.json"
}, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("offline")
class ImApprovalCallbackTest {

    /** 图执行线程池替换为 mock:回调链路只到"任务已入队"为止,不真跑图 */
    @MockitoBean(name = "ticketGraphExecutor")
    private ExecutorService graphExecutor;

    /** 通知通道替换为 mock:单独验证"挂起 → 发卡片"的时机(AFTER_COMMIT) */
    @MockitoBean(name = "directNotifySender")
    private NotifySender notifySender;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private TicketRoundService roundService;
    @Autowired
    private ImCallbackRecordRepository callbackRecordRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void stubConfiguredChannel() {
        when(notifySender.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("合法签名回调驱动待审批工单:翻状态、落决策、留痕")
    void acceptedCallbackResumesPendingApprovalTicket() throws Exception {
        String ticketId = pendingApprovalTicket();

        postCallback(ticketId, "evt-accept-1", "approve", "approver-1")
                .andExpect(status().isOk())
                .andExpect(header().string(ApprovalCallbackController.RESULT_HEADER,
                        ImCallbackRecordEntity.RESULT_ACCEPTED))
                .andExpect(jsonPath("$.errcode").value(0));

        // 状态闸门生效:待审批 → 处理中(图线程池是 mock,故停在 PROCESSING,不会被图改回)
        assertThat(ticketService.get(ticketId).orElseThrow().status())
                .isEqualTo(TicketService.STATUS_PROCESSING);
        // 决策落库:审批人来自回调,不是"未署名"
        assertThat(approvalService.decisionOf(ticketId).orElseThrow().approved()).isTrue();
        assertThat(approvalService.decisionOf(ticketId).orElseThrow().approver()).isEqualTo("approver-1");
        // 留痕:首次结论 + 首次详情
        ImCallbackRecordEntity record = recordOf("testim", "evt-accept-1");
        assertThat(record.getTicketId()).isEqualTo(ticketId);
        assertThat(record.getResult()).isEqualTo(ImCallbackRecordEntity.RESULT_ACCEPTED);
    }

    @Test
    @DisplayName("同一事件号重投只回放首次结论,不重复执行")
    void replayReturnsFirstOutcomeWithoutReExecuting() throws Exception {
        String ticketId = pendingApprovalTicket();
        postCallback(ticketId, "evt-replay", "approve", "approver-1").andExpect(status().isOk());

        // 平台重投:报文里审批人/动作都变了,但结论必须仍以首次为准
        postCallback(ticketId, "evt-replay", "reject", "approver-2")
                .andExpect(status().isOk())
                .andExpect(header().string(ApprovalCallbackController.RESULT_HEADER,
                        ApprovalCallbackController.RESULT_REPLAYED));

        assertThat(approvalService.decisionOf(ticketId).orElseThrow().approved()).isTrue();
        assertThat(approvalService.decisionOf(ticketId).orElseThrow().approver()).isEqualTo("approver-1");
        assertThat(callbackRecordRepository.findAll().stream()
                .filter(r -> "evt-replay".equals(r.getCallbackId())).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("签名不通过:拒绝且不改状态、不落决策、不留痕")
    void invalidSignatureIsRejectedWithoutSideEffects() throws Exception {
        String ticketId = pendingApprovalTicket();

        mockMvc.perform(post(ApprovalCallbackController.PATH, "testim")
                        .header("x-test-sign", "bad-sign")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("eventId=evt-bad&ticketId=" + ticketId + "&action=approve&approver=mallory"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        assertThat(ticketService.get(ticketId).orElseThrow().status())
                .isEqualTo(TicketService.STATUS_PENDING_APPROVAL);
        assertThat(approvalService.hasDecision(ticketId)).isFalse();
        assertThat(callbackRecordRepository.findAll().stream()
                .filter(r -> "evt-bad".equals(r.getCallbackId())).count()).isZero();
    }

    @Test
    @DisplayName("未知平台返回 404")
    void unknownPlatformReturnsNotFound() throws Exception {
        mockMvc.perform(post(ApprovalCallbackController.PATH, "unknown-platform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("unknown-platform")));
    }

    @Test
    @DisplayName("工单不在待审批状态:应答成功但标记忽略,不落决策")
    void ticketNotPendingApprovalIsIgnored() throws Exception {
        String ticketId = ticketService.create("已结单的工单");
        ticketService.updateStatus(ticketId, TicketService.STATUS_CLOSED);

        postCallback(ticketId, "evt-ignored", "approve", "approver-1")
                .andExpect(status().isOk())
                .andExpect(header().string(ApprovalCallbackController.RESULT_HEADER,
                        ImCallbackRecordEntity.RESULT_IGNORED));

        assertThat(approvalService.hasDecision(ticketId)).isFalse();
        assertThat(recordOf("testim", "evt-ignored").getResult())
                .isEqualTo(ImCallbackRecordEntity.RESULT_IGNORED);
    }

    @Test
    @DisplayName("同一工单第二个事件号:闸门拦住,不二次审批")
    void secondEventForSameTicketIsIgnored() throws Exception {
        String ticketId = pendingApprovalTicket();
        postCallback(ticketId, "evt-first", "approve", "approver-1").andExpect(status().isOk());

        postCallback(ticketId, "evt-second", "reject", "approver-2")
                .andExpect(status().isOk())
                .andExpect(header().string(ApprovalCallbackController.RESULT_HEADER,
                        ImCallbackRecordEntity.RESULT_IGNORED));

        // 决策仍是先到者的批准结论,工单没有被第二个事件反向
        assertThat(approvalService.decisionOf(ticketId).orElseThrow().approved()).isTrue();
        assertThat(approvalService.decisionOf(ticketId).orElseThrow().approver()).isEqualTo("approver-1");
    }

    @Test
    @DisplayName("真实钉钉适配器未配凭证时入口默认关闭(400)")
    void dingTalkCallbackIsClosedWhenUnconfigured() throws Exception {
        mockMvc.perform(post(ApprovalCallbackController.PATH, "dingtalk")
                        .param("timestamp", "1700000000000")
                        .param("nonce", "nonce-1")
                        .param("msg_signature", "whatever")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"encrypt\":\"whatever\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("未配置")));
    }

    @Test
    @DisplayName("审批挂起那轮提交成功后才发卡片")
    void cardIsSentAfterRoundCommits() {
        String ticketId = ticketService.create("我的ECS实例连不上了");

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                roundService.completeRound(ticketId, TicketService.STATUS_PENDING_APPROVAL, "已转人工审批,请稍候"));

        verify(notifySender).send(argThat(title -> title != null && title.contains(ticketId)),
                argThat(text -> text != null && text.contains("待人工审批")));
        assertThat(ticketService.get(ticketId).orElseThrow().status())
                .isEqualTo(TicketService.STATUS_PENDING_APPROVAL);
    }

    @Test
    @DisplayName("审批挂起那轮回滚时不发卡片")
    void cardIsNotSentWhenRoundRollsBack() {
        String ticketId = ticketService.create("回滚场景工单");
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> template.execute(status -> {
            roundService.completeRound(ticketId, TicketService.STATUS_PENDING_APPROVAL, "占位回复");
            throw new IllegalStateException("模拟本轮落库失败");
        })).isInstanceOf(IllegalStateException.class);

        verify(notifySender, never()).send(anyString(), anyString());
        assertThat(ticketService.get(ticketId).orElseThrow().status())
                .isNotEqualTo(TicketService.STATUS_PENDING_APPROVAL);
    }

    // ---------- 辅助 ----------

    private String pendingApprovalTicket() {
        String ticketId = ticketService.create("我的ECS实例连不上了,ssh 也登不上,帮我看看");
        ticketService.updateStatus(ticketId, TicketService.STATUS_PENDING_APPROVAL);
        return ticketId;
    }

    private ResultActions postCallback(String ticketId, String eventId, String action, String approver)
            throws Exception {
        String body = "eventId=" + eventId + "&ticketId=" + ticketId + "&action=" + action
                + "&approver=" + approver + "&reason=演示审批";
        return mockMvc.perform(post(ApprovalCallbackController.PATH, TestImAdapter.PLATFORM)
                .header("x-test-sign", "ok")
                .contentType(MediaType.TEXT_PLAIN)
                .content(body));
    }

    private ImCallbackRecordEntity recordOf(String platform, String callbackId) {
        return callbackRecordRepository.findById(new ImCallbackRecordEntity.Key(platform, callbackId))
                .orElseThrow(() -> new AssertionError("未找到回调留痕: " + platform + "/" + callbackId));
    }

    /**
     * 测试专用适配器:把"平台私有协议"简化成一段 KV 文本 + 一个签名头,
     * 用于验证回调编排语义(路由/验签拒绝/幂等回放/状态闸门/落决策),与钉钉协议解耦。
     */
    static class TestImAdapter implements ImCallbackAdapter {

        static final String PLATFORM = "testim";

        @Override
        public String platform() {
            return PLATFORM;
        }

        @Override
        public ParseResult parse(Map<String, String> headers, String rawBody) {
            if (!"ok".equals(headers.get("x-test-sign"))) {
                return ParseResult.reject("测试适配器:签名不通过");
            }
            Map<String, String> kv = splitKv(rawBody);
            String callbackId = kv.get("eventId");
            String ticketId = kv.get("ticketId");
            if (callbackId == null || ticketId == null) {
                return ParseResult.reject("测试适配器:缺少 eventId 或 ticketId");
            }
            return ParseResult.ok(new StandardApprovalCommand(PLATFORM, callbackId, ticketId,
                    "approve".equals(kv.get("action")), kv.get("approver"), kv.get("reason")));
        }

        private static Map<String, String> splitKv(String rawBody) {
            Map<String, String> kv = new LinkedHashMap<>();
            for (String pair : rawBody.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    kv.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
            return kv;
        }
    }

    @TestConfiguration
    static class TestAdapterConfig {

        @Bean
        ImCallbackAdapter testImAdapter() {
            return new TestImAdapter();
        }
    }
}
