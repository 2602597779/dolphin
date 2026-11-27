package com.dolphin.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 会话存储配置({@code dolphin.cluster.session-store.*},P0 第 4 处接口留口的实现切换)。
 *
 * <p>为什么这里可以有开关:两套实现都<b>真实存在</b>(进程内 Map / 共享表 session_context),
 * 配置只决定用哪一套——与发号器 {@code ticket-id.strategy} 同款;
 * 单一实现的地方仍然不做假开关(见 {@link TicketIdProperties} 说明)。
 */
@ConfigurationProperties(prefix = "dolphin.cluster.session-store")
public class SessionStoreProperties {

    /** 存储实现:local=进程内 Map(默认,单实例语义与改造前一致) | jdbc=共享表 session_context */
    private String kind = "local";

    /** jdbc 实现的空闲过期阈值(与进程内实现默认一致:30 分钟) */
    private Duration idleTtl = Duration.ofMinutes(30);

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Duration getIdleTtl() {
        return idleTtl;
    }

    public void setIdleTtl(Duration idleTtl) {
        this.idleTtl = idleTtl;
    }
}
