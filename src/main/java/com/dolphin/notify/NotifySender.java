package com.dolphin.notify;

/**
 * 通知通道抽象(接口留口):调用方只依赖本接口,实现由 {@code dolphin.notify.channel} 切换。
 *
 * <ul>
 *   <li>{@code direct} → {@link DirectNotifySender}:同步直发(改造前的既有语义,offline 默认);</li>
 *   <li>{@code mq} → {@link MqNotifySender}:进程内 MQ 语义(有界缓冲 + 限速消费 + 失败重试 + 死信)。</li>
 * </ul>
 *
 * <p>生产替换真实 MQ(RocketMQ 等)时只新增本接口实现,调用方与配置语义零改动。
 */
public interface NotifySender {

    /** 下游是否已配置(钉钉 webhook 未配置时通知降级为日志,不算失败) */
    boolean isConfigured();

    /**
     * 提交一条通知。
     *
     * @return true=已提交(直发成功 / 已入队);false=发送失败或被拒(旁路可丢,调用方不中断主流程)
     */
    boolean send(String title, String text);
}
