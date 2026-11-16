package com.consentledger.domain;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 某个政策版本下的一个"用途"快照。用途的说明、合法性基础、留存期限都是
 * 版本化的——同一用途在新版本里改了描述，旧记录仍能看到当时的定义。
 */
@Entity
@Table(name = "policy_purpose",
        uniqueConstraints = @UniqueConstraint(name = "uk_policy_purpose_code", columnNames = {"policy_version_id", "code"}),
        indexes = @Index(name = "idx_policy_purpose_code", columnList = "code"))
@Getter
@Setter
@NoArgsConstructor
public class PolicyPurpose {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_version_id", nullable = false)
    private PolicyVersion policyVersion;

    /** 跨版本稳定的用途编码，例如 marketing_email */
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "legal_basis", nullable = false, length = 32)
    private LegalBasis legalBasis = LegalBasis.CONSENT;

    /**
     * OPT_IN：默认不允许，必须取得同意；
     * OPT_OUT：默认允许（如履行合同所必需的处理），用户可拒绝/撤回。
     */
    @Column(name = "consent_model", nullable = false, length = 16)
    private String consentModel = "OPT_IN";

    @Column(name = "sensitive_data", nullable = false)
    private boolean sensitiveData;

    /** 留存期（月），null 表示未设置 */
    @Column(name = "retention_months")
    private Integer retentionMonths;

    /** 可能接收该类数据的第三方（用于凭证展示与告知） */
    @Column(name = "third_parties", length = 1000)
    private String thirdParties;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public boolean optIn() {
        return !"OPT_OUT".equalsIgnoreCase(consentModel);
    }
}
