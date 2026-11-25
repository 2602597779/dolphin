package com.dolphin.notify;

import java.time.Instant;

/**
 * 通知消息体(队列传递单元)。
 *
 * <p>进程内实现直接传对象;生产换真实 MQ 时本 record 即 JSON 序列化的消息体
 * (字段刻意保持简单,不携带任何不可序列化的运行时状态)。
 */
public record NotifyMessage(String title, String text, Instant createdAt) {
}
