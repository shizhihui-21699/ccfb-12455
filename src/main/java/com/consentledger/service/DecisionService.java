package com.consentledger.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.consentledger.domain.ConsentState;
import com.consentledger.domain.ConsentSubject;
import com.consentledger.domain.ConsentStatus;
import com.consentledger.domain.LegalBasis;
import com.consentledger.repo.ConsentStateRepository;
import com.consentledger.repo.ConsentSubjectRepository;
import com.consentledger.service.DecisionCache.StateFacts;
import com.consentledger.service.PolicyCatalog.PurposeView;
import com.consentledger.web.ApiException;

/**
 * 决策引擎：业务系统在"使用数据之前"调用。
 *
 * <p>判定优先级：
 * <ol>
 *   <li><b>法定基础</b>：用途的合法性基础不是 CONSENT（履约、法定义务等），
 *       直接返回 allowed 并注明 basis——不参与同意管理，也无需用户勾选。</li>
 *   <li><b>无同意</b>：主体不存在或从未同意，返回 denied + reason。</li>
 *   <li><b>版本过期</b>：同意了旧版政策，而当前生效版本标记为实质性变更
 *       （reconsentRequired），返回 denied + POLICY_RECONSENT_REQUIRED，
 *       业务系统应引导用户重新同意，而不是继续使用数据。</li>
 *   <li><b>有效同意</b>：返回 allowed，并给出同意实际依据的政策版本。</li>
 * </ol>
 */
@Service
public class DecisionService {

    private final ConsentSubjectRepository subjectRepository;
    private final ConsentStateRepository stateRepository;
    private final PolicyCatalog policyCatalog;
    private final DecisionCache cache;

    public DecisionService(ConsentSubjectRepository subjectRepository,
                           ConsentStateRepository stateRepository,
                           PolicyCatalog policyCatalog,
                           DecisionCache cache) {
        this.subjectRepository = subjectRepository;
        this.stateRepository = stateRepository;
        this.policyCatalog = policyCatalog;
        this.cache = cache;
    }

    public record Decision(boolean allowed, String reason, String purposeCode, String purposeName,
                           LegalBasis legalBasis, Long policyVersionId, String policyVersionLabel,
                           boolean reconsentRequired, Instant decidedAt, boolean cached,
                           String subjectExternalId) {

        /** 已从政策中移除的用途：不参与处理，但也不该让调用方误以为拼错了。 */
        static Decision retired(String externalId, String code, Instant now) {
            return new Decision(false, "PURPOSE_RETIRED", code, null, null, null, null,
                    false, now, false, externalId);
        }
    }

    /** 单用途决策。 */
    public Decision decide(String subjectExternalId, String purposeCode) {
        if (purposeCode == null || purposeCode.isBlank()) {
            throw ApiException.badRequest("INVALID_PURPOSE", "purposeCode 不能为空");
        }
        PolicyCatalog.Snapshot catalog = policyCatalog.current();
        PurposeView purpose = catalog.purposes().get(purposeCode);
        if (purpose == null) {
            if (catalog.wasEverDefined(purposeCode)) {
                return Decision.retired(subjectExternalId, purposeCode, Instant.now());
            }
            throw ApiException.unprocessable("UNKNOWN_PURPOSE", "用途未在任何政策中定义：" + purposeCode);
        }
        ConsentSubject subject = subjectRepository.findByExternalId(subjectExternalId).orElse(null);
        if (subject == null) {
            return evaluate(subjectExternalId, purpose, StateFacts.unknownSubject(), false);
        }
        long subjectId = subject.getId();
        String key = DecisionCache.key(subjectId, purposeCode);
        boolean[] cached = {true};
        StateFacts facts = cache.getOrLoad(key, k -> {
            cached[0] = false;
            return loadFacts(subjectId, purposeCode);
        });
        return evaluate(subjectExternalId, purpose, facts, cached[0]);
    }

