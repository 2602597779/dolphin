package com.dolphin.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉钉回调适配器单测:验签/时间窗/报文映射的边界。
 *
 * <p>所有请求都由本仓库的加解密工具按协议"真造"出来(不是打桩),
 * 因此这些用例验证的是"协议到标准指令"的转换正确性,而不是实现自证。
 */
class DingTalkCallbackAdapterTest {

    private static final String TOKEN = "dolphin-test-token";
    private static final String AES_KEY = "0123456789012345678901234567890123456789012";
    private static final String TICKET_ID = "T2S2IVPLK-7-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一次回调请求:验签三要素 + 请求体 */
    private record Call(Map<String, String> headers, String body) {
    }

    @Test
    @DisplayName("合法卡片回调 → 标准审批指令(批准)")
    void parsesValidApproveCallback() {
        String event = cardEvent("evt-1", TICKET_ID, "APPROVE", "同意执行");

        ImCallbackAdapter.ParseResult result = parse(
                signedCall(event, System.currentTimeMillis(), TOKEN, AES_KEY));

        assertTrue(result.accepted(), "合法回调必须解析成功,实际: " + result.rejectReason());
        StandardApprovalCommand command = result.command();
        assertEquals(DingTalkCallbackAdapter.PLATFORM, command.platform());
        assertEquals("evt-1", command.callbackId());
        assertEquals(TICKET_ID, command.ticketId(), "工单号应由 outTrackId 前缀反解");
        assertTrue(command.approved());
        assertEquals("zhang-san", command.approver());
        assertEquals("同意执行", command.reason());
    }

    @Test
    @DisplayName("content 内嵌 JSON 的拒绝动作 → approved=false")
    void parsesRejectAction() {
        ImCallbackAdapter.ParseResult result = parse(
                signedCall(cardEvent("evt-2", TICKET_ID, "REJECT", "风险太高"), System.currentTimeMillis(), TOKEN, AES_KEY));

        assertTrue(result.accepted(), result.rejectReason());
        assertFalse(result.command().approved());
        assertEquals("风险太高", result.command().reason());
    }

    @Test
    @DisplayName("content 是纯动作名时同样识别")
    void parsesPlainActionContent() {
        ObjectNode event = baseEvent("evt-3", TICKET_ID);
        event.put("content", "驳回");

        ImCallbackAdapter.ParseResult result = parse(
                signedCall(event.toString(), System.currentTimeMillis(), TOKEN, AES_KEY));

        assertTrue(result.accepted(), result.rejectReason());
        assertFalse(result.command().approved());
    }

    @Test
    @DisplayName("actionId 直接在报文根节点也兼容")
    void parsesRootLevelActionId() {
        ObjectNode event = baseEvent("evt-4", TICKET_ID);
        event.put("actionId", "agree");

        ImCallbackAdapter.ParseResult result = parse(
                signedCall(event.toString(), System.currentTimeMillis(), TOKEN, AES_KEY));

        assertTrue(result.accepted(), result.rejectReason());
        assertTrue(result.command().approved());
    }

