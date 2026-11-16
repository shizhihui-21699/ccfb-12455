package com.consentledger.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.consentledger.config.ConsentProperties;
import com.consentledger.domain.ConsentAction;
import com.consentledger.domain.ConsentChain;
import com.consentledger.domain.ConsentEvent;
import com.consentledger.domain.ConsentState;
import com.consentledger.domain.ConsentStatus;
import com.consentledger.domain.ConsentSubject;
import com.consentledger.domain.LegalBasis;
import com.consentledger.repo.ConsentChainRepository;
import com.consentledger.repo.ConsentEventRepository;
import com.consentledger.repo.ConsentStateRepository;
import com.consentledger.repo.ConsentSubjectRepository;
import com.consentledger.service.PolicyCatalog.PurposeView;
import com.consentledger.service.PolicyCatalog.Snapshot;
import com.consentledger.web.ApiException;

/**
 * 同意记录的写入核心。所有写入走同一事务：
 * 1) 对主体链头加行锁，串行化同一主体的并发写入；
 * 2) 逐条追加事件并推进链哈希；
 * 3) 更新状态投影；
 * 4) 提交后精确失效决策缓存。
 */
@Service
public class ConsentService {

    private final ConsentSubjectRepository subjectRepository;
    private final SubjectRegistry subjectRegistry;
    private final ConsentEventRepository eventRepository;
    private final ConsentStateRepository stateRepository;
    private final ConsentChainRepository chainRepository;
    private final PolicyCatalog policyCatalog;
    private final DecisionCache decisionCache;
    private final Hashing hashing;
    private final ConsentProperties properties;

    public ConsentService(ConsentSubjectRepository subjectRepository,
                          SubjectRegistry subjectRegistry,
                          ConsentEventRepository eventRepository,
                          ConsentStateRepository stateRepository,
                          ConsentChainRepository chainRepository,
                          PolicyCatalog policyCatalog,
                          DecisionCache decisionCache,
                          Hashing hashing,
                          ConsentProperties properties) {
        this.subjectRepository = subjectRepository;
        this.subjectRegistry = subjectRegistry;
        this.eventRepository = eventRepository;
        this.stateRepository = stateRepository;
        this.chainRepository = chainRepository;
        this.policyCatalog = policyCatalog;
        this.decisionCache = decisionCache;
        this.hashing = hashing;
        this.properties = properties;
    }

    /** 幂等地取回或创建主体（并发安全，见 {@link SubjectRegistry}）。 */
    public ConsentSubject ensureSubject(String externalId, String displayName, String sourceSystem) {
        return subjectRegistry.resolve(externalId, displayName, sourceSystem);
    }

    public record ChangeRequest(String purposeCode, ConsentAction action) {
    }

    public record WriteContext(String channel, String actor, String clientName, String requestId,
                               String ipAddress, String userAgent, Instant occurredAt) {

        public static WriteContext of(String channel, String actor, String clientName, String requestId,
                                     String ipAddress, String userAgent) {
            return new WriteContext(channel, actor, clientName, requestId, ipAddress, userAgent, null);
        }

        Instant effectiveTime() {
            return occurredAt == null ? Instant.now() : occurredAt;
        }
    }

    public record WriteResult(long subjectId, String subjectExternalId, String batchId,
                              List<ConsentEvent> events, List<ConsentState> states) {
    }

