package com.consentledger.web.dto;

import java.time.Instant;
import java.util.List;

import com.consentledger.domain.ConsentAction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * /api/v1 的请求与响应契约。字段名与业务系统直连，避免多一层映射。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    // ---------- 决策 ----------

    public record DecisionQuery(@NotBlank @Size(max = 128) String subjectId,
                                @NotEmpty @Size(max = 200) List<@NotBlank String> purposes,
                                /** 可选：业务方自己的幂等/追踪号，会回显并记入日志 */
                                @Size(max = 64) String requestId) {
    }

    public record DecisionItem(boolean allowed, String reason, String purposeCode, String purposeName,
                               String legalBasis, Long policyVersionId, String policyVersionLabel,
                               boolean reconsentRequired, boolean cached) {
    }

    public record DecisionResponse(String subjectId, Instant evaluatedAt, String requestId,
                                   int cacheTtlSeconds, List<DecisionItem> decisions) {
    }

    // ---------- 同意记录 ----------

    public record ConsentChange(@NotBlank String purposeCode,
                                @NotNull ConsentAction action) {
    }

    public record ConsentWriteRequest(@NotBlank @Size(max = 128) String subjectId,
                                      @Size(max = 128) String displayName,
                                      @Size(max = 64) String sourceSystem,
                                      @NotEmpty @Size(max = 50) @Valid List<ConsentChange> changes,
                                      /** 渠道：WEB/APP/API/OFFLINE，默认 API */
                                      @Size(max = 24) String channel,
                                      /** 操作者，缺省视为用户本人 */
                                      @Size(max = 128) String actor,
                                      @Size(max = 64) String requestId) {
    }

    public record ConsentEventItem(String eventUid, String purposeCode, String action, String resultingStatus,
                                   String legalBasis, Long policyVersionId, String policyVersionLabel,
                                   String policyContentHash, Instant occurredAt, String recordHash) {
    }

    public record ConsentStateItem(String purposeCode, String status, Long policyVersionId,
                                   String policyVersionLabel, Instant grantedAt, Instant withdrawnAt) {
    }

    public record ConsentWriteResponse(long subjectId, String subjectIdExternal, String batchId,
                                       List<ConsentStateItem> states, List<ConsentEventItem> events) {
    }

    public record SubjectStatusResponse(String subjectId, String displayName, String sourceSystem,
                                        Instant registeredAt, List<ConsentStateItem> states,
                                        int eventCount, String chainHeadHash) {
    }

    // ---------- 政策目录 ----------

    public record PurposeView(String code, String name, String description, String legalBasis,
                              boolean optIn, boolean sensitiveData, Integer retentionMonths,
                              String thirdParties, String policyCode, String policyName,
                              Long policyVersionId, String policyVersionLabel, String policyContentHash,
                              Instant effectiveFrom, boolean reconsentRequired) {
    }

    public record PolicyVersionView(Long id, String policyCode, String policyName, String versionLabel,
                                    String status, Instant effectiveFrom, Instant publishedAt,
                                    String contentHash, String changeNote, boolean reconsentRequired,
                                    List<PurposeView> purposes) {
    }

    public record PurposeCatalogResponse(Instant generatedAt, List<PurposeView> purposes) {
    }

}
