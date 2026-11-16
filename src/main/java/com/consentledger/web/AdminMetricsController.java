package com.consentledger.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.consentledger.domain.ConsentStatus;
import com.consentledger.repo.ConsentEventRepository;
import com.consentledger.repo.ConsentStateRepository;
import com.consentledger.repo.ConsentSubjectRepository;
import com.consentledger.service.DecisionService;
import com.consentledger.service.PolicyCatalog;

/**
 * 管理端运行指标 JSON（供后台页面轮询与监控采集）。
 */
@RestController
@RequestMapping("/admin/api")
public class AdminMetricsController {

    private final DecisionService decisionService;
    private final PolicyCatalog policyCatalog;
    private final ConsentStateRepository stateRepository;
    private final ConsentEventRepository eventRepository;
    private final ConsentSubjectRepository subjectRepository;

    public AdminMetricsController(DecisionService decisionService, PolicyCatalog policyCatalog,
                                  ConsentStateRepository stateRepository,
                                  ConsentEventRepository eventRepository,
                                  ConsentSubjectRepository subjectRepository) {
        this.decisionService = decisionService;
        this.policyCatalog = policyCatalog;
        this.stateRepository = stateRepository;
        this.eventRepository = eventRepository;
        this.subjectRepository = subjectRepository;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        PolicyCatalog.Snapshot snapshot = policyCatalog.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subjects", subjectRepository.count());
        out.put("events", eventRepository.count());
        out.put("granted", stateRepository.countByStatus(ConsentStatus.GRANTED));
        out.put("withdrawn", stateRepository.countByStatus(ConsentStatus.WITHDRAWN));
        out.put("activeVersions", snapshot.versions().size());
        out.put("catalogRevision", snapshot.dbRevision());
        out.put("purposes", snapshot.orderedPurposes().stream().map(p -> Map.of(
                "code", p.code(),
                "name", p.name(),
                "policyVersion", p.policyVersionLabel(),
                "legalBasis", p.legalBasis().name()))
                .toList());
        out.put("cache", decisionService.cacheStats());
        return out;
    }

    @GetMapping("/purposes")
    public List<Map<String, Object>> purposes() {
        return policyCatalog.current().orderedPurposes().stream().map(p -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", p.code());
            item.put("name", p.name());
            item.put("legalBasis", p.legalBasis().name());
            item.put("policyCode", p.policyCode());
            item.put("policyVersion", p.policyVersionLabel());
            item.put("reconsentRequired", p.reconsentRequired());
            item.put("sensitive", p.sensitiveData());
            return item;
        }).toList();
    }
}
