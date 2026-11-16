package com.consentledger.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.consentledger.domain.LegalBasis;
import com.consentledger.domain.Policy;
import com.consentledger.domain.PolicyPurpose;
import com.consentledger.domain.PolicyVersion;
import com.consentledger.domain.PolicyVersionStatus;
import com.consentledger.repo.PolicyRepository;
import com.consentledger.repo.PolicyVersionRepository;
import com.consentledger.web.ApiException;

/**
 * 政策生命周期：新建政策 → 起草版本 → 发布（可预约生效）→ 新版本取代旧版本。
 * 已发布版本内容不可修改；任何调整必须产生新版本，历史同意才有据可查。
 */
@Service
public class PolicyService {

    private final PolicyRepository policyRepository;
    private final PolicyVersionRepository versionRepository;
    private final Hashing hashing;
    private final DecisionCache decisionCache;
    private final PolicyCatalog policyCatalog;
    private final ConsentAuditService auditService;

    public PolicyService(PolicyRepository policyRepository,
                         PolicyVersionRepository versionRepository,
                         Hashing hashing,
                         DecisionCache decisionCache,
                         PolicyCatalog policyCatalog,
                         ConsentAuditService auditService) {
        this.policyRepository = policyRepository;
        this.versionRepository = versionRepository;
        this.hashing = hashing;
        this.decisionCache = decisionCache;
        this.policyCatalog = policyCatalog;
        this.auditService = auditService;
    }

    public record PurposeDraft(String code, String name, String description, LegalBasis legalBasis,
                               boolean optIn, boolean sensitiveData, Integer retentionMonths,
                               String thirdParties) {
    }

    public record CreatePolicyRequest(String code, String name, String description, String controllerName) {
    }

    public record CreateVersionRequest(String versionLabel, Instant effectiveFrom, String changeNote,
                                       boolean reconsentRequired, String documentText, String documentUrl,
                                       List<PurposeDraft> purposes) {
    }

    @Transactional
    public Policy createPolicy(CreatePolicyRequest request, String actor) {
        if (request.code() == null || !request.code().matches("[a-z0-9][a-z0-9_-]{1,63}")) {
            throw ApiException.badRequest("INVALID_POLICY_CODE", "政策编码须为 2-64 位小写字母/数字/下划线/短横线");
        }
        if (policyRepository.existsByCode(request.code())) {
            throw ApiException.conflict("POLICY_EXISTS", "政策编码已存在：" + request.code(), null);
        }
        Policy policy = new Policy();
        policy.setCode(request.code());
        policy.setName(request.name());
        policy.setDescription(request.description());
        policy.setControllerName(request.controllerName());
        Policy saved = policyRepository.save(policy);
        auditService.record(actor, "POLICY_CREATED", "POLICY", saved.getCode(), saved.getName());
        return saved;
    }

