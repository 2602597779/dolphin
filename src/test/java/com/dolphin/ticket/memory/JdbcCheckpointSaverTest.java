package com.dolphin.ticket.memory;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.dolphin.ticket.repo.GraphCheckpointRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DbCheckpointSaver} 持久化语义(真实 H2 内存库):
 * put→get 往返、threadId 单行 upsert(最新快照覆盖)、重启语义(全新 saver 实例读同一库可恢复)、clear。
 */
@DataJpaTest
class JdbcCheckpointSaverTest {

    @Autowired
    private GraphCheckpointRepository repository;

    private final RunnableConfig config = RunnableConfig.builder().threadId("t-1").build();

    private static Checkpoint checkpoint(String nodeId, String nextNodeId, String phase) {
        return Checkpoint.builder()
                .id(UUID.randomUUID().toString())
                .nodeId(nodeId)
                .nextNodeId(nextNodeId)
                .state(Map.of("TICKET_ID", "t-1", "phase", phase))
                .build();
    }

    @Test
    void putAndGetRoundTrip() throws Exception {
        var saver = new DbCheckpointSaver(repository);
        saver.put(config, checkpoint("agent", "approval", "submitted"));

        var loaded = saver.get(config);
        assertThat(loaded).isPresent();
        // resume 起点 = nextNodeId;nodeId 为中断时所在节点
        assertThat(loaded.get().getNodeId()).isEqualTo("agent");
        assertThat(loaded.get().getNextNodeId()).isEqualTo("approval");
        assertThat(loaded.get().getState()).containsEntry("phase", "submitted");
    }

    @Test
    void putTwiceUpsertsSingleRowKeepingLatest() throws Exception {
        var saver = new DbCheckpointSaver(repository);
        saver.put(config, checkpoint("agent", "approval", "first"));
        saver.put(config, checkpoint("approval", "executor", "second"));

        // threadId=工单号一行:重复 put 覆盖为最新快照,不堆积历史
        assertThat(repository.count()).isEqualTo(1);
        var loaded = saver.get(config).orElseThrow();
        assertThat(loaded.getNodeId()).isEqualTo("approval");
        assertThat(loaded.getState()).containsEntry("phase", "second");
    }

    @Test
    void newSaverInstanceReadsSameDb_restartSemantics() throws Exception {
        var before = new DbCheckpointSaver(repository);
        before.put(config, checkpoint("agent", "approval", "interrupted"));

        // 模拟进程重启:全新 saver 实例(只共享 DB)仍能读出中断快照续跑
        var after = new DbCheckpointSaver(repository);
        var loaded = after.get(config).orElseThrow();
        assertThat(loaded.getNextNodeId()).isEqualTo("approval");
        assertThat(loaded.getState()).containsEntry("phase", "interrupted");
    }

    @Test
    void clearRemovesCheckpoint() throws Exception {
        var saver = new DbCheckpointSaver(repository);
        saver.put(config, checkpoint("agent", "approval", "x"));

        assertThat(saver.clear(config)).isTrue();
        assertThat(saver.get(config)).isEmpty();
        assertThat(repository.count()).isZero();
        // 重复 clear 无行可删,返回 false
        assertThat(saver.clear(config)).isFalse();
    }
}
