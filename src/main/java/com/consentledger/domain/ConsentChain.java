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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 每个主体一条哈希链头。写入时对它加行锁（SELECT ... FOR UPDATE），
 * 保证同一主体的并发同意/撤回不会产生分叉或重复的链头。
 * 单实例、多实例部署语义一致。
 */
@Entity
@Table(name = "consent_chain",
        uniqueConstraints = @UniqueConstraint(name = "uk_chain_subject", columnNames = "subject_id"),
        indexes = @Index(name = "idx_chain_subject", columnList = "subject_id"))
@Getter
@Setter
@NoArgsConstructor
public class ConsentChain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "last_event_id", nullable = false)
    private Long lastEventId;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    @Column(name = "event_count", nullable = false)
    private long eventCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;
}
