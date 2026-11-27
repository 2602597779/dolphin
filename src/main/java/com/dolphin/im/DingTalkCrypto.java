package com.dolphin.im;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 钉钉开放平台回调加解密/验签协议(纯函数工具类,零第三方 SDK)。
 *
 * <p>协议要点(与钉钉/企微回调一致):
 * <ol>
 *   <li><b>验签</b>:{@code msg_signature = SHA1(sort(token, timestamp, nonce, encrypt) 拼接)} 的十六进制小写;</li>
 *   <li><b>解密</b>:aesKey = Base64Decode(encodingAesKey + "="),取前 16 字节作 IV,AES-256-CBC/PKCS7;
 *       明文信封 = 16 字节随机串 + 4 字节网络序长度 + 明文 + receiveId;</li>
 *   <li><b>应答</b>:钉钉要求<b>加密应答</b>(含 msg_signature/time/nonce/encrypt),返回明文会被判失败并重投。</li>
 * </ol>
 *
 * <p>为什么自实现:引入钉钉 SDK 只为两个纯算法函数,却要带进一整条 SDK 依赖链与版本约束,
 * 对一个"回调入口"而言得不偿失。此处算法固定、可被独立实现的向量交叉验证(见 DingTalkCryptoTest)。
 *
 * <p>⚠️ 本实现按官方协议文档实现并通过本地向量验证,<b>未与钉钉沙箱真机对通</b>。
 */
public final class DingTalkCrypto {

    private static final int BLOCK_SIZE = 16;
    private static final int KEY_LENGTH = 32;
    private static final String AES_CBC_PKCS7 = "AES/CBC/PKCS5Padding";

    private final String token;
    private final String receiveId;
    private final byte[] aesKey;
    private final byte[] iv;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 不校验 receiveId 的构造器(钉钉群机器人回调无该字段) */
    public DingTalkCrypto(String token, String encodingAesKey) {
        this(token, encodingAesKey, null);
    }

    /**
     * @param token          回调验签 token(平台后台配置)
     * @param encodingAesKey 43 字符 EncodingAESKey(平台后台配置)
     * @param receiveId      明文尾部回带的身份(钉钉为 appKey/corpId),空则跳过该校验
     */
    public DingTalkCrypto(String token, String encodingAesKey, String receiveId) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("回调 token 不能为空");
        }
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException("EncodingAESKey 必须是 43 个字符");
        }
        byte[] key = Base64.getDecoder().decode(encodingAesKey + "=");
        if (key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("EncodingAESKey 解码后必须是 32 字节,实际 " + key.length);
        }
        this.token = token;
        this.receiveId = receiveId == null ? "" : receiveId;
        this.aesKey = key;
        this.iv = Arrays.copyOf(key, BLOCK_SIZE);
    }

    /** 验签:msg_signature = SHA1(字典序拼接 token/timestamp/nonce/encrypt) */
    public String sign(String timestamp, String nonce, String encrypt) {
        String[] parts = {token, String.valueOf(timestamp), String.valueOf(nonce), String.valueOf(encrypt)};
        Arrays.sort(parts);
        return sha1Hex(String.join("", parts));
    }

    /** 常量时间比较验签结果,避免签名比较侧信道 */
    public boolean verify(String msgSignature, String timestamp, String nonce, String encrypt) {
        if (msgSignature == null || msgSignature.isBlank()) {
            return false;
        }
        byte[] expected = sign(timestamp, nonce, encrypt).getBytes(StandardCharsets.UTF_8);
        byte[] actual = msgSignature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /** 解密回调密文,返回信封中的明文(事件 JSON) */
    public String decrypt(String encryptBase64) {
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance(AES_CBC_PKCS7);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            plain = cipher.doFinal(Base64.getDecoder().decode(encryptBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("回调密文解密失败: " + e.getMessage(), e);
        }
        if (plain.length < BLOCK_SIZE + 4) {
            throw new IllegalArgumentException("回调明文长度非法: " + plain.length);
        }
        int msgLength = ByteBuffer.wrap(plain, BLOCK_SIZE, 4).getInt();
        if (msgLength < 0 || BLOCK_SIZE + 4 + msgLength > plain.length) {
            throw new IllegalArgumentException("回调明文长度字段非法: " + msgLength);
        }
        String message = new String(plain, BLOCK_SIZE + 4, msgLength, StandardCharsets.UTF_8);
        String tail = new String(plain, BLOCK_SIZE + 4 + msgLength,
                plain.length - BLOCK_SIZE - 4 - msgLength, StandardCharsets.UTF_8);
        if (!receiveId.isEmpty() && !receiveId.equals(tail)) {
            throw new IllegalArgumentException("回调 receiveId 校验失败,存在伪造风险");
        }
        return message;
    }

    /** 加密(16 字节随机前缀),用于自检与演示构造合法回调 */
    public String encrypt(String message) {
        byte[] prefix = new byte[BLOCK_SIZE];
        secureRandom.nextBytes(prefix);
        return encrypt(message, prefix);
    }

    /** 加密(指定前缀):固定前缀使加解密结果可复现,供单测交叉验证 */
    String encrypt(String message, byte[] prefix16) {
        if (prefix16 == null || prefix16.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("随机前缀必须是 16 字节");
        }
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        byte[] receiveIdBytes = receiveId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(BLOCK_SIZE + 4 + messageBytes.length + receiveIdBytes.length);
        buffer.put(prefix16);
        buffer.putInt(messageBytes.length);
        buffer.put(messageBytes);
        buffer.put(receiveIdBytes);
        try {
            Cipher cipher = Cipher.getInstance(AES_CBC_PKCS7);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            return Base64.getEncoder().encodeToString(cipher.doFinal(buffer.array()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("回调应答加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造钉钉要求的加密应答体。
     *
     * <p>明文应答会被平台视为失败并触发重投,因此成功处理后必须回这个结构。
     */
    public String encryptReply(String message, String timestamp, String nonce) {
        String encrypt = encrypt(message);
        return "{\"msg_signature\":\"" + sign(timestamp, nonce, encrypt)
                + "\",\"time\":\"" + timestamp
                + "\",\"nonce\":\"" + nonce
                + "\",\"encrypt\":\"" + encrypt + "\"}";
    }

    private static String sha1Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-1 不可用: " + e.getMessage(), e);
        }
    }
}
