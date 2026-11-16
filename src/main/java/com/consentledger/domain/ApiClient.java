package com.consentledger.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 接入的业务系统。密钥只保存 SHA-256 哈希与可展示前缀。
 */
@Entity
@Table(name = "api_client",
        uniqueConstraints = @UniqueConstraint(name = "uk_api_client_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
public class ApiClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 密钥哈希（SHA-256 hex） */
    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    /** 密钥前 8 位，便于管理员辨认 */
    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    /** 允许查询的用途，逗号分隔；* 表示全部 */
    @Column(nullable = false, length = 1000)
    private String purposes = "*";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;
}
