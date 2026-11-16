package com.consentledger.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 同意主体（数据主体）。externalId 由业务系统提供，是稳定主键。
 * 只保存最小识别信息——展示名可留空，甚至可只存哈希。
 */
@Entity
@Table(name = "consent_subject",
        uniqueConstraints = @UniqueConstraint(name = "uk_subject_external_id", columnNames = "external_id"),
        indexes = @Index(name = "idx_subject_created", columnList = "created_at"))
@Getter
@Setter
@NoArgsConstructor
public class ConsentSubject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(name = "display_name", length = 128)
    private String displayName;

    /** 业务来源系统，便于区分不同接入方的主体 */
    @Column(name = "source_system", length = 64)
    private String sourceSystem;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
