package com.dolphin.customer;

import com.dolphin.ticket.api.TicketController;
import com.dolphin.ticket.auth.ApiKeyAuthFilter;
import com.dolphin.ticket.service.TicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段1 租户隔离回归:客户/租户模型 + API-Key 鉴权 + 工单归属隔离。
 *
 * <p>验证点:
 * <ol>
 *   <li>鉴权:缺失/错误/已吊销 key 统一 401;有效 key 通过并把 customerId 注入请求上下文;</li>
 *   <li>归属:客户 A 的单对客户 B 不可见(get 404、list 不含),不泄露存在性;</li>
 *   <li>列表:本客户视角只看本客户的单,支持状态过滤;</li>
 *   <li>直调(内部渠道,Mock 请求无鉴权属性):客户上下文为 null 时退化为全量可见
 *       (HTTP 链路有过滤器兜底,内部渠道本就可信)。</li>
 * </ol>
 *
 * <p>环境:offline profile + H2 内存库,零外部依赖。注意 @SpringBootTest 不执行
 * CommandLineRunner,种子数据需显式调用 {@code seeder.run()} 幂等灌入。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dolphin-tenant;DB_CLOSE_DELAY=-1",
        "dolphin.rag.vector-store-path=target/dolphin-tenant-vector-store.json"
})
@ActiveProfiles("offline")
class TenantIsolationTest {

    @Autowired
    private CustomerSeeder customerSeeder;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private TicketController ticketController;
    @Autowired
    private ApiKeyAuthFilter filter;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedCustomers() {
        customerSeeder.run(); // 幂等:表空才灌
    }

    // ---- 鉴权过滤器 ----

    @Test
    void missingApiKey_rejectedWith401() throws Exception {
        MockHttpServletResponse response = filterRequest(null, "/api/tickets/submit");
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("X-API-Key");
    }

    @Test
    void wrongApiKey_rejectedWithSameMessage() throws Exception {
        // 错误 key 与缺失 key 文案一致:不给探测者"key 存在与否"的反馈
        assertThat(filterRequest("ak-not-exist", "/api/tickets/submit").getContentAsString())
                .isEqualTo(filterRequest(null, "/api/tickets/submit").getContentAsString());
    }

    @Test
    void validApiKey_passesAndInjectsCustomerId() throws Exception {
        MockHttpServletRequest request = authenticatedRequest(CustomerSeeder.DEMO_KEY_ALPHA,
                "/api/tickets/submit");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTR)).isEqualTo("cust-001");
    }

    @Test
    void nonTicketPath_passesWithoutAuth() throws Exception {
        // 运营侧接口(告警 webhook 等)不受客户鉴权保护
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/alert/webhook");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTR)).isNull();
    }

    @Test
    void disabledCustomer_rejectedEvenWithValidKey() throws Exception {
        // 吊销语义:enabled=false 的客户,key 仍存在但鉴权一律 401
        String revokedKey = "ak-revoked-test";
        CustomerEntity revoked = new CustomerEntity("cust-revoked", "已吊销客户", revokedKey, false,
                java.time.Instant.now());
        customerRepository.save(revoked);
        try {
            assertThat(customerService.authenticate(revokedKey)).isEmpty();
            MockHttpServletRequest request = authenticatedRequest(revokedKey, "/api/tickets");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(401);
        } finally {
            customerRepository.delete(revoked); // 用例自清理,不污染其他用例
        }
    }

    // ---- 工单归属隔离 ----

    @Test
    void ticketOfCustomerA_invisibleToCustomerB() {
        String idA = ticketService.create("客户A的工单:SLB 健康检查失败", "cust-001");
        String idB = ticketService.create("客户B的工单:实例无法连接", "cust-002");

        // 本人可见
        assertThat(ticketService.getForCustomer(idA, "cust-001")).isPresent();
        assertThat(ticketService.getForCustomer(idB, "cust-002")).isPresent();
        // 互相不可见(404 语义)
        assertThat(ticketService.getForCustomer(idA, "cust-002")).isEmpty();
        assertThat(ticketService.getForCustomer(idB, "cust-001")).isEmpty();
        // 不存在的客户看不到任何单
        assertThat(ticketService.getForCustomer(idA, "cust-999")).isEmpty();

        // 列表隔离:A 只看到自己的单
        var listA = ticketService.listForCustomer("cust-001", null, 100);
        var listB = ticketService.listForCustomer("cust-002", null, 100);
        assertThat(listA).allSatisfy(t -> assertThat(t.customerId()).isEqualTo("cust-001"));
        assertThat(listB).allSatisfy(t -> assertThat(t.customerId()).isEqualTo("cust-002"));
    }

    @Test
    void list_supportsStatusFilter() {
        ticketService.create("待审批的单", "cust-001");
        ticketService.create("结单的单", "cust-001");
        String closed = ticketService.create("会结单的单", "cust-001");
        ticketService.updateStatus(closed, TicketService.STATUS_CLOSED);

        var closedOnly = ticketService.listForCustomer("cust-001", TicketService.STATUS_CLOSED, 100);
        assertThat(closedOnly).isNotEmpty();
        assertThat(closedOnly).allSatisfy(t -> assertThat(t.status()).isEqualTo(TicketService.STATUS_CLOSED));
    }

    @Test
    void internalChannel_nullCustomerSeesAll() {
        // 内部渠道(告警自动开单/单测直调)customer=null:全量可见
        String idA = ticketService.create("内部渠道工单", "cust-001");
        String idInternal = ticketService.create("系统自动开单", null);
        assertThat(ticketService.getForCustomer(idInternal, null)).isPresent();
        assertThat(ticketService.getForCustomer(idA, null)).isPresent();
    }

    @Test
    void controller_getEnforcesOwnership() {
        String idA = ticketService.create("客户A专属", "cust-001");
        MockHttpServletRequest asCustomerB = new MockHttpServletRequest();
        asCustomerB.setAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTR, "cust-002");

        var response = ticketController.get(idA, asCustomerB);
        assertThat(response.getStatusCode().value()).isEqualTo(404);

        MockHttpServletRequest asCustomerA = new MockHttpServletRequest();
        asCustomerA.setAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTR, "cust-001");
        assertThat(ticketController.get(idA, asCustomerA).getStatusCode().value()).isEqualTo(200);
    }

    // ---- 辅助 ----

    private MockHttpServletResponse filterRequest(String apiKey, String uri)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (apiKey != null) {
            request.addHeader("X-API-Key", apiKey);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletRequest authenticatedRequest(String apiKey, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.addHeader("X-API-Key", apiKey);
        return request;
    }
}
