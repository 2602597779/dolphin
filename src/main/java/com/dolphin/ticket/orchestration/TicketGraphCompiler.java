package com.dolphin.ticket.orchestration;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.dolphin.ticket.memory.TicketCheckpointStore;
import org.springframework.stereotype.Component;

/**
 * 工单图编译器:每请求新建 CompiledGraph(P0 并发根治)。
 *
 * <p>graph-core 1.0.0.2 中节点 action 读取的是 CompiledGraph 共享的模板 state,
 * 单例 CompiledGraph 在并发请求下会互相覆盖模板数据造成跨工单串扰。
 * 此处每请求 {@code compile()} 产出独立实例,各请求持有独立模板 state,彻底根治。
 *
 * <p>checkpoint 经 {@link TicketCheckpointStore}(DB saver,threadId=工单号)持久化:
 * 中断-恢复语义不受每请求编译影响——新编译的图模板为空,approve 链路须先从
 * DB checkpoint 水合本工单状态({@code overAllState().input(getState(...))})再续跑。
 */
@Component
public class TicketGraphCompiler {

    private final StateGraph stateGraph;
    private final TicketCheckpointStore checkpointStore;

    public TicketGraphCompiler(StateGraph ticketStateGraph, TicketCheckpointStore checkpointStore) {
        this.stateGraph = ticketStateGraph;
        this.checkpointStore = checkpointStore;
    }

    /** 每请求编译新图:approval 前中断等待人工 + DB checkpoint(会话记忆) */
    public CompiledGraph compile() {
        CompileConfig config = CompileConfig.builder()
                .saverConfig(checkpointStore.saverConfig())
                .interruptBefore(TicketGraphConfig.NODE_APPROVAL)
                .build();
        try {
            return stateGraph.compile(config);
        } catch (GraphStateException e) {
            throw new IllegalStateException("工单图编译失败", e);
        }
    }
}
