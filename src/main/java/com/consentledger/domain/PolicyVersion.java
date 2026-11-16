package com.consentledger.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 隐私政策的一个具体版本。同意记录永远指向一个不可变的 PolicyVersion，
 * 后续版本只新增，不回改历史版本内容。
 */
@Entity
@Table(name = "policy_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_policy_version_label", columnNames = {"policy_id", "version_label"}),
        indexes = {
                @Index(name = "idx_policy_version_status", columnList = "status"),
                @Index(name = "idx_policy_version_effective", columnList = "effective_from")
        })
@Getter
@Setter
@NoArgsConstructor
public class PolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    /** 面向用户展示的版本号，例如 v1.0、2026-11-01 */
    @Column(name = "version_label", nullable = false, length = 64)
    private String versionLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PolicyVersionStatus status = PolicyVersionStatus.DRAFT;

    /** 该版本生效时间（可预约未来生效） */
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    /** 版本内容的规范化哈希，用于证明"当时同意的是这一版" */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 相对上一版的变化说明，展示给用户与管理端 */
    @Column(name = "change_note", length = 4000)
    private String changeNote;

    /** 本版是否实质性变更：为 true 时，旧版本同意需重新获取 */
    @Column(name = "reconsent_required", nullable = false)
    private boolean reconsentRequired;

    /** 政策全文（纯文本/Markdown），存证用 */
    @Column(name = "document_text", columnDefinition = "clob")
    private String documentText;

    @Column(name = "document_url", length = 1024)
    private String documentUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @OneToMany(mappedBy = "policyVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<PolicyPurpose> purposes = new ArrayList<>();

    public void addPurpose(PolicyPurpose purpose) {
        purpose.setPolicyVersion(this);
        this.purposes.add(purpose);
    }

    public boolean isActive() {
        return status == PolicyVersionStatus.ACTIVE;
    }
}
