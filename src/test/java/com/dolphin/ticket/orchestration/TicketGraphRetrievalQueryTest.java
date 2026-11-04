package com.dolphin.ticket.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dolphin.ticket.orchestration.state.TicketState;
import com.dolphin.ticket.service.ConversationService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段4优化 O6:检索 query 带对话历史回归。
 *
 * <p>追问常是"那怎么办"这类省略句,只用本轮原文检索会丢失工单主体;
 * 验证 query = 历史尾部(约 300 字)+ 本轮原文,首轮(无历史)保持纯原文。
 */
class TicketGraphRetrievalQueryTest {

    @Test
    void queryUsesHistoryTailPlusCurrentMessage() {
        StringBuilder history = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            history.append("客户: 实例 i-abc123 端口 8080 报错 502(第").append(i).append("轮)\n");
        }
        String raw = "那怎么办";

        OverAllState state = new OverAllState(Map.of(TicketState.CONVERSATION_HISTORY, history.toString()));
        String query = TicketGraphConfig.retrievalQuery(state, raw);

        assertThat(query).startsWith(ConversationService.tail(history.toString(), 300));
        assertThat(query).endsWith("\n" + raw);
        assertThat(query.length()).isLessThanOrEqualTo(300 + 1 + raw.length());
        // 只带最近的上下文,不会把整段历史塞进检索
        assertThat(query).doesNotContain("第0轮");
    }

    @Test
    void queryWithoutHistoryFallsBackToRawMessage() {
        String raw = "SLB 健康检查不过";
        OverAllState state = new OverAllState(Map.of());

        assertThat(TicketGraphConfig.retrievalQuery(state, raw)).isEqualTo(raw);
        // 空白历史同样按首轮处理
        OverAllState blankState = new OverAllState(Map.of(TicketState.CONVERSATION_HISTORY, "   "));
        assertThat(TicketGraphConfig.retrievalQuery(blankState, raw)).isEqualTo(raw);
    }
}