    @Transactional
    public PolicyVersion createVersion(Long policyId, CreateVersionRequest request, String actor) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> ApiException.notFound("POLICY_NOT_FOUND", "政策不存在：" + policyId));
        if (request.versionLabel() == null || request.versionLabel().isBlank()) {
            throw ApiException.badRequest("INVALID_VERSION_LABEL", "版本号不能为空");
        }
        if (versionRepository.existsByPolicyIdAndVersionLabel(policyId, request.versionLabel())) {
            throw ApiException.conflict("VERSION_EXISTS",
                    "该政策已存在版本 " + request.versionLabel(), null);
        }
        if (request.purposes() == null || request.purposes().isEmpty()) {
            throw ApiException.badRequest("NO_PURPOSES", "政策版本至少需要一个用途");
        }
        List<String> codes = request.purposes().stream().map(PurposeDraft::code).toList();
        if (codes.stream().anyMatch(c -> c == null || c.isBlank())) {
            throw ApiException.badRequest("INVALID_PURPOSE", "用途编码不能为空");
        }
        if (new java.util.HashSet<>(codes).size() != codes.size()) {
            throw ApiException.badRequest("DUPLICATE_PURPOSE", "同一版本中用途编码不可重复");
        }

        PolicyVersion version = new PolicyVersion();
        version.setPolicy(policy);
        version.setVersionLabel(request.versionLabel());
        version.setStatus(PolicyVersionStatus.DRAFT);
        version.setEffectiveFrom(request.effectiveFrom() == null ? Instant.now() : request.effectiveFrom());
        version.setChangeNote(request.changeNote());
        version.setReconsentRequired(request.reconsentRequired());
        version.setDocumentText(request.documentText());
        version.setDocumentUrl(request.documentUrl());
        version.setCreatedBy(actor);

        int order = 0;
        for (PurposeDraft draft : request.purposes()) {
            PolicyPurpose p = new PolicyPurpose();
            p.setCode(draft.code());
            p.setName(draft.name());
            p.setDescription(draft.description());
            p.setLegalBasis(draft.legalBasis() == null ? LegalBasis.CONSENT : draft.legalBasis());
            p.setConsentModel(draft.optIn() ? "OPT_IN" : "OPT_OUT");
            p.setSensitiveData(draft.sensitiveData());
            p.setRetentionMonths(draft.retentionMonths());
            p.setThirdParties(draft.thirdParties());
            p.setSortOrder(order++);
            version.addPurpose(p);
        }
        version.setContentHash(hashing.policyContentHash(policy.getCode(), version.getVersionLabel(),
                version.getDocumentText(), version.getPurposes()));

        PolicyVersion saved = versionRepository.save(version);
        auditService.record(actor, "VERSION_DRAFTED", "POLICY_VERSION",
                policy.getCode() + "@" + saved.getVersionLabel(), saved.getContentHash());
        return saved;
    }

    /**
     * 发布版本：置为 ACTIVE，同政策其它 ACTIVE 版本转为 SUPERSEDED。
     * 预约生效的版本也在发布后等待 effectiveFrom 到达才参与决策。
     */
    @Transactional
    public PolicyVersion publish(Long versionId, String actor) {
        PolicyVersion version = versionRepository.findWithPurposesById(versionId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "政策版本不存在：" + versionId));
        if (version.getStatus() == PolicyVersionStatus.ACTIVE) {
            return version;
        }
        if (version.getStatus() == PolicyVersionStatus.RETIRED) {
            throw ApiException.conflict("VERSION_RETIRED", "已退役版本不可再次发布", null);
        }
        // 内容可能在建稿后被编辑过：重新计算哈希，确保与用户将看到的内容一致
        version.setContentHash(hashing.policyContentHash(
                version.getPolicy().getCode(), version.getVersionLabel(),
                version.getDocumentText(), version.getPurposes()));
        version.setStatus(PolicyVersionStatus.ACTIVE);
        version.setPublishedAt(Instant.now());
        versionRepository.saveAndFlush(version);
        versionRepository.supersedeOthers(version.getPolicy().getId(), version.getId());

        decisionCache.bumpRevision();
        TransactionHooks.afterCommit(() -> {
            policyCatalog.invalidate();
            decisionCache.invalidateAllLocal();
        });
        auditService.record(actor, "VERSION_PUBLISHED", "POLICY_VERSION",
                version.getPolicy().getCode() + "@" + version.getVersionLabel(),
                "effectiveFrom=" + version.getEffectiveFrom() + ", reconsent=" + version.isReconsentRequired());
        return version;
    }

    @Transactional
    public PolicyVersion retire(Long versionId, String actor) {
        PolicyVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "政策版本不存在：" + versionId));
        version.setStatus(PolicyVersionStatus.RETIRED);
        versionRepository.save(version);
        decisionCache.bumpRevision();
        TransactionHooks.afterCommit(() -> {
            policyCatalog.invalidate();
            decisionCache.invalidateAllLocal();
        });
        auditService.record(actor, "VERSION_RETIRED", "POLICY_VERSION",
                version.getPolicy().getCode() + "@" + version.getVersionLabel(), null);
        return version;
    }

    /** 从既有版本复制一份草稿，便于"改一版"。 */
    @Transactional
    public PolicyVersion cloneAsDraft(Long sourceVersionId, String newLabel, boolean reconsentRequired,
                                      String changeNote, String actor) {
        PolicyVersion source = versionRepository.findWithPurposesById(sourceVersionId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "政策版本不存在：" + sourceVersionId));
        List<PurposeDraft> drafts = new ArrayList<>();
        for (PolicyPurpose p : source.getPurposes()) {
            drafts.add(new PurposeDraft(p.getCode(), p.getName(), p.getDescription(), p.getLegalBasis(),
                    p.optIn(), p.isSensitiveData(), p.getRetentionMonths(), p.getThirdParties()));
        }
        return createVersion(source.getPolicy().getId(),
                new CreateVersionRequest(newLabel, Instant.now(), changeNote, reconsentRequired,
                        source.getDocumentText(), source.getDocumentUrl(), drafts),
                actor);
    }

    public List<PolicyVersion> versionsOf(Long policyId) {
        return versionRepository.findByPolicyIdOrderByEffectiveFromDesc(policyId);
    }

    public PolicyVersion requireVersion(Long id) {
        return versionRepository.findWithPurposesById(id)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "政策版本不存在：" + id));
    }

    /** 当前决策使用的版本集合，用于管理端总览。 */
    public List<PolicyVersion> activeVersions() {
        return versionRepository.findAllActiveEffective(Instant.now());
    }
}
