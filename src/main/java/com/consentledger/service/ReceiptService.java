package com.consentledger.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.consentledger.config.ConsentProperties;
import com.consentledger.domain.ConsentChain;
import com.consentledger.domain.ConsentEvent;
import com.consentledger.domain.ConsentState;
import com.consentledger.domain.ConsentSubject;
import com.consentledger.repo.ConsentChainRepository;
import com.consentledger.repo.ConsentEventRepository;
import com.consentledger.repo.ConsentStateRepository;
import com.consentledger.repo.ConsentSubjectRepository;
import com.consentledger.web.ApiException;

/**
 * 同意凭证导出：一次导出即一份可提交监管的完整证据包。
 *
 * <p>包含：主体标识、当前各用途状态、按时间排序的全部事件（含政策版本号与
 * 内容哈希、用户看到的用途清单、渠道/IP/UA）、整条哈希链的校验结果，
 * 并对整份凭证做 HMAC 签名。任何人拿到 JSON 都可以用公开算法复算事件链。
 */
@Service
public class ReceiptService {

    private final ConsentSubjectRepository subjectRepository;
    private final ConsentStateRepository stateRepository;
    private final ConsentEventRepository eventRepository;
    private final ConsentChainRepository chainRepository;
    private final Hashing hashing;
    private final ConsentProperties properties;

    public ReceiptService(ConsentSubjectRepository subjectRepository,
                          ConsentStateRepository stateRepository,
                          ConsentEventRepository eventRepository,
                          ConsentChainRepository chainRepository,
                          Hashing hashing,
                          ConsentProperties properties) {
        this.subjectRepository = subjectRepository;
        this.stateRepository = stateRepository;
        this.eventRepository = eventRepository;
        this.chainRepository = chainRepository;
        this.hashing = hashing;
        this.properties = properties;
    }

    public record Receipt(Map<String, Object> document) {
    }

    public Receipt build(String subjectExternalId, boolean includeEventPayload) {
        ConsentSubject subject = subjectRepository.findByExternalId(subjectExternalId)
                .orElseThrow(() -> ApiException.notFound("SUBJECT_NOT_FOUND",
                        "未找到该主体，无法导出凭证：" + subjectExternalId));
        // 按写入顺序列出：这是哈希链的定义顺序，也是账本的事实顺序
        List<ConsentEvent> events = eventRepository.findBySubjectIdOrderByIdAsc(subject.getId());
        List<ConsentState> states = stateRepository.findBySubjectIdOrderByPurposeCodeAsc(subject.getId());
        ConsentChain chain = chainRepository.findBySubjectId(subject.getId()).orElse(null);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("receiptFormat", "consent-ledger/receipt/v1");
        doc.put("generatedAt", Instant.now().toString());
        doc.put("controller", controllerName());
        doc.put("subject", subjectBlock(subject));
        doc.put("summary", summaryBlock(states));
        doc.put("purposes", purposesBlock(states));
        if (includeEventPayload) {
            List<Map<String, Object>> eventList = new ArrayList<>(events.size());
            for (ConsentEvent e : events) {
                eventList.add(eventBlock(e));
            }
            doc.put("events", eventList);
        } else {
            doc.put("events", List.of());
            doc.put("eventCount", events.size());
        }
        doc.put("chain", chainBlock(chain, events));
        doc.put("verification", verify(events, chain));

        // 对除 signature 外的所有内容做规范化签名
        String canonical = canonicalReceipt(doc);
        doc.put("signature", Map.of(
                "algorithm", Hashing.SIGNATURE_ALGORITHM,
                "keyId", keyId(),
                "value", hashing.hmacSha256Hex(properties.getReceiptSigningSecret(), canonical),
                "canonicalForm", "canonical-json-v1 over all keys except signature; "
                        + "chain uses event recordHash values in event order"));
        doc.put("notice", "本凭证由同意管理平台自动生成。事件 recordHash 由平台密钥进行 HMAC-SHA256 计算，"
                + "链式串联可证明未被篡改；如需独立验证请联系平台获取校验接口。");
        return new Receipt(doc);
    }

    public String sign(Map<String, Object> document) {
        Map<String, Object> withoutSignature = new LinkedHashMap<>(document);
        withoutSignature.remove("signature");
        return hashing.hmacSha256Hex(properties.getReceiptSigningSecret(), canonicalReceipt(withoutSignature));
    }

    private String keyId() {
        return "hmac-" + hashing.sha256Hex(properties.getReceiptSigningSecret()).substring(0, 12);
    }

    private String controllerName() {
        return properties.getDemoData().isEnabled() ? "演示数据控制者（示例科技有限公司）" : "数据控制者";
    }

