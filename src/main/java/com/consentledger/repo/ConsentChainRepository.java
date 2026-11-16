package com.consentledger.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.consentledger.domain.ConsentChain;

import jakarta.persistence.LockModeType;

public interface ConsentChainRepository extends JpaRepository<ConsentChain, Long> {

    Optional<ConsentChain> findBySubjectId(Long subjectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConsentChain c where c.subjectId = :subjectId")
    Optional<ConsentChain> lockBySubjectId(@Param("subjectId") Long subjectId);
}
