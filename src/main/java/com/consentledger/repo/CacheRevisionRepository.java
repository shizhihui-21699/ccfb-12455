package com.consentledger.repo;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.consentledger.domain.CacheRevision;

public interface CacheRevisionRepository extends JpaRepository<CacheRevision, Long> {

    default long current() {
        return findById(CacheRevision.SINGLETON_ID).map(CacheRevision::getRevision).orElse(0L);
    }

    default Optional<CacheRevision> findSingleton() {
        return findById(CacheRevision.SINGLETON_ID);
    }

    /**
     * 原子自增，避免多实例并发时的乐观锁冲突。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CacheRevision c set c.revision = c.revision + 1, c.updatedAt = :now where c.id = :id")
    int increment(@Param("id") Long id, @Param("now") Instant now);
}
