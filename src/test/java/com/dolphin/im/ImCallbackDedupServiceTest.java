package com.dolphin.im;

import com.dolphin.im.repo.ImCallbackRecordEntity;
import com.dolphin.im.repo.ImCallbackRecordRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 回调幂等去重:同一平台同一事件只登记一次,重投返回首次结果,且唯一性由库表主键保证。
 */
@DataJpaTest
class ImCallbackDedupServiceTest {

    private static final String PLATFORM = "dingtalk";
    private static final String CALLBACK_ID = "evt-9f1c8d2a";

    @Autowired
    private ImCallbackRecordRepository repository;

    @Autowired
    private EntityManager entityManager;

    private ImCallbackDedupService service;

    @BeforeEach
    void setUp() {
        service = new ImCallbackDedupService(repository);
    }

    @Test
    @DisplayName("未处理过的回调查询为空")
    void 未处理过的回调查询为空() {
        assertThat(service.find(PLATFORM, "never-seen")).isEmpty();
    }

    @Test
    @DisplayName("登记后可查到首次处理结果")
    void 登记后可查到首次处理结果() {
        service.remember(PLATFORM, CALLBACK_ID, "T2S2IVPLK-7-1", ImCallbackRecordEntity.RESULT_ACCEPTED, "审批通过,已续跑图执行");

        Optional<ImCallbackRecordEntity> found = service.find(PLATFORM, CALLBACK_ID);
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getTicketId()).isEqualTo("T2S2IVPLK-7-1");
        assertThat(found.orElseThrow().getResult()).isEqualTo(ImCallbackRecordEntity.RESULT_ACCEPTED);
        assertThat(found.orElseThrow().getDetail()).isEqualTo("审批通过,已续跑图执行");
        assertThat(found.orElseThrow().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("重复登记不覆盖首次留痕")
    void 重复登记不覆盖首次留痕() {
        service.remember(PLATFORM, CALLBACK_ID, "T2S2IVPLK-7-1", ImCallbackRecordEntity.RESULT_ACCEPTED, "首次结果");
        // 平台重投:第二次登记必须无效,否则"返回首次结果"会退化成"返回最后一次结果"
        service.remember(PLATFORM, CALLBACK_ID, "T2S2IVPLK-7-1", ImCallbackRecordEntity.RESULT_IGNORED, "重投结果");

        ImCallbackRecordEntity row = service.find(PLATFORM, CALLBACK_ID).orElseThrow();
        assertThat(row.getResult()).isEqualTo(ImCallbackRecordEntity.RESULT_ACCEPTED);
        assertThat(row.getDetail()).isEqualTo("首次结果");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同平台同一事件号互不影响")
    void 不同平台同一事件号互不影响() {
        service.remember(PLATFORM, CALLBACK_ID, "T-A", ImCallbackRecordEntity.RESULT_ACCEPTED, null);
        service.remember("welink", CALLBACK_ID, "T-B", ImCallbackRecordEntity.RESULT_ACCEPTED, null);

        assertThat(service.find(PLATFORM, CALLBACK_ID).orElseThrow().getTicketId()).isEqualTo("T-A");
        assertThat(service.find("welink", CALLBACK_ID).orElseThrow().getTicketId()).isEqualTo("T-B");
    }

    @Test
    @DisplayName("拒绝与忽略同样留痕(重投可原样回放)")
    void 拒绝与忽略同样留痕() {
        service.remember(PLATFORM, "evt-rej", null, ImCallbackRecordEntity.RESULT_REJECTED, "回调验签失败");
        service.remember(PLATFORM, "evt-ign", "T-C", ImCallbackRecordEntity.RESULT_IGNORED, "工单状态已不是待审批");

        assertThat(service.find(PLATFORM, "evt-rej").orElseThrow().getResult()).isEqualTo(ImCallbackRecordEntity.RESULT_REJECTED);
        assertThat(service.find(PLATFORM, "evt-ign").orElseThrow().getResult()).isEqualTo(ImCallbackRecordEntity.RESULT_IGNORED);
    }

    /**
     * "去重靠库表主键"这句话必须能验证:同键插两次,第二次必须被数据库拒绝。
     * 走原生 SQL 绕过应用层的存在性预检,直接检验唯一性是否真的落在表上。
     */
    @Test
    @DisplayName("主键唯一约束真的在库表上生效")
    void 主键唯一约束真的在库表上生效() {
        insertRaw("evt-dupe");
        Throwable thrown = catchThrowable(() -> insertRaw("evt-dupe"));

        assertThat(thrown).isNotNull();
        assertThat(messageChain(thrown)).matches("(?is).*(unique|primary key|constraint).*");
    }

    private void insertRaw(String callbackId) {
        entityManager.createNativeQuery(
                        "insert into im_callback_record (platform, callback_id, ticket_id, result, detail, created_at) "
                                + "values (:platform, :callbackId, null, 'ACCEPTED', null, current_timestamp)")
                .setParameter("platform", PLATFORM)
                .setParameter("callbackId", callbackId)
                .executeUpdate();
    }

    private static String messageChain(Throwable thrown) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