    private Map<String, Object> subjectBlock(ConsentSubject subject) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("externalId", subject.getExternalId());
        block.put("displayName", subject.getDisplayName());
        block.put("sourceSystem", subject.getSourceSystem());
        block.put("registeredAt", subject.getCreatedAt().toString());
        return block;
    }

    private Map<String, Object> summaryBlock(List<ConsentState> states) {
        long granted = states.stream().filter(ConsentState::isUsable).count();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("totalPurposes", states.size());
        block.put("granted", granted);
        block.put("withdrawn", states.size() - granted);
        return block;
    }

    private List<Map<String, Object>> purposesBlock(List<ConsentState> states) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ConsentState state : states) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("purposeCode", state.getPurposeCode());
            p.put("status", state.getStatus().name());
            p.put("policyVersionId", state.getPolicyVersionId());
            p.put("policyVersionLabel", state.getPolicyVersionLabel());
            p.put("grantedAt", state.getGrantedAt() == null ? null : state.getGrantedAt().toString());
            p.put("withdrawnAt", state.getWithdrawnAt() == null ? null : state.getWithdrawnAt().toString());
            p.put("lastEventId", state.getLastEventId());
            list.add(p);
        }
        return list;
    }

    private Map<String, Object> eventBlock(ConsentEvent e) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("eventUid", e.getEventUid());
        block.put("action", e.getAction().name());
        block.put("resultingStatus", e.getResultingStatus().name());
        block.put("purposeCode", e.getPurposeCode());
        block.put("purposeName", e.getPurposeName());
        block.put("legalBasis", e.getLegalBasis().name());
        block.put("policyCode", e.getPolicyCode());
        block.put("policyVersionId", e.getPolicyVersionId());
        block.put("policyVersionLabel", e.getPolicyVersionLabel());
        block.put("policyContentHash", e.getPolicyContentHash());
        block.put("displayedPurposes", e.getDisplayedPurposes());
        block.put("statement", e.getStatement());
        block.put("batchId", e.getBatchId());
        block.put("channel", e.getChannel());
        block.put("actor", e.getActor());
        block.put("clientName", e.getClientName());
        block.put("requestId", e.getRequestId());
        block.put("ipAddress", e.getIpAddress());
        block.put("userAgent", e.getUserAgent());
        block.put("occurredAt", e.getOccurredAt().toString());
        block.put("recordedAt", e.getRecordedAt().toString());
        block.put("prevHash", e.getPrevHash());
        block.put("recordHash", e.getRecordHash());
        block.put("canonicalForm", "v1|eventUid|subjectId|subjectExternalId|purposeCode|action|legalBasis|"
                + "policyVersionId|policyContentHash|occurredAtEpochMilli|prevHash");
        block.put("orderNote", "events 按写入顺序（自增 id）排列，与 prevHash 链一致；occurredAt 为业务时间，"
                + "历史数据回填时可能不与写入顺序一致。");
        return block;
    }

    private Map<String, Object> chainBlock(ConsentChain chain, List<ConsentEvent> events) {
        Map<String, Object> block = new LinkedHashMap<>();
        if (chain != null) {
            block.put("lastEventId", chain.getLastEventId());
            block.put("lastHash", chain.getLastHash());
            block.put("eventCount", chain.getEventCount());
            block.put("updatedAt", chain.getUpdatedAt().toString());
        } else {
            block.put("lastEventId", 0);
            block.put("lastHash", Hashing.GENESIS_HASH);
            block.put("eventCount", 0);
        }
        if (!events.isEmpty()) {
            block.put("firstPrevHash", events.get(0).getPrevHash());
            block.put("lastRecordHash", events.get(events.size() - 1).getRecordHash());
        }
        block.put("genesisHash", Hashing.GENESIS_HASH);
        return block;
    }

    /** 复算整条链，返回逐条校验结论。 */
    public Map<String, Object> verify(List<ConsentEvent> events, ConsentChain chain) {
        List<Map<String, Object>> failures = new ArrayList<>();
        String expectedPrev = Hashing.GENESIS_HASH;
        for (ConsentEvent e : events) {
            String canonical = hashing.canonicalEvent(e);
            String recomputed = hashing.hmacSha256Hex(properties.getReceiptSigningSecret(), canonical);
            List<String> errors = new ArrayList<>();
            if (!expectedPrev.equals(e.getPrevHash())) {
                errors.add("prevHash 与上一条 recordHash 不一致");
            }
            if (!hashing.constantTimeEquals(recomputed, e.getRecordHash())) {
                errors.add("recordHash 复算不一致");
            }
            if (!errors.isEmpty()) {
                failures.add(Map.of("eventUid", e.getEventUid(), "errors", errors));
            }
            expectedPrev = e.getRecordHash();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chainValid", failures.isEmpty());
        result.put("eventsVerified", events.size());
        result.put("failures", failures);
        result.put("algorithm", Hashing.SIGNATURE_ALGORITHM);
        result.put("note", "recordHash = HMAC-SHA256(secret, canonicalEvent)；prevHash 将事件串成链。"
                + "篡改任一字段或调换顺序都会使链校验失败。");
        if (chain != null && !events.isEmpty()) {
            result.put("headMatchesLatestEvent",
                    chain.getLastHash().equals(events.get(events.size() - 1).getRecordHash()));
        }
        return result;
    }

    /** 稳定 JSON：递归按键排序，保证不同实现可复算同一签名。 */
    @SuppressWarnings("unchecked")
    private String canonicalReceipt(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void writeCanonical(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            List<String> keys = new ArrayList<>();
            for (Object k : map.keySet()) {
                keys.add(String.valueOf(k));
            }
            keys.sort(String::compareTo);
            boolean first = true;
            for (String key : keys) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(key)).append(':');
                writeCanonical(map.get(key), sb);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeCanonical(list.get(i), sb);
            }
            sb.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append(quote(String.valueOf(value)));
        }
    }

    private String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
