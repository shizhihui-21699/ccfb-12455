package com.consentledger.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.consentledger.domain.ConsentSubject;

public interface ConsentSubjectRepository extends JpaRepository<ConsentSubject, Long> {

    Optional<ConsentSubject> findByExternalId(String externalId);

    boolean existsByExternalId(String externalId);
}
