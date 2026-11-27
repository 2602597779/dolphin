package com.dolphin.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 钉钉互动卡片回调适配器(参考实现)。
 *
 * <p>职责边界:只做"平台私有协议 → 标准审批指令"的翻译与来源校验,
 * 不碰工单状态、不碰审批决策落库 —— 那些是 {@code ApprovalCallbackController} 的事。
 *
 * <p>协议:钉钉把回调三要素 {@code timestamp / nonce / msg_signature}
 * 放在查询参数上,请求体是 {@code {"encrypt":"..."}};响应必须是加密应答,否则平台判失败并重投。
 * 因此本仓库的 Controller 传给适配器的是"请求头 + 查询参数"的合并视图,键一律小写。
 *
 * <p>工单定位约定:出卡片时把工单号编进 {@code outTrackId},形如
 * {@code dolphin-approval:T2S2IVPLK-7-1};回调按前缀反解出工单号。
 * 这样卡片回调无需额外查表即可定位工单(也可直接用 {@code ticketId} 字段覆盖)。
 *
 * <p>由 {@code ImCallbackConfig} 装配为 {@link ImCallbackAdapter} Bean:未配置凭证时自己返回拒绝,
 * 而不是靠条件装配把 Bean 藏起来 —— 这样"未配置"与"配错"是同一条可观测的失败路径。
 *
 * <p>⚠️ 本实现按官方回调协议实现并有本地向量验证,<b>未用真机沙箱对通</b>;
 * 字段名以官方文档为准,保全字段用 {@code firstText} 做了大小写兼容。
 */
public class DingTalkCallbackAdapter implements ImCallbackAdapter {

    /** 平台标识,对应回调路径变量 {platform} */
    public static final String PLATFORM = "dingtalk";

    /** 卡片 id 前缀:出卡时把工单号编码进来,回调据此定位工单 */
    public static final String CARD_ID_PREFIX = "dolphin-approval:";

    /** 应答明文(钉钉只校验能否验签解密,内容固定即可) */
    private static final String ACK_PLAINTEXT = "{\"errcode\":0,\"errmsg\":\"ok\"}";

    /** 时间戳容忍窗口:超窗一律拒绝,防重放 */
    private static final long TIMESTAMP_TOLERANCE_MS = 60L * 60 * 1000;

    private static final Set<String> APPROVE_ACTIONS = Set.of("approve", "agree", "ok", "同意", "批准", "通过");
    private static final Set<String> REJECT_ACTIONS = Set.of("reject", "refuse", "deny", "拒绝", "驳回", "不通过");

    private final ImCallbackProperties.DingTalk config;
    private final ObjectMapper objectMapper;

