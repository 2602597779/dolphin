package com.dolphin.ticket.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 工单图 checkpoint 实体(表 graph_checkpoint):按 threadId(工单号)一行,
 * 审批中断后重启进程仍可从 DB 续跑(HITL 持久化的关键)。
 *
 * <p>node_id 为中断时所在节点(Checkpoint.builder 必填),next_node 为 resume 的续跑起点
 * (graph-core CompiledGraph resume 语义:起点 = checkpoint.nextNodeId)。
 */
@Entity
@Table(name = "graph_checkpoint")
public class GraphCheckpointEntity {

    @Id
    @Column(name = "ticket_id", nullable = false, length = 64)
    private String ticketId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "next_node", nullable = false, length = 64)
    private String nextNode;

    @Lob
    @Column(name = "config_json")
    private String configJson;

    /** checkpoint 状态(JSON,值为 Map<String,Object>,Jackson 序列化) */
    @Lob
    @Column(name = "state_json", nullable = false)
    private String stateJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GraphCheckpointEntity() {
    }

    public GraphCheckpointEntity(String ticketId, String nodeId, String nextNode,
                                 String configJson, String stateJson, Instant updatedAt) {
        this.ticketId = ticketId;
        this.nodeId = nodeId;
        this.nextNode = nextNode;
        this.configJson = configJson;
        this.stateJson = stateJson;
        this.updatedAt = updatedAt;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNextNode() {
        return nextNode;
    }

    public String getConfigJson() {
        return configJson;
    }

    public String getStateJson() {
        return stateJson;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public void setNextNode(String nextNode) {
        this.nextNode = nextNode;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
