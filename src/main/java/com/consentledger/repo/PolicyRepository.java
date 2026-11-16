package com.consentledger.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.consentledger.domain.Policy;

public interface PolicyRepository extends JpaRepository<Policy, Long> {

    Optional<Policy> findByCode(String code);

    boolean existsByCode(String code);

    @Query("select p from Policy p order by p.name asc")
    List<Policy> findAllOrdered();
}
