package com.consentledger.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.consentledger.domain.ApiClient;

public interface ApiClientRepository extends JpaRepository<ApiClient, Long> {

    Optional<ApiClient> findByKeyHash(String keyHash);

    boolean existsByName(String name);
}
