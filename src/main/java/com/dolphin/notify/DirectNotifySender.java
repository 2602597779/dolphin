package com.dolphin.notify;

import com.dolphin.alert.DingTalkNotifier;

/**
 * 直发通道:同步调用钉钉发送(改造前既有语义)。
 *
 * <p>默认 {@code dolphin.notify.channel=direct}:offline 演示与既有测试不受影响;
 * 也是 mq 模式的"最终下游"(消费者拿到消息后仍由它真正发送)。
 */
public class DirectNotifySender implements NotifySender {

    private final DingTalkNotifier notifier;

    public DirectNotifySender(DingTalkNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public boolean isConfigured() {
        return notifier.isConfigured();
    }

    @Override
    public boolean send(String title, String text) {
        return notifier.sendMarkdown(title, text);
    }
}