    @Test
    @DisplayName("密文被篡改 → 验签失败拒绝")
    void rejectsTamperedCiphertext() {
        DingTalkCrypto crypto = new DingTalkCrypto(TOKEN, AES_KEY);
        long timestamp = System.currentTimeMillis();
        String encrypt = crypto.encrypt(cardEvent("evt-5", TICKET_ID, "APPROVE", "ok"), new byte[16]);
        String otherEncrypt = crypto.encrypt(cardEvent("evt-5", TICKET_ID, "APPROVE", "ok"));
        String signature = crypto.sign(String.valueOf(timestamp), "nonce-1", encrypt);

        ImCallbackAdapter.ParseResult result = parse(
                rawCall(otherEncrypt, timestamp, "nonce-1", signature));

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().contains("验签失败"), result.rejectReason());
    }

    @Test
    @DisplayName("换 token 签名 → 拒绝(凭证即入口开关)")
    void rejectsWrongTokenSignature() {
        ImCallbackAdapter.ParseResult result = parse(
                signedCall(cardEvent("evt-6", TICKET_ID, "APPROVE", "ok"),
                        System.currentTimeMillis(), "other-token", AES_KEY));

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().contains("验签失败"), result.rejectReason());
    }

    @Test
    @DisplayName("时间戳超窗 → 拒绝(防重放)")
    void rejectsStaleTimestamp() {
        long twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000L;

        ImCallbackAdapter.ParseResult result = parse(
                signedCall(cardEvent("evt-7", TICKET_ID, "APPROVE", "ok"), twoHoursAgo, TOKEN, AES_KEY));

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().contains("时间戳"), result.rejectReason());
    }

    @Test
    @DisplayName("未配置凭证 → 入口默认关闭(拒绝而非放行)")
    void rejectsWhenNotConfigured() {
        DingTalkCallbackAdapter unconfigured =
                new DingTalkCallbackAdapter(new ImCallbackProperties(), MAPPER);
        Call call = signedCall(cardEvent("evt-8", TICKET_ID, "APPROVE", "ok"),
                System.currentTimeMillis(), TOKEN, AES_KEY);

        ImCallbackAdapter.ParseResult result = unconfigured.parse(call.headers(), call.body());

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().contains("未配置"), result.rejectReason());
    }

    @Test
    @DisplayName("缺 nonce 验签头 → 拒绝")
    void rejectsMissingSignatureHeader() {
        Call original = signedCall(cardEvent("evt-9", TICKET_ID, "APPROVE", "ok"),
                System.currentTimeMillis(), TOKEN, AES_KEY);
        Map<String, String> headers = new HashMap<>(original.headers());
        headers.remove("nonce");

        ImCallbackAdapter.ParseResult result = parse(new Call(headers, original.body()));

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().contains("验签参数"), result.rejectReason());
    }

    @Test
    @DisplayName("未知卡片动作 → 拒绝(不做默认放行)")
    void rejectsUnknownAction() {
        ImCallbackAdapter.ParseResult result = parse(
                signedCall(cardEvent("evt-10", TICKET_ID, "maybe", ""), System.currentTimeMillis(), TOKEN, AES_KEY));

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().contains("未知的卡片动作"), result.rejectReason());
    }

    @Test
    @DisplayName("缺事件唯一号 → 拒绝(没有幂等键)")
    void rejectsMissingEventId() {
        ObjectNode event = baseEvent(null, TICKET_ID);
        event.put("actionId", "approve");

        ImCallbackAdapter.ParseResult result = parse(
                signedCall(event.toString(), System.currentTimeMillis(), TOKEN, AES_KEY));

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().contains("EventId"), result.rejectReason());
    }

    @Test
    @DisplayName("定位不到工单 → 拒绝")
    void rejectsUnresolvableTicket() {
        ObjectNode event = baseEvent("evt-11", null);
        event.put("outTrackId", "some-other-card");
        event.put("actionId", "approve");

        ImCallbackAdapter.ParseResult result = parse(
                signedCall(event.toString(), System.currentTimeMillis(), TOKEN, AES_KEY));

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().contains("未定位到工单"), result.rejectReason());
    }

    @Test
    @DisplayName("明文不是 JSON → 拒绝")
    void rejectsNonJsonPlaintext() {
        ImCallbackAdapter.ParseResult result = parse(
                signedCall("hello", System.currentTimeMillis(), TOKEN, AES_KEY));

        assertFalse(result.accepted());
        assertTrue(result.rejectReason().contains("不是合法 JSON"), result.rejectReason());
    }

    @Test
    @DisplayName("ack 是加密应答:可验签且能解出成功体(否则平台会重投)")
    void ackIsEncryptedAndVerifiable() {
        Call call = signedCall(cardEvent("evt-12", TICKET_ID, "APPROVE", "ok"),
                System.currentTimeMillis(), TOKEN, AES_KEY);

        String ack = adapter().ack(call.headers(), call.body());

        DingTalkCrypto crypto = new DingTalkCrypto(TOKEN, AES_KEY);
        String encrypt = ack.replaceAll(".*\"encrypt\":\"([^\"]+)\".*", "$1");
        String signature = ack.replaceAll(".*\"msg_signature\":\"([^\"]+)\".*", "$1");
        assertTrue(crypto.verify(signature, call.headers().get("timestamp"), call.headers().get("nonce"), encrypt));
        assertTrue(crypto.decrypt(encrypt).contains("errcode"), "应答明文应是成功体");
    }

    // ---------- 构造辅助 ----------

    private static DingTalkCallbackAdapter adapter() {
        ImCallbackProperties properties = new ImCallbackProperties();
        properties.getDingtalk().setToken(TOKEN);
        properties.getDingtalk().setAesKey(AES_KEY);
        return new DingTalkCallbackAdapter(properties, MAPPER);
    }

    /** 便捷入口:适配器的入参就是"验签参数视图 + 原始请求体" */
    private static ImCallbackAdapter.ParseResult parse(Call call) {
        return adapter().parse(call.headers(), call.body());
    }

    /** 按协议真造一次回调:签名覆盖密文,密文内是事件 JSON */
    private static Call signedCall(String plainEvent, long timestamp, String token, String aesKey) {
        DingTalkCrypto crypto = new DingTalkCrypto(token, aesKey);
        String nonce = "nonce-1";
        String encrypt = crypto.encrypt(plainEvent, new byte[16]);
        return rawCall(encrypt, timestamp, nonce, crypto.sign(String.valueOf(timestamp), nonce, encrypt));
    }

    private static Call rawCall(String encrypt, long timestamp, String nonce, String signature) {
        Map<String, String> headers = new HashMap<>();
        headers.put("timestamp", String.valueOf(timestamp));
        headers.put("nonce", nonce);
        headers.put("msg_signature", signature);
        return new Call(headers, "{\"encrypt\":\"" + encrypt + "\"}");
    }

    /** 钉钉互动卡片回调报文:业务字段在 content(内嵌 JSON 字符串)里 */
    private static String cardEvent(String eventId, String ticketId, String actionId, String remark) {
        ObjectNode content = MAPPER.createObjectNode();
        content.put("actionId", actionId);
        content.put("remark", remark);
        ObjectNode event = baseEvent(eventId, ticketId);
        event.put("content", content.toString());
        return event.toString();
    }

    private static ObjectNode baseEvent(String eventId, String ticketId) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("EventType", "CARD_ACTION_CALLBACK");
        if (eventId != null) {
            event.put("EventId", eventId);
        }
        if (ticketId != null) {
            event.put("outTrackId", DingTalkCallbackAdapter.CARD_ID_PREFIX + ticketId);
        }
        event.put("userId", "zhang-san");
        return event;
    }
}