    /**
     * 批量决策：单次最多 200 个用途。缓存未命中者只需一次主体查询，
     * 每个未命中用途各一次索引查询（冷启动后即转为纯内存命中）。
     */
    public List<Decision> decideMany(String subjectExternalId, List<String> purposeCodes) {
        if (purposeCodes == null || purposeCodes.isEmpty()) {
            throw ApiException.badRequest("EMPTY_PURPOSES", "purposeCodes 不能为空");
        }
        if (purposeCodes.size() > 200) {
            throw ApiException.badRequest("TOO_MANY_PURPOSES", "单次最多查询 200 个用途");
        }
        if (new java.util.LinkedHashSet<>(purposeCodes).size() != purposeCodes.size()) {
            throw ApiException.badRequest("DUPLICATE_PURPOSE", "purposeCodes 中存在重复项");
        }
        PolicyCatalog.Snapshot catalog = policyCatalog.current();
        List<String> unknown = new ArrayList<>();
        for (String code : purposeCodes) {
            // 曾经定义过但现在不在生效政策中的用途，按"已移除"返回拒绝，而不是报错
            if (!catalog.contains(code) && !catalog.wasEverDefined(code)) {
                unknown.add(code);
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.unprocessable("UNKNOWN_PURPOSE", "存在未在任何政策中定义的用途：" + unknown);
        }

        ConsentSubject subject = subjectRepository.findByExternalId(subjectExternalId).orElse(null);
        if (subject == null) {
            List<Decision> out = new ArrayList<>(purposeCodes.size());
            for (String code : purposeCodes) {
                PurposeView purpose = catalog.purposes().get(code);
                out.add(purpose == null
                        ? Decision.retired(subjectExternalId, code, Instant.now())
                        : evaluate(subjectExternalId, purpose, StateFacts.unknownSubject(), false));
            }
            return out;
        }

        long subjectId = subject.getId();
        Map<String, Decision> byCode = new java.util.HashMap<>();
        List<String> misses = new ArrayList<>();
        for (String code : purposeCodes) {
            if (!catalog.contains(code)) {
                byCode.put(code, Decision.retired(subjectExternalId, code, Instant.now()));
                continue;
            }
            String key = DecisionCache.key(subjectId, code);
            var hit = cache.peek(key);
            if (hit.isPresent()) {
                byCode.put(code, evaluate(subjectExternalId, catalog.purposes().get(code), hit.get(), true));
            } else {
                misses.add(code);
            }
        }
        if (!misses.isEmpty()) {
            // 未命中的用途合并为一次查询，避免批量请求产生 N 次往返
            Map<String, StateFacts> loaded = new java.util.HashMap<>();
            var states = stateRepository.findBySubjectIdOrderByPurposeCodeAsc(subjectId);
            for (ConsentState state : states) {
                loaded.put(state.getPurposeCode(), toFacts(state));
            }
            Map<String, StateFacts> toCache = new java.util.HashMap<>();
            for (String code : misses) {
                StateFacts facts = loaded.getOrDefault(code, StateFacts.noState());
                toCache.put(DecisionCache.key(subjectId, code), facts);
                byCode.put(code, evaluate(subjectExternalId, catalog.purposes().get(code), facts, false));
            }
            cache.putAll(toCache);
        }
        List<Decision> out = new ArrayList<>(purposeCodes.size());
        for (String code : purposeCodes) {
            out.add(byCode.get(code));
        }
        return out;
    }

    /** 单用途缓存未命中时的加载器（Spring Data 仓储方法自身即为只读事务）。 */
    private StateFacts loadFacts(long subjectId, String purposeCode) {
        ConsentState state = stateRepository.findBySubjectIdAndPurposeCode(subjectId, purposeCode).orElse(null);
        return state == null ? StateFacts.noState() : toFacts(state);
    }

    private StateFacts toFacts(ConsentState state) {
        return new StateFacts(true, true, state.getStatus() == ConsentStatus.GRANTED,
                state.getPolicyVersionId(), state.getPolicyVersionLabel(),
                state.getGrantedAt(), state.getWithdrawnAt(), state.getUpdatedAt());
    }

    private Decision evaluate(String externalId, PurposeView purpose, StateFacts facts, boolean cached) {
        Instant now = Instant.now();
        if (purpose.legalBasis() != LegalBasis.CONSENT) {
            return new Decision(true, "LEGAL_BASIS_" + purpose.legalBasis().name(), purpose.code(),
                    purpose.name(), purpose.legalBasis(), purpose.policyVersionId(), purpose.policyVersionLabel(),
                    false, now, true, externalId);
        }
        if (!facts.granted() || facts.policyVersionId() == null) {
            String reason = !facts.subjectKnown() ? "SUBJECT_UNKNOWN" : "NO_CONSENT";
            return new Decision(false, reason, purpose.code(), purpose.name(), purpose.legalBasis(),
                    null, null, false, now, cached, externalId);
        }
        boolean staleVersion = !facts.policyVersionId().equals(purpose.policyVersionId());
        if (staleVersion && purpose.reconsentRequired()) {
            return new Decision(false, "POLICY_RECONSENT_REQUIRED", purpose.code(), purpose.name(),
                    purpose.legalBasis(), facts.policyVersionId(), facts.policyVersionLabel(),
                    true, now, cached, externalId);
        }
        return new Decision(true, "CONSENT_GRANTED", purpose.code(), purpose.name(), purpose.legalBasis(),
                facts.policyVersionId(), facts.policyVersionLabel(), false, now, cached, externalId);
    }

    /** 决策结果建议的客户端缓存秒数。 */
    public long cacheTtlSeconds() {
        return cache.ttl().toSeconds();
    }

    /** 缓存运行指标，供 /admin/api/stats 与压测脚本读取。 */
    public Map<String, Object> cacheStats() {
        var stats = cache.stats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", cache.estimatedSize());
        out.put("hitCount", stats.hitCount());
        out.put("missCount", stats.missCount());
        out.put("hitRate", Math.round(stats.hitRate() * 10000.0) / 10000.0);
        out.put("dbLoads", cache.loadCount());
        out.put("preciseInvalidations", cache.preciseInvalidationCount());
        out.put("ttlSeconds", cache.ttl().toSeconds());
        out.put("revision", cache.currentRevision());
        return out;
    }
}
