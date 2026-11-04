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
 * 转录格式("客户: "/"客服: ")、超长线程按整行从尾部截断(阶段4优化 O5)、
 * requestId 幂等判重(阶段4优化 O7)。
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
    void transcriptOverBudgetKeepsWholeLinesAndLatestMessage() {
        // O5:超长线程按整行从尾部保留,首行必须是完整消息行(不能以半句/半行开头)
        for (int i = 0; i < 100; i++) {
            String content = "第" + i + "轮沟通记录:" + "内容占位".repeat(20);
            if (i % 2 == 0) {
                conversationService.appendUser("t-103", content);
            } else {
                conversationService.appendAgent("t-103", content);
            }
        }
        String transcript = conversationService.transcript("t-103");
        String[] lines = transcript.split("\n");

        assertThat(transcript.length()).isLessThanOrEqualTo(ConversationService.TRANSCRIPT_MAX_CHARS);
        assertThat(lines.length).isGreaterThan(1);
        assertThat(lines).allSatisfy(line -> assertThat(line).matches("^(客户|客服): .+"));
        // 尾部是最新一轮沟通(第 99 轮),旧消息整行丢弃
        assertThat(lines[lines.length - 1]).startsWith("客服: 第99轮沟通记录:");
        assertThat(transcript).doesNotContain("第0轮沟通记录");
    }

    @Test
    void transcriptSingleOverlongLineIsTruncatedWithEllipsis() {
        // 单行就超预算时只能截断该行:省略标记提示更早内容被裁掉,尾部仍是最新内容
        String big = "第0轮沟通记录,内容占位。".repeat(500);
        conversationService.appendUser("t-104", big);

        String transcript = conversationService.transcript("t-104");
        assertThat(transcript.length()).isLessThanOrEqualTo(ConversationService.TRANSCRIPT_MAX_CHARS);
        assertThat(transcript).startsWith("…");
        assertThat(transcript).endsWith(big.substring(big.length() - (ConversationService.TRANSCRIPT_MAX_CHARS - 1)));
    }

    @Test
    void tailKeepsWholeLinesWithinBudget() {
        String text = "最旧一行\n中间一行\n最新一行";
        // 预算 12:从尾部累加"最新一行"(4)+换行1+"中间一行"(4)=9,"最旧一行"再+5=14 超预算 → 整行丢弃
        assertThat(ConversationService.tail(text, 12)).isEqualTo("中间一行\n最新一行");
        assertThat(ConversationService.tail(text, 0)).isEmpty();
        assertThat(ConversationService.tail("", 10)).isEmpty();
        assertThat(ConversationService.tail("短文本", 10)).isEqualTo("短文本");
    }

    @Test
    void requestIdsAreTrackedPerTicket() {
        conversationService.appendUser("t-a", "第一轮", "req-1");
        assertThat(conversationService.hasRequest("t-a", "req-1")).isTrue();
        assertThat(conversationService.hasRequest("t-a", "req-2")).isFalse();
        // 请求号判重按工单隔离:同一 requestId 在别单不算重复
        assertThat(conversationService.hasRequest("t-b", "req-1")).isFalse();
        assertThat(conversationService.hasRequest("t-a", null)).isFalse();
        // 空请求号统一落 null,不会撞唯一约束
        conversationService.appendUser("t-a", "第二轮", " ");
        conversationService.appendUser("t-a", "第三轮", "");
        assertThat(conversationService.messages("t-a")).hasSize(3);
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
