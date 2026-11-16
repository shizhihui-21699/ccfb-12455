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

@Entity
@Table(name = "policy",
        uniqueConstraints = @UniqueConstraint(name = "uk_policy_code", columnNames = "code"),
        indexes = @Index(name = "idx_policy_name", columnList = "name"))
@Getter
@Setter
@NoArgsConstructor
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 稳定编码，业务系统与接口使用它引用政策 */
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(length = 2000)
    private String description;

    /** 数据控制者（我方主体名称），出现在凭证上 */
    @Column(name = "controller_name", length = 256)
    private String controllerName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