    public DingTalkCallbackAdapter(ImCallbackProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getDingtalk();
        this.objectMapper = objectMapper;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public ParseResult parse(Map<String, String> headers, String rawBody) {
        if (!config.configured()) {
            return ParseResult.reject("钉钉回调未配置(token/aes-key 为空,入口默认关闭)");
        }
        String timestamp = header(headers, "timestamp");
        String nonce = header(headers, "nonce");
        String msgSignature = header(headers, "msg_signature");
        if (timestamp.isEmpty() || nonce.isEmpty() || msgSignature.isEmpty()) {
            return ParseResult.reject("缺少验签参数 timestamp/nonce/msg_signature");
        }
        String encrypt = readEncrypt(rawBody);
        if (encrypt == null) {
            return ParseResult.reject("请求体缺少 encrypt 字段");
        }
        DingTalkCrypto crypto = new DingTalkCrypto(config.getToken(), config.getAesKey(), config.getReceiveId());
        if (!crypto.verify(msgSignature, timestamp, nonce, encrypt)) {
            return ParseResult.reject("回调验签失败(凭证不匹配或报文被篡改)");
        }
        if (!withinTolerance(timestamp)) {
            return ParseResult.reject("回调时间戳超出容忍窗口,疑似重放");
        }
        String plain;
        try {
            plain = crypto.decrypt(encrypt);
        } catch (IllegalArgumentException e) {
            return ParseResult.reject("回调密文解密失败: " + e.getMessage());
        }
        return mapToCommand(plain);
    }

    @Override
    public String ack(Map<String, String> headers, String rawBody) {
        String timestamp = header(headers, "timestamp");
        String nonce = header(headers, "nonce");
        if (!config.configured() || timestamp.isEmpty() || nonce.isEmpty()) {
            // 未配置或无法构造加密应答时退回明文:平台可能重投,但幂等层会兜住重复
            return "{\"errcode\":0,\"errmsg\":\"ok\"}";
        }
        DingTalkCrypto crypto = new DingTalkCrypto(config.getToken(), config.getAesKey(), config.getReceiveId());
        return crypto.encryptReply(ACK_PLAINTEXT, timestamp, nonce);
    }

    /** 明文事件 JSON → 标准审批指令 */
    private ParseResult mapToCommand(String plain) {
        JsonNode root;
        try {
            root = objectMapper.readTree(plain);
        } catch (Exception e) {
            return ParseResult.reject("回调明文不是合法 JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return ParseResult.reject("回调明文不是 JSON 对象");
        }
        JsonNode action = actionNode(root);
        String callbackId = firstText(root, "EventId", "eventId", "eventID");
        String ticketId = firstText(root, "ticketId", "TicketId");
        if (ticketId == null) {
            String cardId = firstText(root, "outTrackId", "OutTrackId");
            if (cardId != null && cardId.startsWith(CARD_ID_PREFIX)) {
                ticketId = cardId.substring(CARD_ID_PREFIX.length());
            }
        }
        String approver = firstText(root, "userId", "UserId", "senderId", "SenderId");
        String actionId = null;
        String remark = null;
        if (action != null) {
            actionId = action.isObject() ? firstText(action, "actionId", "actionID", "action") : action.asText();
            remark = action.isObject() ? firstText(action, "remark", "reason", "comment") : null;
        }

        if (callbackId == null || callbackId.isBlank()) {
            return ParseResult.reject("回调缺少事件唯一号(EventId),无法幂等去重");
        }
        if (ticketId == null || ticketId.isBlank()) {
            return ParseResult.reject("回调未定位到工单(缺 ticketId,且 outTrackId 无 " + CARD_ID_PREFIX + " 前缀)");
        }
        if (actionId == null || actionId.isBlank()) {
            return ParseResult.reject("回调缺少卡片动作 actionId");
        }
        Boolean approved = resolveAction(actionId);
        if (approved == null) {
            return ParseResult.reject("未知的卡片动作: " + actionId);
        }
        return ParseResult.ok(new StandardApprovalCommand(PLATFORM, callbackId, ticketId, approved,
                approver == null || approver.isBlank() ? "unknown" : approver, remark));
    }

    /** 卡片动作载体:优先取 content(可能是内嵌 JSON 字符串或纯动作名),否则用根节点 */
    private JsonNode actionNode(JsonNode root) {
        JsonNode content = firstNode(root, "content", "Content");
        if (content == null) {
            return root;
        }
        if (content.isObject()) {
            return content;
        }
        String text = content.asText();
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(text);
        }
    }

    private static Boolean resolveAction(String actionId) {
        String key = actionId.trim().toLowerCase(Locale.ROOT);
        if (APPROVE_ACTIONS.contains(key)) {
            return Boolean.TRUE;
        }
        if (REJECT_ACTIONS.contains(key)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private String readEncrypt(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            return node == null ? null : firstText(node, "encrypt", "Encrypt");
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean withinTolerance(String timestamp) {
        long parsed;
        try {
            parsed = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        return Math.abs(System.currentTimeMillis() - parsed) <= TIMESTAMP_TOLERANCE_MS;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return "";
        }
        String value = headers.get(name);
        if (value == null) {
            value = headers.get(name.toUpperCase(Locale.ROOT));
        }
        return value == null ? "" : value.trim();
    }

    private static JsonNode firstNode(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode child = node.get(name);
            if (child != null && !child.isNull()) {
                return child;
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... names) {
        JsonNode child = firstNode(node, names);
        return child == null ? null : child.asText();
    }
}
