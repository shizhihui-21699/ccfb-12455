package com.consentledger.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.consentledger.domain.ConsentAction;
import com.consentledger.domain.ConsentEvent;
import com.consentledger.domain.PolicyPurpose;
import com.consentledger.domain.PolicyVersion;

/**
 * 所有哈希/签名的唯一实现处。事件链用公开可复算的 SHA-256，
 * 凭证签名用 HMAC-SHA256（密钥不出服务端）。
 */
@Component
public class Hashing {

    public static final String CHAIN_ALGORITHM = "SHA-256";
    public static final String SIGNATURE_ALGORITHM = "HmacSHA256";
    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    public String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(CHAIN_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String hmacSha256Hex(String secret, String value) {
        try {
            Mac mac = Mac.getInstance(SIGNATURE_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), SIGNATURE_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * 事件规范化字符串。字段用 | 连接、值内 | 与 \ 转义，避免拼接歧义。
     */
    public String canonicalEvent(ConsentEvent e) {
        return joinCanonical(
                "v1",
                e.getEventUid(),
                String.valueOf(e.getSubjectId()),
                e.getSubjectExternalId(),
                e.getPurposeCode(),
                e.getAction().name(),
                e.getLegalBasis().name(),
                String.valueOf(e.getPolicyVersionId()),
                e.getPolicyContentHash(),
                String.valueOf(e.getOccurredAt().toEpochMilli()),
                e.getPrevHash());
    }

    public String canonicalEvent(String eventUid, long subjectId, String subjectExternalId, String purposeCode,
                                 ConsentAction action, String legalBasis, long policyVersionId,
                                 String policyContentHash, long occurredAtEpochMilli, String prevHash) {
        return joinCanonical(
                "v1",
                eventUid,
                String.valueOf(subjectId),
                subjectExternalId,
                purposeCode,
                action.name(),
                legalBasis,
                String.valueOf(policyVersionId),
                policyContentHash,
                String.valueOf(occurredAtEpochMilli),
                prevHash);
    }

    /**
     * 政策版本的内容哈希：版本号、全文与用途定义共同决定。
     * 任何字段变化都会得到不同哈希，从而证明用户同意的是哪一版。
     */
    public String policyContentHash(String policyCode, String versionLabel, String documentText,
                                    List<PolicyPurpose> purposes) {
        StringBuilder sb = new StringBuilder();
        sb.append("policyCode=").append(policyCode).append('\n');
        sb.append("versionLabel=").append(versionLabel).append('\n');
        sb.append("documentText=").append(normalizeText(documentText)).append('\n');
        purposes.stream()
                .sorted((a, b) -> a.getCode().compareTo(b.getCode()))
                .forEach(p -> sb.append("purpose=")
                        .append(p.getCode()).append(':')
                        .append(p.getName()).append(':')
                        .append(p.getLegalBasis().name()).append(':')
                        .append(p.getConsentModel().toUpperCase(Locale.ROOT)).append(':')
                        .append(p.isSensitiveData()).append(':')
                        .append(p.getRetentionMonths() == null ? "" : p.getRetentionMonths())
                        .append('\n'));
        return sha256Hex(sb.toString());
    }

    public String apiKeyHash(String apiKey) {
        // 密钥本身熵足够高，单轮 SHA-256 即可用于存储比对
        return sha256Hex("api-key:" + apiKey);
    }

    public boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").strip();
    }

    private String joinCanonical(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            String part = parts[i] == null ? "" : parts[i];
            sb.append(part.replace("\\", "\\\\").replace("|", "\\|"));
        }
        return sb.toString();
    }
}
