package com.dolphin.im;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉钉回调加解密协议单测。
 *
 * <p>关键设计:签名与密文各有一个<b>黄金向量</b>,由独立工具(.NET
 * {@code System.Security.Cryptography} 的 SHA1/Aes 实现)生成后固化在测试里,
 * 用来证明本实现与"协议文档的另一种实现"字节级一致 —— 而不是用自己加密自己解密来自证正确。
 */
class DingTalkCryptoTest {

    private static final String TOKEN = "dolphin-test-token";
    private static final String AES_KEY = "0123456789012345678901234567890123456789012";

    /** 独立实现(.NET SHA1)对字典序拼接串算出的十六进制签名 */
    private static final String GOLDEN_SIGNATURE = "c50743f1cbd84e4b9738794e57942c2992aeb55b";
    /** 独立实现(.NET AES-256-CBC)对信封 16×0x01 + 长度5 + "hello" + 空 receiveId 的密文 */
    private static final String GOLDEN_CIPHERTEXT = "hq6dQi+2SjnVsgXYnrz0GAgykxp+j8BoY6wV4ZRjlNk=";

    @Test
    @DisplayName("签名算法与独立实现(.NET SHA1)逐字节一致")
    void signatureMatchesIndependentImplementation() {
        DingTalkCrypto crypto = new DingTalkCrypto(TOKEN, AES_KEY);

        String signature = crypto.sign("1730000000", "nonce1", "abc123");

        assertEquals(GOLDEN_SIGNATURE, signature, "签名算法(字典序拼接+SHA1)必须与协议一致");
        assertTrue(crypto.verify(GOLDEN_SIGNATURE, "1730000000", "nonce1", "abc123"));
    }

    @Test
    @DisplayName("能解出独立实现生成的密文(证明密钥派生与信封布局正确)")
    void decryptsCiphertextFromIndependentImplementation() {
        DingTalkCrypto crypto = new DingTalkCrypto(TOKEN, AES_KEY);

        assertEquals("hello", crypto.decrypt(GOLDEN_CIPHERTEXT));
    }

    @Test
    @DisplayName("加解密往返一致,且密文随随机前缀变化")
    void encryptDecryptRoundTrip() {
        DingTalkCrypto crypto = new DingTalkCrypto(TOKEN, AES_KEY);
        String payload = "{\"eventType\":\"approval\",\"ticketId\":\"T2S2IVPLK-7-1\"}";
        byte[] prefix = new byte[16];

        String first = crypto.encrypt(payload, prefix);
        String second = crypto.encrypt(payload, prefix);
        assertEquals(first, second, "同前缀必须可复现(测试可交叉验证的前提)");
        assertEquals(payload, crypto.decrypt(first));
        assertNotEquals(first, crypto.encrypt(payload), "随机前缀应使密文不可预测");
    }

    @Test
    @DisplayName("加密应答可自校验:platform 回读时应能验签并解出明文")
    void encryptedReplyIsSelfVerifiable() {
        DingTalkCrypto crypto = new DingTalkCrypto(TOKEN, AES_KEY);

        String reply = crypto.encryptReply("{\"errcode\":0,\"errmsg\":\"ok\"}", "1730000000", "nonce1");
        String encrypt = reply.replaceAll(".*\"encrypt\":\"([^\"]+)\".*", "$1");
        String signature = reply.replaceAll(".*\"msg_signature\":\"([^\"]+)\".*", "$1");

        assertTrue(crypto.verify(signature, "1730000000", "nonce1", encrypt));
        assertEquals("{\"errcode\":0,\"errmsg\":\"ok\"}", crypto.decrypt(encrypt));
    }

    @Test
    @DisplayName("密文被篡改后验签失败")
    void tamperedPayloadFailsVerification() {
        DingTalkCrypto crypto = new DingTalkCrypto(TOKEN, AES_KEY);
        String signature = crypto.sign("1730000000", "nonce1", "abc123");

        assertFalse(crypto.verify(signature, "1730000000", "nonce1", "abc124"), "密文变一位即应失败");
        assertFalse(crypto.verify(signature, "1730000001", "nonce1", "abc123"), "时间戳变一位即应失败");
        assertFalse(crypto.verify(GOLDEN_SIGNATURE.toUpperCase(), "1730000000", "nonce1", "abc123000"),
                "内容变化时签名必须不匹配");
    }

    @Test
    @DisplayName("换 token 后原签名验不过(凭证决定入口开关)")
    void wrongTokenFailsVerification() {
        DingTalkCrypto victim = new DingTalkCrypto(TOKEN, AES_KEY);
        DingTalkCrypto attacker = new DingTalkCrypto("other-token", AES_KEY);

        String signature = victim.sign("1730000000", "nonce1", "abc123");

        assertTrue(victim.verify(signature, "1730000000", "nonce1", "abc123"));
        assertFalse(attacker.verify(signature, "1730000000", "nonce1", "abc123"));
    }

    @Test
    @DisplayName("receiveId 不匹配时拒绝解密(防跨应用伪造回放)")
    void receiveIdMismatchRejected() {
        DingTalkCrypto corpA = new DingTalkCrypto(TOKEN, AES_KEY, "corp-A");
        DingTalkCrypto corpB = new DingTalkCrypto(TOKEN, AES_KEY, "corp-B");

        String encryptedForA = corpA.encrypt("{\"eventType\":\"approval\"}", new byte[16]);

        assertEquals("{\"eventType\":\"approval\"}", corpA.decrypt(encryptedForA));
        assertThrows(IllegalArgumentException.class, () -> corpB.decrypt(encryptedForA));
    }

    @Test
    @DisplayName("密文非法/长度不足时解密失败而非返回脏数据")
    void malformedCiphertextRejected() {
        DingTalkCrypto crypto = new DingTalkCrypto(TOKEN, AES_KEY);

        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("not-a-base64!!!"));
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("QUJD"), "3 字节不是合法分组");
    }

    @Test
    @DisplayName("凭证非法时构造即失败:配置错不该等到回调时才暴露")
    void invalidCredentialsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DingTalkCrypto("", AES_KEY));
        assertThrows(IllegalArgumentException.class, () -> new DingTalkCrypto(TOKEN, "short-key"));
        assertThrows(IllegalArgumentException.class, () -> new DingTalkCrypto(TOKEN, null));
    }

    @Test
    @DisplayName("字段顺序不影响签名:字典序拼接对拍")
    void signatureIsOrderInsensitiveToInputOrder() {
        DingTalkCrypto crypto = new DingTalkCrypto(TOKEN, AES_KEY);

        assertEquals(crypto.sign("1730000000", "nonce1", "abc123"),
                crypto.sign("1730000000", "nonce1", "abc123"));
        assertEquals(GOLDEN_SIGNATURE, crypto.sign("1730000000", "nonce1", "abc123"));
    }
}
