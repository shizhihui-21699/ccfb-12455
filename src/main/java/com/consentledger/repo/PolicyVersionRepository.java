package com.consentledger.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.consentledger.domain.PolicyVersion;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, Long> {

    @EntityGraph(attributePaths = {"purposes", "policy"})
    Optional<PolicyVersion> findWithPurposesById(Long id);

    /** 管理端版本列表：按生效时间倒序，携带用途与政策。 */
    @EntityGraph(attributePaths = {"purposes", "policy"})
    List<PolicyVersion> findByPolicyIdOrderByEffectiveFromDesc(Long policyId);

    /**
     * 全部当前生效版本（状态 ACTIVE 且已到生效时间）。
     * 决策目录的唯一数据来源。
     */
    @EntityGraph(attributePaths = {"purposes", "policy"})
    @Query("""
            select distinct v from PolicyVersion v
            where v.status = com.consentledger.domain.PolicyVersionStatus.ACTIVE
              and v.effectiveFrom <= :now
            order by v.effectiveFrom asc, v.id asc
            """)
    List<PolicyVersion> findAllActiveEffective(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PolicyVersion v set v.status = com.consentledger.domain.PolicyVersionStatus.SUPERSEDED
            where v.policy.id = :policyId
              and v.status = com.consentledger.domain.PolicyVersionStatus.ACTIVE
              and v.id <> :keepId
            """)
    int supersedeOthers(@Param("policyId") Long policyId, @Param("keepId") Long keepId);

    boolean existsByPolicyIdAndVersionLabel(Long policyId, String versionLabel);
}
