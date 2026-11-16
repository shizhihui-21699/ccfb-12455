package com.consentledger.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.consentledger.domain.PolicyPurpose;

public interface PolicyPurposeRepository extends JpaRepository<PolicyPurpose, Long> {

    List<PolicyPurpose> findByPolicyVersionIdOrderBySortOrderAscIdAsc(Long policyVersionId);

    /** 历史上出现过的全部用途编码——用于区分"从未定义"与"已从政策中移除"。 */
    @Query("select distinct p.code from PolicyPurpose p")
    List<String> findDistinctCodes();
}
