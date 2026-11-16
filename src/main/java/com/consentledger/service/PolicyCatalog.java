package com.consentledger.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.consentledger.domain.LegalBasis;
import com.consentledger.domain.PolicyPurpose;
import com.consentledger.domain.PolicyVersion;
import com.consentledger.repo.PolicyPurposeRepository;
import com.consentledger.repo.PolicyVersionRepository;

/**
 * 当前生效政策目录的快照缓存。
 *
 * <p>决策路径每次都要知道"这个用途现在的定义与版本"。把所有生效版本的用途
 * 展开成一张不可变 Map，仅在下列情况重建：
 * <ul>
 *   <li>数据库修订号前进（其它实例发布/撤回了政策）；</li>
 *   <li>本实例显式调用 {@link #invalidate()}（本实例发布/退役政策，
 *       在事务提交后调用）。</li>
 * </ul>
 * 由此，缓存未命中时一次决策也只需 1 次状态查询——政策目录完全在内存里。
 */
@Component
public class PolicyCatalog {

    private static final Logger log = LoggerFactory.getLogger(PolicyCatalog.class);

    private final PolicyVersionRepository versionRepository;
    private final PolicyPurposeRepository purposeRepository;
    private final DecisionCache decisionCache;
    private final TransactionTemplate transactionTemplate;
    private final AtomicLong generation = new AtomicLong();

    private volatile Snapshot snapshot = Snapshot.empty();

    public PolicyCatalog(PolicyVersionRepository versionRepository,
                         PolicyPurposeRepository purposeRepository,
                         DecisionCache decisionCache,
                         TransactionTemplate transactionTemplate) {
        this.versionRepository = versionRepository;
        this.purposeRepository = purposeRepository;
        this.decisionCache = decisionCache;
        this.transactionTemplate = transactionTemplate;
    }

    /** 一个用途在某个生效政策版本下的完整定义快照。 */
    public record PurposeView(String code, String name, String description, LegalBasis legalBasis,
                              boolean optIn, boolean sensitiveData, Integer retentionMonths,
                              String thirdParties, long policyId, String policyCode, String policyName,
                              long policyVersionId, String policyVersionLabel, String policyContentHash,
                              Instant effectiveFrom, boolean reconsentRequired) {
    }

    /**
     * @param knownPurposeCodes 历史上自定义过的全部用途编码。用于区分
     *                          "拼错/从未存在的用途"（调用方缺陷，应报错）
     *                          与"已从政策中移除的用途"（应正常返回拒绝）。
     */
    public record Snapshot(long dbRevision, long generation, Instant builtAt,
                           Map<String, PurposeView> purposes, List<PurposeView> orderedPurposes,
                           List<PolicyVersion> versions, Set<String> knownPurposeCodes) {

        static Snapshot empty() {
            return new Snapshot(-1, -1, Instant.EPOCH, Map.of(), List.of(), List.of(), Set.of());
        }

        /** 当前是否有生效政策定义该用途。 */
        public boolean contains(String purposeCode) {
            return purposes.containsKey(purposeCode);
        }

        /** 该用途是否曾经在任何政策版本中出现过。 */
        public boolean wasEverDefined(String purposeCode) {
            return knownPurposeCodes.contains(purposeCode);
        }
    }

    /** 读取与当前修订号一致的快照；版本落后时重建。 */
    public Snapshot current() {
        long db = decisionCache.currentRevision();
        long gen = generation.get();
        Snapshot s = snapshot;
        if (s.dbRevision() == db && s.generation() == gen) {
            return s;
        }
        synchronized (this) {
            if (snapshot.dbRevision() != db || snapshot.generation() != gen) {
                snapshot = rebuild(db, gen);
            }
            return snapshot;
        }
    }

    /** 本实例发布/退役政策后调用（须在事务提交后），使下一次读取重建。 */
    public void invalidate() {
        generation.incrementAndGet();
    }

    /** 启动预热：避免第一批请求同时触发重建。 */
    @Scheduled(initialDelay = 1000, fixedDelay = 60000)
    public void warmUp() {
        try {
            current();
        } catch (RuntimeException e) {
            log.warn("Policy catalog warm-up failed: {}", e.getMessage());
        }
    }

    private Snapshot rebuild(long db, long gen) {
        Instant now = Instant.now();
        // 在只读事务内加载（open-in-view 已关闭），并按 id 排序保证构建确定性
        List<PolicyVersion> versions = new ArrayList<>(transactionTemplate.execute(status -> {
            List<PolicyVersion> loaded = versionRepository.findAllActiveEffective(now);
            loaded.sort(Comparator.comparing(PolicyVersion::getId));
            return loaded;
        }));
        // 同一用途可能出现在多个政策中；以生效时间最新者为准
        versions.sort(Comparator.comparing(PolicyVersion::getEffectiveFrom).thenComparing(PolicyVersion::getId));
        Set<String> knownCodes = new HashSet<>(transactionTemplate.execute(status ->
                purposeRepository.findDistinctCodes()));
        Map<String, PurposeView> byPurpose = new LinkedHashMap<>();
        List<PolicyVersion> usedVersions = new ArrayList<>();
        for (PolicyVersion version : versions) {
            for (PolicyPurpose p : version.getPurposes()) {
                byPurpose.put(p.getCode(), new PurposeView(
                        p.getCode(), p.getName(), p.getDescription(), p.getLegalBasis(), p.optIn(),
                        p.isSensitiveData(), p.getRetentionMonths(), p.getThirdParties(),
                        version.getPolicy().getId(), version.getPolicy().getCode(), version.getPolicy().getName(),
                        version.getId(), version.getVersionLabel(), version.getContentHash(),
                        version.getEffectiveFrom(), version.isReconsentRequired()));
            }
            if (!version.getPurposes().isEmpty()) {
                usedVersions.add(version);
            }
        }
        List<PurposeView> ordered = new ArrayList<>(byPurpose.values());
        ordered.sort(Comparator.comparing(PurposeView::policyName).thenComparing(PurposeView::name));
        log.info("Policy catalog rebuilt: dbRevision={}, activeVersions={}, purposes={}",
                db, usedVersions.size(), byPurpose.size());
        return new Snapshot(db, gen, now, Map.copyOf(byPurpose), List.copyOf(ordered),
                List.copyOf(usedVersions), Set.copyOf(knownCodes));
    }
}