    /**
     * 批量记录同意/撤回。任一用途校验失败则整批不写（要么用户看到的就是要么全同意，
     * 要么产品侧明确分开提交）。
     */
    @Transactional
    public WriteResult record(String subjectExternalId, String displayName, String sourceSystem,
                              List<ChangeRequest> requests, WriteContext ctx) {
        if (requests == null || requests.isEmpty()) {
            throw ApiException.badRequest("EMPTY_BATCH", "至少需要一条同意或撤回记录");
        }
        // 去重：同一批不允许同一用途出现两次
        Map<String, ConsentAction> normalized = new LinkedHashMap<>();
        for (ChangeRequest r : requests) {
            if (r.purposeCode() == null || r.purposeCode().isBlank()) {
                throw ApiException.badRequest("INVALID_PURPOSE", "purposeCode 不能为空");
            }
            if (normalized.putIfAbsent(r.purposeCode(), r.action()) != null) {
                throw ApiException.conflict("DUPLICATE_PURPOSE",
                        "同一批次内用途重复：" + r.purposeCode(), Map.of("purposeCode", r.purposeCode()));
            }
        }

        ConsentSubject subject = ensureSubject(subjectExternalId, displayName, sourceSystem);
        Snapshot catalog = policyCatalog.current();
        String batchId = UUID.randomUUID().toString();

        // 链头必须在主事务之外先建好，主事务才能对它加行锁
        subjectRegistry.ensureChain(subject.getId());
        ConsentChain chain = chainRepository.lockBySubjectId(subject.getId())
                .orElseThrow(() -> new IllegalStateException("主体哈希链缺失：" + subject.getId()));

        // 取得行锁之后再取时间：保证 occurredAt 与事件写入顺序单调一致。
        // 回填历史数据时由调用方显式提供 occurredAt。
        Instant occurredAt = ctx.effectiveTime();

        List<ConsentEvent> createdEvents = new ArrayList<>();
        List<ConsentState> updatedStates = new ArrayList<>();

        for (Map.Entry<String, ConsentAction> entry : normalized.entrySet()) {
            String purposeCode = entry.getKey();
            ConsentAction action = entry.getValue();
            PurposeView purpose = catalog.purposes().get(purposeCode);
            if (purpose == null) {
                if (catalog.wasEverDefined(purposeCode)) {
                    throw ApiException.unprocessable("PURPOSE_RETIRED",
                            "用途 " + purposeCode + " 已从当前生效政策中移除，不再接受同意或撤回");
                }
                throw ApiException.unprocessable("UNKNOWN_PURPOSE",
                        "用途未在任何政策中定义：" + purposeCode);
            }
            // 非同意基础的处理（履约、法定义务等）不应出现在同意账本里：
            // 记下来反而会让监管误以为"我们靠同意处理"，也容易被误解为可撤回。
            if (purpose.legalBasis() != LegalBasis.CONSENT) {
                throw ApiException.unprocessable("NOT_CONSENT_BASED",
                        "用途 " + purposeCode + " 的合法性基础是 " + purpose.legalBasis()
                                + "，不通过同意管理，无需也无法记录同意/撤回");
            }

            ConsentState state = stateRepository.findBySubjectIdAndPurposeCode(subject.getId(), purposeCode)
                    .orElse(null);

            // 已处于撤回态再撤回：幂等跳过，避免账本被重复点击灌满
            if (action == ConsentAction.WITHDRAW && state != null && state.getStatus() == ConsentStatus.WITHDRAWN) {
                continue;
            }

            ConsentEvent event = new ConsentEvent();
            event.setEventUid(UUID.randomUUID().toString());
            event.setSubjectId(subject.getId());
            event.setSubjectExternalId(subject.getExternalId());
            event.setPurposeCode(purposeCode);
            event.setPurposeName(purpose.name());
            event.setAction(action);
            event.setResultingStatus(action == ConsentAction.GRANT ? ConsentStatus.GRANTED : ConsentStatus.WITHDRAWN);
            event.setLegalBasis(purpose.legalBasis());
            event.setPolicyId(purpose.policyId());
            event.setPolicyCode(purpose.policyCode());
            event.setPolicyVersionId(purpose.policyVersionId());
            event.setPolicyVersionLabel(purpose.policyVersionLabel());
            event.setPolicyContentHash(purpose.policyContentHash());
            event.setDisplayedPurposes(displayedPurposesJson(catalog, purposeCode));
            event.setBatchId(batchId);
            event.setChannel(ctx.channel());
            event.setActor(ctx.actor());
            event.setClientName(ctx.clientName());
            event.setRequestId(normalizeRequestId(ctx.requestId()));
            event.setIpAddress(ctx.ipAddress());
            event.setUserAgent(truncate(ctx.userAgent(), 512));
            event.setStatement(statementFor(action, purpose));
            event.setOccurredAt(occurredAt);
            event.setPrevHash(chain.getLastHash());
            event.setRecordHash(hashing.hmacSha256Hex(properties.getReceiptSigningSecret(),
                    hashing.canonicalEvent(event)));
            eventRepository.save(event);

            chain.setLastEventId(event.getId());
            chain.setLastHash(event.getRecordHash());
            chain.setEventCount(chain.getEventCount() + 1);
            chain.setUpdatedAt(occurredAt);

            ConsentState target = state;
            if (target == null) {
                target = new ConsentState();
                target.setSubjectId(subject.getId());
                target.setSubjectExternalId(subject.getExternalId());
                target.setPurposeCode(purposeCode);
            }
            target.setStatus(event.getResultingStatus());
            target.setLastEventId(event.getId());
            target.setPolicyVersionId(purpose.policyVersionId());
            target.setPolicyVersionLabel(purpose.policyVersionLabel());
            target.setUpdatedAt(occurredAt);
            if (action == ConsentAction.GRANT) {
                target.setGrantedAt(occurredAt);
            } else {
                target.setWithdrawnAt(occurredAt);
            }
            stateRepository.save(target);

            createdEvents.add(event);
            updatedStates.add(target);
        }

        chainRepository.save(chain);
        decisionCache.bumpRevision();

        // 事务提交后再失效缓存，避免并发读到未提交数据
        TransactionHooks.afterCommit(() -> createdEvents.forEach(
                e -> decisionCache.invalidateLocal(DecisionCache.key(e.getSubjectId(), e.getPurposeCode()))));

        return new WriteResult(subject.getId(), subject.getExternalId(), batchId, createdEvents, updatedStates);
    }

    private String statementFor(ConsentAction action, PurposeView purpose) {
        if (action == ConsentAction.GRANT) {
            return "本人已阅读并同意《" + purpose.policyName() + "》" + purpose.policyVersionLabel()
                    + "，同意为「" + purpose.name() + "」目的处理相关个人信息。";
        }
        return "本人撤回对「" + purpose.name() + "」目的的同意，撤回即时生效。";
    }

    private String displayedPurposesJson(Snapshot catalog, String highlighted) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (PurposeView p : catalog.orderedPurposes()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"code\":").append(json(p.code()))
                    .append(",\"name\":").append(json(p.name()))
                    .append(",\"legalBasis\":").append(json(p.legalBasis().name()))
                    .append(",\"policyVersion\":").append(json(p.policyVersionLabel()))
                    .append(",\"highlighted\":").append(p.code().equals(highlighted))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private String json(String value) {
        if (value == null) {
            return "null";
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private String normalizeRequestId(String requestId) {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public Optional<ConsentSubject> findSubject(String externalId) {
        return subjectRepository.findByExternalId(externalId);
    }

    public List<ConsentState> statesFor(Long subjectId) {
        return stateRepository.findBySubjectIdOrderByPurposeCodeAsc(subjectId);
    }

    public List<ConsentEvent> historyFor(Long subjectId) {
        return eventRepository.findBySubjectIdOrderByIdAsc(subjectId);
    }

    public ConsentChain chainFor(Long subjectId) {
        return chainRepository.findBySubjectId(subjectId).orElse(null);
    }
}
