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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 当前状态投影：每个 (主体, 用途) 一行。决策接口只读这张表，
 * 避免在高并发查询时聚合整个事件历史。它由事件写入事务同步维护，
 * 随时可从 consent_event 重算。
 */
@Entity
@Table(name = "consent_state",
        uniqueConstraints = @UniqueConstraint(name = "uk_state_subject_purpose", columnNames = {"subject_id", "purpose_code"}),
        indexes = @Index(name = "idx_state_purpose_status", columnList = "purpose_code, status"))
@Getter
@Setter
@NoArgsConstructor
public class ConsentState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "subject_external_id", nullable = false, length = 128)
    private String subjectExternalId;

    @Column(name = "purpose_code", nullable = false, length = 64)
    private String purposeCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConsentStatus status;

    @Column(name = "last_event_id", nullable = false)
    private Long lastEventId;

    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;

    @Column(name = "policy_version_label", nullable = false, length = 64)
    private String policyVersionLabel;

    @Column(name = "granted_at")
    private Instant grantedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public boolean isUsable() {
        return status == ConsentStatus.GRANTED;
    }
}
