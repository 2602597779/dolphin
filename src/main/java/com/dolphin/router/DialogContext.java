package com.dolphin.router;

/**
 * 会话上下文:承载路由粘性状态。
 *
 * <p>activeCapability 非空表示该会话有进行中的能力流程(如工单跑到一半等待审批),
 * 此时新消息默认短路回原流程,不做重新分类(见 StickySessionMatcher)。
 */
public class DialogContext {

    private final String sessionId;

    /** 当前占用会话的能力编码,null 表示无进行中流程 */
    private volatile String activeCapability;

    /** 等待人工审批的工单号(工单流程专用),null 表示无待审批 */
    private volatile String pendingTicketId;

    /** 最近访问时间戳,驱动 SessionStore 的过期清理 */
    private volatile long lastAccessedAt = System.currentTimeMillis();

    public DialogContext(String sessionId) {
        this.sessionId = sessionId;
    }

    /** 标记会话被使用(每次请求入口调用) */
    public void touch() {
        this.lastAccessedAt = System.currentTimeMillis();
    }

    public long getLastAccessedAt() {
        return lastAccessedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getActiveCapability() {
        return activeCapability;
    }

    public void setActiveCapability(String activeCapability) {
        this.activeCapability = activeCapability;
    }

    public String getPendingTicketId() {
        return pendingTicketId;
    }

    public void setPendingTicketId(String pendingTicketId) {
        this.pendingTicketId = pendingTicketId;
    }
}
