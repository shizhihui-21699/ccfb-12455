package com.consentledger.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 只追加的同意事件账本——平台的事实来源。
 * 任何同意/撤回都会落一条记录，绝不修改或删除；
 * recordHash 与前一条记录的 hash 串联，形成可校验的防篡改链。
 */
@Entity
@Table(name = "consent_event",
        indexes = {
                @Index(name = "idx_event_subject_time", columnList = "subject_id, occurred_at"),
                @Index(name = "idx_event_subject_purpose", columnList = "subject_id, purpose_code"),
                @Index(name = "idx_event_time", columnList = "occurred_at"),
                @Index(name = "idx_event_batch", columnList = "batch_id"),
                @Index(name = "idx_event_uid", columnList = "event_uid")
        })
@Getter
@Setter
@NoArgsConstructor
public class ConsentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外暴露的不可猜测事件 ID */
    @Column(name = "event_uid", nullable = false, length = 36)
    private String eventUid;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "subject_external_id", nullable = false, length = 128)
    private String subjectExternalId;

    @Column(name = "purpose_code", nullable = false, length = 64)
    private String purposeCode;

    @Column(name = "purpose_name", nullable = false, length = 128)
    private String purposeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConsentAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "resulting_status", nullable = false, length = 16)
    private ConsentStatus resultingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "legal_basis", nullable = false, length = 32)
    private LegalBasis legalBasis;

    /** 政策版本快照——证明确切是哪一版 */
    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "policy_code", nullable = false, length = 64)
    private String policyCode;

    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;

    @Column(name = "policy_version_label", nullable = false, length = 64)
    private String policyVersionLabel;

    @Column(name = "policy_content_hash", nullable = false, length = 64)
    private String policyContentHash;

    /** 用户当时实际看到的用途集合（JSON 数组），证明告知范围 */
    @Column(name = "displayed_purposes", columnDefinition = "clob")
    private String displayedPurposes;

    /** 同一屏一次性勾选产生的多条记录共享 batchId */
    @Column(name = "batch_id", length = 36)
    private String batchId;

    /** 渠道：WEB / APP / API / ADMIN / IMPORT */
    @Column(nullable = false, length = 24)
    private String channel;

    /** 操作者：用户本人、客服工号等 */
    @Column(name = "actor", length = 128)
    private String actor;

    @Column(name = "client_name", length = 128)
    private String clientName;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "statement", length = 2000)
    private String statement;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt = Instant.now();

    /** 前一条事件的 recordHash，形成链 */
    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    /** HMAC-SHA256(prevHash + 规范化字段) */
    @Column(name = "record_hash", nullable = false, length = 64)
    private String recordHash;
}
