package com.dolphin.ticket.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.JacksonStateSerializer;

/**
 * 工单图状态序列化器。
 *
 * <p>graph-core 1.0.0.2 的 StateGraph 默认构造器使用 Gson 序列化状态,
 * 但 Gson 不在本项目 classpath 上;这里改用 Jackson(Spring Boot 自带),
 * 通过 {@code new StateGraph(factory, new TicketStateSerializer())} 传入。
 */
public class TicketStateSerializer extends JacksonStateSerializer {

    public TicketStateSerializer() {
        super(OverAllState::new);
    }
}
