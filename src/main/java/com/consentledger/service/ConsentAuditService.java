package com.consentledger.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.consentledger.domain.AdminAuditLog;
import com.consentledger.repo.AdminAuditLogRepository;

@Service
public class ConsentAuditService {

    private final AdminAuditLogRepository repository;

    public ConsentAuditService(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(String actor, String action, String targetType, String targetId, String detail) {
        AdminAuditLog log = new AdminAuditLog();
        log.setActor(actor == null ? "system" : actor);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail);
        repository.save(log);
    }
}
