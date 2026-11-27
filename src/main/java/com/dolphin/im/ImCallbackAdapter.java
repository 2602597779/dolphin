package com.dolphin.im;

import java.util.Map;

/**
 * IM 回调适配器:把平台私有回调协议翻译成统一审批指令。
 *
 * <p>验签/解密全部封装在实现内部(各平台算法不同),调用方只拿到
 * "通过并解析成功"或"不通过并带原因",因此审批链路不依赖任何平台 SDK。
 *
 * <p>安全前提:{@code rawBody} 必须是<b>原始请求体字符串</b>。
 * 平台签名是对原始字节算的,先反序列化再验签会因字段顺序/空白差异导致验签失败,
 * 也会给攻击者留下"改字段但签名仍匹配"的空间。
 */
public interface ImCallbackAdapter {

    /** 平台标识,对应回调路径变量 {@code /api/im/callback/{platform}} */
    String platform();

    /**
     * 校验来源并解析为统一审批指令。
     *
     * @param headers 请求头(键统一转小写;验签需要 timestamp/nonce/sign 等)
     * @param rawBody 原始请求体字符串(未反序列化)
     * @return 成功携带指令;失败携带拒绝原因(写审计、用于响应码判定)
     */
    ParseResult parse(Map<String, String> headers, String rawBody);

    /**
     * 平台要求的成功应答体。
     *
     * <p>默认返回平台无关的 JSON;钉钉要求<b>加密应答</b>(回明文会被判失败并重投),
     * 由 {@link DingTalkCallbackAdapter} 覆写。
     *
     * @param headers 与 {@link #parse} 同一份请求头(加密应答需要 timestamp/nonce)
     * @param rawBody 原始请求体(加密应答需要其中的 encrypt 字段)
     */
    default String ack(Map<String, String> headers, String rawBody) {
        return "{\"errcode\":0,\"errmsg\":\"ok\"}";
    }

    /**
     * 解析结果。
     *
     * @param command      解析出的指令,失败时为 null
     * @param rejectReason 拒绝原因,成功时为 null
     */
    record ParseResult(StandardApprovalCommand command, String rejectReason) {

        public static ParseResult ok(StandardApprovalCommand command) {
            return new ParseResult(command, null);
        }

        public static ParseResult reject(String reason) {
            return new ParseResult(null, reason);
        }

        public boolean accepted() {
            return command != null;
        }
    }
}
