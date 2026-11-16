package com.consentledger.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 全局单调递增的修订号。任何写入都会 +1，各实例轮询它来刷新决策缓存。
 * 单节点部署时本实例写入会直接精确失效本地键，无需等待轮询。
 */
@Entity
@Table(name = "cache_revision")
@Getter
@Setter
@NoArgsConstructor
public class CacheRevision {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
