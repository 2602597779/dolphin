package com.dolphin.ticket.service;

import com.dolphin.ticket.repo.TicketMessageEntity;
import com.dolphin.ticket.repo.TicketMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段4 对话线程服务回归:消息追加/按序读取/历史转录。
 *
 * <p>验证点:线程旧→新排序、系统侧空回复不落消息(图异常中断只保留客户侧发言)、
 * 转录格式("客户: "/"客服: ")、超长线程尾部截断(防撑爆提示词)。
 */
@DataJpaTest
@Import(ConversationService.class)
class ConversationServiceTest {

    @Autowired
    private ConversationService conversationService;

    @Test
    void threadKeepsInsertionOrderAndRoles() {
        conversationService.appendUser("t-100", "实例连不上了");
        conversationService.appendAgent("t-100", "已提交人工审批,通过后执行重启");
        conversationService.appendUser("t-100", "其实是安全组没放行 443");

        List<ConversationService.Message> messages = conversationService.messages("t-100");
        assertThat(messages).hasSize(3);
        assertThat(messages).extracting(ConversationService.Message::role)
                .containsExactly("USER", "AGENT", "USER");
        assertThat(messages.get(0).content()).isEqualTo("实例连不上了");
        assertThat(messages.get(2).content()).isEqualTo("其实是安全组没放行 443");
        // 每条消息都带时间戳,供客户侧线程回看
        assertThat(messages).allSatisfy(m -> assertThat(m.createdAt()).isNotNull());
    }

    @Test
    void blankAgentMessageIsSkipped() {
        // 图异常中断无终稿:不落空消息,线程只保留客户侧发言
        conversationService.appendUser("t-101", "帮我看看");
        conversationService.appendAgent("t-101", null);
        conversationService.appendAgent("t-101", "   ");
        assertThat(conversationService.messages("t-101")).hasSize(1);
        assertThat(conversationService.messages("t-101").get(0).role()).isEqualTo("USER");
    }

    @Test
    void emptyThreadReturnsEmptyAndBlankTranscript() {
        assertThat(conversationService.messages("t-404")).isEmpty();
        assertThat(conversationService.transcript("t-404")).isEmpty();
    }

    @Test
    void transcriptRendersRolePrefixedLines() {
        conversationService.appendUser("t-102", "SLB 健康检查不通过?");
        conversationService.appendAgent("t-102", "常见原因是后端端口未监听。");
        assertThat(conversationService.transcript("t-102"))
                .isEqualTo("客户: SLB 健康检查不通过?\n客服: 常见原因是后端端口未监听。");
    }

    @Test
    void transcriptTruncatesToTail() {
        // 超长线程只保留尾部 6000 字符:历史是上下文参考,尾部(最新沟通)相关性最高
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            big.append("第").append(i).append("轮沟通记录,内容占位。");
        }
        conversationService.appendUser("t-103", big.toString());
        String transcript = conversationService.transcript("t-103");
        assertThat(transcript.length()).isLessThanOrEqualTo(6000);
        assertThat(transcript.endsWith("客户: " + big)).isFalse(); // 已被截断
        assertThat(transcript).endsWith(big.substring(big.length() - 6000 + "客户: ".length()));
    }

    @Test
    void threadsAreIsolatedPerTicket() {
        conversationService.appendUser("t-a", "A 的诉求");
        conversationService.appendUser("t-b", "B 的诉求");
        assertThat(conversationService.messages("t-a"))
                .extracting(ConversationService.Message::content)
                .containsExactly("A 的诉求");
        assertThat(conversationService.messages("t-b"))
                .extracting(ConversationService.Message::content)
                .containsExactly("B 的诉求");
    }
}
