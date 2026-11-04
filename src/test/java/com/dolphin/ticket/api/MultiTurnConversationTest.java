package com.dolphin.ticket.api;

import com.dolphin.customer.CustomerSeeder;
import com.dolphin.ticket.auth.ApiKeyAuthFilter;
import com.dolphin.ticket.service.ConversationService;
import com.dolphin.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段4 工单多轮对话回归:reply 续跑 + 对话线程全链路。
 *
 * <p>验证点:
 * <ol>
 *   <li>多轮续跑:PENDING_APPROVAL 单追问后按最新上下文从头重跑(改口安全组 → 免审批直执结单);</li>
 *   <li>线程完整:客户/系统消息按序追加,每轮都入线程可回看;</li>
 *   <li>状态约束:CLOSED 拒绝追问(409)、ESCALATED 只记录不重跑、空消息 400;</li>
 *   <li>租户隔离:跨客户 reply/messages 均 404。</li>
 * </ol>
 *
 * <p>环境:offline profile + H2 内存库,直调 Controller(绕过 HTTP 层,鉴权属性手工注入)。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dolphin-multiturn;DB_CLOSE_DELAY=-1",
        "dolphin.rag.vector-store-path=target/dolphin-multiturn-vector-store.json"
})
@ActiveProfiles("offline")
class MultiTurnConversationTest {

    @Autowired
    private TicketController ticketController;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private CustomerSeeder customerSeeder;

    @BeforeEach
    void seedCustomers() {
        customerSeeder.run(); // 幂等:表空才灌
    }

    @Test
    void pendingTicket_replyRerunsFromScratchAndThreadKeepsAllRounds() {
        // 第 1 轮:连不上 → 诊断出 restartInstance(需审批)→ 中断在审批前
        String id = submit("cust-001", "我的ECS实例连不上了,ssh 都登不上");
        assertThat(ticketService.get(id).orElseThrow().status())
                .isEqualTo(TicketService.STATUS_PENDING_APPROVAL);
        assertThat(conversationService.messages(id)).hasSize(2); // 客户诉求 + 首次答复草稿

        // 第 2 轮:客户改口"其实是安全组没放行 443" → 按本轮诉求从头重跑
        // → authorizeSecurityGroup 免审批直执 → 结单(而非沿用上轮的重启审批断点)
        Map<String, Object> body = body(ticketController.reply(id,
                new TicketController.ReplyRequest("其实是安全组没放行443端口,帮我开一下"),
                asCustomer("cust-001")));
        assertThat(body.get("status")).isEqualTo(TicketService.STATUS_CLOSED);
        assertThat(ticketService.get(id).orElseThrow().status()).isEqualTo(TicketService.STATUS_CLOSED);
        assertThat((Integer) body.get("rounds")).isEqualTo(4); // 2 客户 + 2 系统

        // 线程按序完整:客户诉求/系统答复交替,第 2 轮答复对应本轮安全组诉求
        var messages = conversationService.messages(id);
        assertThat(messages).extracting(ConversationService.Message::role)
                .containsExactly("USER", "AGENT", "USER", "AGENT");
        assertThat(messages.get(2).content()).contains("安全组");
        assertThat(messages.get(3).content()).isNotBlank();
    }

    @Test
    void closedTicket_replyRejectedWith409() {
        String id = submit("cust-001", "SLB 健康检查不通过是什么原因"); // 咨询类,无动作直执结单
        assertThat(ticketService.get(id).orElseThrow().status()).isEqualTo(TicketService.STATUS_CLOSED);

        ResponseEntity<Map<String, Object>> response = ticketController.reply(id,
                new TicketController.ReplyRequest("还有个问题"), asCustomer("cust-001"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("error", "工单已结单,如需继续处理请重新提交: " + id);
        // 线程不被污染:追问未入线程
        assertThat(conversationService.messages(id)).hasSize(2);
    }

    @Test
    void escalatedTicket_replyRecordedOnlyWithoutAiRerun() {
        String id = submit("cust-001", "我的ECS实例连不上了,ssh 都登不上");
        ticketService.updateStatus(id, TicketService.STATUS_ESCALATED); // 模拟已转人工

        Map<String, Object> body = body(ticketController.reply(id,
                new TicketController.ReplyRequest("补充:重启之后还是连不上"), asCustomer("cust-001")));
        assertThat(body.get("status")).isEqualTo(TicketService.STATUS_ESCALATED);
        assertThat((String) body.get("note")).contains("转人工");

        // 追问只入线程供专员查看:不触发 AI 重跑(无系统侧新消息)、状态不翻转
        var messages = conversationService.messages(id);
        assertThat(messages).extracting(ConversationService.Message::role)
                .containsExactly("USER", "AGENT", "USER");
        assertThat(messages.get(2).content()).contains("重启之后还是连不上");
        assertThat(ticketService.get(id).orElseThrow().status()).isEqualTo(TicketService.STATUS_ESCALATED);
    }

    @Test
    void reply_blankMessageRejectedWith400() {
        String id = submit("cust-001", "我的ECS实例连不上了,ssh 都登不上");
        assertThat(ticketController.reply(id, new TicketController.ReplyRequest("  "),
                asCustomer("cust-001")).getStatusCode().value()).isEqualTo(400);
        assertThat(ticketController.reply(id, new TicketController.ReplyRequest(null),
                asCustomer("cust-001")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void replyAndMessages_enforceTenantIsolation() {
        String id = submit("cust-001", "我的ECS实例连不上了,ssh 都登不上");

        // 跨客户追问/看线程:404,不泄露工单存在性
        assertThat(ticketController.reply(id, new TicketController.ReplyRequest("我也问问"),
                asCustomer("cust-002")).getStatusCode().value()).isEqualTo(404);
        assertThat(ticketController.messages(id, asCustomer("cust-002"))
                .getStatusCode().value()).isEqualTo(404);

        // 本人可看线程:角色/内容/时间戳齐全
        ResponseEntity<?> response = ticketController.messages(id, asCustomer("cust-001"));
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("ticketStatus")).isEqualTo(TicketService.STATUS_PENDING_APPROVAL);
        assertThat((Integer) body.get("count")).isEqualTo(2);
        var msgs = (java.util.List<?>) body.get("messages");
        assertThat(msgs).allSatisfy(m -> {
            Map<?, ?> msg = (Map<?, ?>) m;
            assertThat(msg.containsKey("role")).isTrue();
            assertThat(msg.containsKey("content")).isTrue();
            assertThat(msg.containsKey("createdAt")).isTrue();
        });
        assertThat(msgs.get(0)).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) msgs.get(0)).get("role")).isEqualTo("USER");
    }

    // ---- 辅助 ----

    private String submit(String customerId, String message) {
        Map<String, Object> body = body(ticketController.submit(
                new TicketController.SubmitRequest(message), asCustomer(customerId)));
        return (String) body.get("ticketId");
    }

    private MockHttpServletRequest asCustomer(String customerId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTR, customerId);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        return response.getBody();
    }
}
