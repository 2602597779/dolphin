package com.dolphin.ticket.action;

import com.dolphin.ticket.repo.ApprovalTokenNonceEntity;
import com.dolphin.ticket.repo.ApprovalTokenNonceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 审批令牌服务:把"人工批准"从易伪造、可重放的 boolean 升级为一次性 HMAC 令牌。
 *
 * <p>动机:boolean approved 决策一旦被执行链路读到即可无限使用且无法防伪——
 * 令牌机制补齐三点安全属性:
 * <ol>
 *   <li><b>防伪</b>:载荷经 HMAC-SHA256 签名,验签用 {@link MessageDigest#isEqual} 常数时间比较;</li>
 *   <li><b>绑定</b>:载荷携带 ticketId + action + paramsHash(action/target/params 规范化序列化后的
 *       SHA-256 摘要),防止"审批重启 A 拿去重启 B";</li>
 *   <li><b>时效 + 一次性</b>:TTL 过期即失效;nonce 消费后作废,重放必被拒。</li>
 * </ol>
 *
 * <p>令牌格式:{@code Base64URL(payload).Base64URL(签名)},payload =
 * {@code ticketId|action|paramsHash|issuedAt|expiresAt|nonce}(毫秒时间戳;各字段不得含 '|',
 * 工单号与动作名均为受控字符集)。
 *
 * <p>TODO:签名密钥仍为内存态——配置缺省时随机重生成,进程重启后旧令牌全部失效;
 * 生产需接入密钥管理服务(KMS/Vault),密钥通过 {@code dolphin.approval.token-secret} 配置注入。
 * 已消费 nonce 表已持久化(见 {@link ApprovalTokenNonceRepository}),固定密钥下重放防护跨重启有效。
 */
@Service
public class ApprovalTokenService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTokenService.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SECRET_BYTES = 32;
    private static final int NONCE_BYTES = 16;
    /** 载荷字段分隔符(payload 各字段禁止包含该字符) */
    private static final String FIELD_DELIM = "|";

    /** 校验结果:valid=false 时 reason 为具体拒绝原因(供审计留痕与测试断言) */
    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok(String reason) { return new ValidationResult(true, reason); }
        static ValidationResult fail(String reason) { return new ValidationResult(false, reason); }
    }

    private final Duration ttl;
    private final SecretKeySpec signingKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ApprovalTokenNonceRepository nonceRepository;

    public ApprovalTokenService(
            @Value("${dolphin.approval.token-ttl:10m}") Duration ttl,
            @Value("${dolphin.approval.token-secret:}") String secret,
            ApprovalTokenNonceRepository nonceRepository) {
        this.ttl = ttl;
        this.nonceRepository = nonceRepository;
        if (secret == null || secret.isBlank()) {
            byte[] generated = new byte[SECRET_BYTES];
            secureRandom.nextBytes(generated);
            this.signingKey = new SecretKeySpec(generated, HMAC_ALGORITHM);
            log.warn("[approval-token] 未配置 dolphin.approval.token-secret,已随机生成临时签名密钥:"
                    + "进程重启后所有已签发令牌立即失效,生产环境必须配置固定密钥(TODO:接入密钥管理服务)");
        } else {
            this.signingKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        }
    }

    /**
     * 为某工单的某个动作建议签发一次性审批令牌(仅在人工审批通过后调用)。
     *
     * @return 令牌字符串 {@code Base64URL(payload).Base64URL(签名)}
     */
    public String issue(String ticketId, ActionProposal proposal) {
        long now = System.currentTimeMillis();
        long expiresAt = now + ttl.toMillis();
        String payload = String.join(FIELD_DELIM,
                ticketId,
                proposal.action(),
                paramsHash(proposal),
                String.valueOf(now),
                String.valueOf(expiresAt),
                randomNonce());
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String sig = base64Url(hmac(payloadBytes));
        return base64Url(payloadBytes) + "." + sig;
    }

    /**
     * 校验并消费令牌:验签 → 解析 → 绑定校验(ticketId/action/paramsHash 三项全等) → 过期 → nonce 一次性消费。
     * 任一步失败立即返回带具体拒绝原因的结果;全部通过则登记 nonce,同一令牌第二次调用必被拒。
     */
    public ValidationResult validateAndConsume(String ticketId, ActionProposal proposal, String token) {
        purgeExpiredNonces();
        if (token == null || token.isBlank()) {
            return ValidationResult.fail("未提供审批令牌");
        }
        String[] segments = token.split("\\.", -1);
        if (segments.length != 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
            return ValidationResult.fail("令牌格式非法(应为 Base64URL(payload).Base64URL(签名))");
        }
        byte[] payloadBytes;
        byte[] providedSig;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(segments[0]);
            providedSig = Base64.getUrlDecoder().decode(segments[1]);
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail("令牌格式非法(Base64URL 解码失败)");
        }

        // 验签:常数时间比较,防时序侧信道
        byte[] expectedSig = hmac(payloadBytes);
        if (!MessageDigest.isEqual(expectedSig, providedSig)) {
            return ValidationResult.fail("令牌签名校验失败(载荷被篡改或签名密钥不一致)");
        }

        String[] fields = new String(payloadBytes, StandardCharsets.UTF_8).split("\\" + FIELD_DELIM, -1);
        if (fields.length != 6) {
            return ValidationResult.fail("令牌载荷结构非法(期望 6 字段,实际 " + fields.length + ")");
        }
        String boundTicketId = fields[0];
        String boundAction = fields[1];
        String boundParamsHash = fields[2];
        long expiresAt;
        try {
            expiresAt = Long.parseLong(fields[4]);
        } catch (NumberFormatException e) {
            return ValidationResult.fail("令牌载荷结构非法(时间戳解析失败)");
        }
        String nonce = fields[5];

        // 绑定校验:令牌只能用于"这张工单 + 这个动作 + 这份参数"
        if (!boundTicketId.equals(ticketId)) {
            return ValidationResult.fail("令牌绑定工单不匹配(令牌=" + boundTicketId + ",当前=" + ticketId + ")");
        }
        if (!boundAction.equals(proposal.action())) {
            return ValidationResult.fail("令牌绑定动作不匹配(令牌=" + boundAction + ",建议=" + proposal.action() + ")");
        }
        if (!boundParamsHash.equals(paramsHash(proposal))) {
            return ValidationResult.fail("令牌绑定参数摘要不匹配(操作对象或参数与审批时不一致)");
        }

        long now = System.currentTimeMillis();
        if (now >= expiresAt) {
            return ValidationResult.fail("令牌已过期(过期时间=" + expiresAt + ",当前=" + now + ")");
        }

        // 一次性消费:nonce 落库(纯插入语义)。先查重快速拒绝;并发下双插以唯一主键约束兜底
        if (nonceRepository.existsById(nonce)) {
            return ValidationResult.fail("令牌已被消费(重放拒绝)");
        }
        try {
            // saveAndFlush 立即触发 INSERT,唯一约束冲突在本调用内暴露,保证并发安全
            nonceRepository.saveAndFlush(new ApprovalTokenNonceEntity(nonce, expiresAt));
        } catch (DataIntegrityViolationException e) {
            return ValidationResult.fail("令牌已被消费(重放拒绝)");
        }
        long remainSeconds = (expiresAt - now) / 1000;
        return ValidationResult.ok("令牌校验通过(动作=" + boundAction + ",剩余有效期=" + remainSeconds + "s)");
    }

    /**
     * 参数摘要:对 action+target+params 做规范化序列化(参数按 key 排序、k=v&k=v 拼接)后取 SHA-256。
     * 令牌只存摘要不存明文,校验时对同一规则重算比对。
     */
    static String paramsHash(ActionProposal proposal) {
        String canonical = proposal.action() + "\n" + proposal.target() + "\n"
                + canonicalParams(proposal.params());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 参数规范化序列化:按 key 字典序排序,避免 Map 遍历顺序影响摘要 */
    private static String canonicalParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        return params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(signingKey);
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    private String randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        return HexFormat.of().formatHex(nonce);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 清理已过期 nonce(令牌过期后不再需要占位防重放);校验链路机会式清理,TODO 定时任务 */
    private void purgeExpiredNonces() {
        nonceRepository.deleteByExpiresAtBefore(System.currentTimeMillis());
    }
}
