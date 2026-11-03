package com.dolphin.ticket.memory;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.dolphin.ticket.repo.GraphCheckpointEntity;
import com.dolphin.ticket.repo.GraphCheckpointRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 数据库 checkpoint saver(表 graph_checkpoint):threadId=工单号一行,
 * put 按主键 upsert 覆盖为最新快照,get 反序列化重建 Checkpoint。
 *
 * <p>checkpoint.state 即 {@code Map<String,Object>}(纯 JSON 安全类型),直接 Jackson 序列化;
 * get 时以新 UUID 重建快照 id(仅存储的 node_id/next_node/state 参与续跑语义,
 * graph-core resume 起点 = checkpoint.nextNodeId)。
 *
 * <p>进程重启后审批中断的工单可从 DB 续跑——HITL 持久化的关键(单行 upsert,只保留最新快照)。
 */
public class DbCheckpointSaver implements BaseCheckpointSaver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> STATE_TYPE = new TypeReference<>() {
    };

    private final GraphCheckpointRepository repository;

    public DbCheckpointSaver(GraphCheckpointRepository repository) {
        this.repository = repository;
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        return get(config).map(List::of).orElseGet(List::of);
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String threadId = threadId(config);
        if (threadId == null) {
            return Optional.empty();
        }
        return repository.findById(threadId).map(this::toCheckpoint);
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        String threadId = threadId(config);
        repository.save(new GraphCheckpointEntity(
                threadId,
                checkpoint.getNodeId() == null ? "" : checkpoint.getNodeId(),
                checkpoint.getNextNodeId() == null ? "" : checkpoint.getNextNodeId(),
                checkpoint.getId(),
                MAPPER.writeValueAsString(checkpoint.getState()),
                Instant.now()));
        return config;
    }

    @Override
    public boolean clear(RunnableConfig config) {
        String threadId = threadId(config);
        if (threadId == null || !repository.existsById(threadId)) {
            return false;
        }
        repository.deleteById(threadId);
        return true;
    }

    private static String threadId(RunnableConfig config) {
        return config == null ? null : config.threadId().orElse(null);
    }

    /** 反序列化重建 Checkpoint(builder:id 用新 UUID,state/nodeId/nextNodeId 来自库) */
    private Checkpoint toCheckpoint(GraphCheckpointEntity entity) {
        try {
            Map<String, Object> state = MAPPER.readValue(entity.getStateJson(), STATE_TYPE);
            return Checkpoint.builder()
                    .id(UUID.randomUUID().toString())
                    .state(state)
                    .nodeId(entity.getNodeId())
                    .nextNodeId(entity.getNextNode())
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("checkpoint 状态反序列化失败 ticketId=" + entity.getTicketId(), e);
        }
    }
}
