package com.consentledger.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.consentledger.domain.ConsentState;
import com.consentledger.domain.ConsentStatus;

public interface ConsentStateRepository extends JpaRepository<ConsentState, Long> {

    Optional<ConsentState> findBySubjectIdAndPurposeCode(Long subjectId, String purposeCode);

    List<ConsentState> findBySubjectIdOrderByPurposeCodeAsc(Long subjectId);

    long countByStatus(ConsentStatus status);

    /** 管理端总览：按用途与状态聚合计数。 */
    @Query("select s.purposeCode, s.status, count(s) from ConsentState s group by s.purposeCode, s.status")
    List<Object[]> aggregateByPurpose();
}
