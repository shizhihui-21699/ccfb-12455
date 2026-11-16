package com.consentledger.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.consentledger.domain.AdminAuditLog;
import com.consentledger.domain.ConsentChain;
import com.consentledger.domain.ConsentEvent;
import com.consentledger.domain.ConsentState;
import com.consentledger.domain.ConsentStatus;
import com.consentledger.domain.ConsentSubject;
import com.consentledger.domain.LegalBasis;
import com.consentledger.domain.Policy;
import com.consentledger.domain.PolicyPurpose;
import com.consentledger.domain.PolicyVersion;
import com.consentledger.domain.PolicyVersionStatus;
import com.consentledger.repo.AdminAuditLogRepository;
import com.consentledger.repo.ConsentEventRepository;
import com.consentledger.repo.ConsentStateRepository;
import com.consentledger.repo.ConsentSubjectRepository;
import com.consentledger.repo.PolicyRepository;
import com.consentledger.service.ConsentService;
import com.consentledger.service.DecisionService;
import com.consentledger.service.PolicyCatalog;
import com.consentledger.service.PolicyService;
import com.consentledger.service.ReceiptService;

/**
 * 管理后台（Thymeleaf 服务端渲染）。所有写操作均要求 CSRF token。
 */
@Controller
public class AdminController {

    private final PolicyRepository policyRepository;
    private final PolicyService policyService;
    private final ConsentSubjectRepository subjectRepository;
    private final ConsentStateRepository stateRepository;
    private final ConsentEventRepository eventRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final ConsentService consentService;
    private final DecisionService decisionService;
    private final PolicyCatalog policyCatalog;
    private final ReceiptService receiptService;
    private final com.consentledger.service.ConsentAuditService auditService;

    public AdminController(PolicyRepository policyRepository, PolicyService policyService,
                           ConsentSubjectRepository subjectRepository,
                           ConsentStateRepository stateRepository,
                           ConsentEventRepository eventRepository,
                           AdminAuditLogRepository auditLogRepository,
                           ConsentService consentService, DecisionService decisionService,
                           PolicyCatalog policyCatalog, ReceiptService receiptService,
                           com.consentledger.service.ConsentAuditService auditService) {
        this.policyRepository = policyRepository;
        this.policyService = policyService;
        this.subjectRepository = subjectRepository;
        this.stateRepository = stateRepository;
        this.eventRepository = eventRepository;
        this.auditLogRepository = auditLogRepository;
        this.consentService = consentService;
        this.decisionService = decisionService;
        this.policyCatalog = policyCatalog;
        this.receiptService = receiptService;
        this.auditService = auditService;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/admin";
    }

    @GetMapping("/admin")
    public String dashboard(Model model) {
        PolicyCatalog.Snapshot snapshot = policyCatalog.current();
        long granted = stateRepository.countByStatus(ConsentStatus.GRANTED);
        long withdrawn = stateRepository.countByStatus(ConsentStatus.WITHDRAWN);
        long subjects = subjectRepository.count();
        long events = eventRepository.count();

        List<Map<String, Object>> purposeStats = new ArrayList<>();
        Map<String, long[]> byPurpose = new LinkedHashMap<>();
        for (Object[] row : stateRepository.aggregateByPurpose()) {
            String code = (String) row[0];
            ConsentStatus status = (ConsentStatus) row[1];
            long count = ((Number) row[2]).longValue();
            long[] pair = byPurpose.computeIfAbsent(code, k -> new long[2]);
            if (status == ConsentStatus.GRANTED) {
                pair[0] = count;
            } else {
                pair[1] = count;
            }
        }
        for (PolicyCatalog.PurposeView view : snapshot.orderedPurposes()) {
            long[] pair = byPurpose.getOrDefault(view.code(), new long[2]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", view.code());
            item.put("name", view.name());
            item.put("policyName", view.policyName());
            item.put("policyVersion", view.policyVersionLabel());
            item.put("legalBasis", view.legalBasis().name());
            item.put("sensitive", view.sensitiveData());
            item.put("granted", pair[0]);
            item.put("withdrawn", pair[1]);
            item.put("total", pair[0] + pair[1]);
            item.put("grantRate", pair[0] + pair[1] == 0 ? 0
                    : Math.round(pair[0] * 1000.0 / (pair[0] + pair[1])) / 10.0);
            purposeStats.add(item);
        }

        model.addAttribute("activePage", "dashboard");
        model.addAttribute("snapshot", snapshot);
        model.addAttribute("activeVersions", snapshot.versions().size());
        model.addAttribute("subjects", subjects);
        model.addAttribute("events", events);
        model.addAttribute("granted", granted);
        model.addAttribute("withdrawn", withdrawn);
        model.addAttribute("purposeStats", purposeStats);
        model.addAttribute("cacheStats", decisionService.cacheStats());
        model.addAttribute("recentEvents", recentEvents(12));
        model.addAttribute("now", Instant.now());
        return "dashboard";
    }

    @GetMapping("/admin/policies")
    public String policies(Model model) {
        List<Policy> policies = policyRepository.findAllOrdered();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Policy p : policies) {
            List<PolicyVersion> versions = policyService.versionsOf(p.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("policy", p);
            row.put("versions", versions);
            row.put("active", versions.stream()
                    .filter(v -> v.getStatus() == PolicyVersionStatus.ACTIVE).findFirst().orElse(null));
            rows.add(row);
        }
        model.addAttribute("activePage", "policies");
        model.addAttribute("rows", rows);
        return "policies";
    }

    @GetMapping("/admin/policies/view/{versionId}")
    public String versionDetail(@PathVariable Long versionId, Model model) {
        PolicyVersion version = policyService.requireVersion(versionId);
        model.addAttribute("activePage", "policies");
        model.addAttribute("version", version);
        model.addAttribute("policy", version.getPolicy());
        model.addAttribute("purposes", version.getPurposes());
        model.addAttribute("versions", policyService.versionsOf(version.getPolicy().getId()));
        model.addAttribute("eventCount", eventRepository.count());
        return "policy-version";
    }

    @GetMapping("/admin/policies/{policyId}/new-version")
    public String newVersionForm(@PathVariable Long policyId,
                                 @RequestParam(required = false) Long from,
                                 Model model) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> ApiException.notFound("POLICY_NOT_FOUND", "政策不存在：" + policyId));
        List<PolicyVersion> versions = policyService.versionsOf(policyId);
        PolicyVersion source = null;
        if (from != null) {
            source = policyService.requireVersion(from);
        } else if (!versions.isEmpty()) {
            source = policyService.requireVersion(versions.get(0).getId());
        }
        model.addAttribute("activePage", "policies");
        model.addAttribute("policy", policy);
        model.addAttribute("source", source);
        model.addAttribute("purposes", source == null ? List.of() : source.getPurposes());
        model.addAttribute("suggestedLabel", nextLabel(versions));
        return "policy-new-version";
    }

    @PostMapping("/admin/policies/{policyId}/versions")
    public String createVersion(@PathVariable Long policyId,
                                @RequestParam String versionLabel,
                                @RequestParam(required = false) String changeNote,
                                @RequestParam(defaultValue = "false") boolean reconsentRequired,
                                @RequestParam(required = false) String documentText,
                                @RequestParam(required = false) String documentUrl,
                                @RequestParam(required = false) List<String> purposeCode,
                                @RequestParam(required = false) List<String> purposeName,
                                @RequestParam(required = false) List<String> purposeDescription,
                                @RequestParam(required = false) List<String> purposeBasis,
                                @RequestParam(required = false) List<String> purposeConsentModel,
                                @RequestParam(required = false) List<Integer> purposeRetention,
                                @RequestParam(required = false) List<String> purposeThirdParties,
                                Authentication auth, RedirectAttributes redirect) {
        List<PolicyService.PurposeDraft> drafts = new ArrayList<>();
        int rows = purposeCode == null ? 0 : purposeCode.size();
        try {
            for (int i = 0; i < rows; i++) {
                String code = trim(purposeCode, i);
                if (code.isEmpty()) {
                    continue;
                }
                String model = trim(purposeConsentModel, i);
                boolean optIn = model.isEmpty() || "OPT_IN".equalsIgnoreCase(model);
                Integer retention = i < safeSize(purposeRetention) ? purposeRetention.get(i) : null;
                String thirdParties = i < safeSize(purposeThirdParties) ? trim(purposeThirdParties, i) : null;
                drafts.add(new PolicyService.PurposeDraft(code, trim(purposeName, i), trim(purposeDescription, i),
                        LegalBasis.valueOf(trim(purposeBasis, i)), optIn, false, retention,
                        thirdParties == null || thirdParties.isEmpty() ? null : thirdParties));
            }
            PolicyVersion created = policyService.createVersion(policyId,
                    new PolicyService.CreateVersionRequest(versionLabel, Instant.now(), changeNote,
                            reconsentRequired, documentText, documentUrl, drafts), auth.getName());
            redirect.addFlashAttribute("message", "草稿 " + created.getVersionLabel() + " 已创建，确认无误后发布生效。");
            return "redirect:/admin/policies/view/" + created.getId();
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/policies/" + policyId + "/new-version";
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "用途参数不合法：" + e.getMessage());
            return "redirect:/admin/policies/" + policyId + "/new-version";
        }
    }

    /** 依据已有版本号推导下一个版本号：v2.0 → v2.1。 */
    private String nextLabel(List<PolicyVersion> versions) {
        if (versions.isEmpty()) {
            return "v1.0";
        }
        String latest = versions.get(0).getVersionLabel();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^v?(\\d+)\\.(\\d+)$").matcher(latest);
        if (m.matches()) {
            return "v" + m.group(1) + "." + (Integer.parseInt(m.group(2)) + 1);
        }
        return latest + "-next";
    }

    @PostMapping("/admin/versions/{versionId}/publish")
    public String publish(@PathVariable Long versionId, Authentication auth, RedirectAttributes redirect) {
        try {
            policyService.publish(versionId, auth.getName());
            redirect.addFlashAttribute("message", "版本已发布并生效。旧版本的同意将按本次变更说明处理。");
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/policies/view/" + versionId;
    }

    @PostMapping("/admin/versions/{versionId}/retire")
    public String retire(@PathVariable Long versionId, Authentication auth, RedirectAttributes redirect) {
        try {
            policyService.retire(versionId, auth.getName());
            redirect.addFlashAttribute("message", "版本已退役，不再参与决策。");
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/policies/view/" + versionId;
    }

    @PostMapping("/admin/versions/{versionId}/clone")
    public String clone(@PathVariable Long versionId, @RequestParam String newLabel,
                        @RequestParam(defaultValue = "false") boolean reconsentRequired,
                        @RequestParam(required = false) String changeNote,
                        Authentication auth, RedirectAttributes redirect) {
        try {
            PolicyVersion draft = policyService.cloneAsDraft(versionId, newLabel, reconsentRequired,
                    changeNote, auth.getName());
            redirect.addFlashAttribute("message", "已复制为草稿 " + draft.getVersionLabel() + "。");
            return "redirect:/admin/policies/view/" + draft.getId();
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/policies/view/" + versionId;
        }
    }

    @GetMapping("/admin/subjects")
    public String subjects(@RequestParam(required = false) String q,
                           @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("activePage", "subjects");
        model.addAttribute("q", q == null ? "" : q);
        if (q != null && !q.isBlank()) {
            subjectRepository.findByExternalId(q.trim()).ifPresentOrElse(
                    s -> model.addAttribute("subject", s),
                    () -> model.addAttribute("error", "未找到主体：" + q));
        }
        Page<AdminAuditLog> audits = auditLogRepository.findAllByOrderByOccurredAtDesc(
                PageRequest.of(0, 5));
        model.addAttribute("audits", audits.getContent());
        return "subjects";
    }

    @GetMapping("/admin/subjects/{externalId}")
    public String subjectDetail(@PathVariable String externalId, Model model) {
        ConsentSubject subject = subjectRepository.findByExternalId(externalId)
                .orElseThrow(() -> ApiException.notFound("SUBJECT_NOT_FOUND", "未找到主体：" + externalId));
        List<ConsentState> states = consentService.statesFor(subject.getId());
        List<ConsentEvent> events = consentService.historyFor(subject.getId());
        ConsentChain chain = consentService.chainFor(subject.getId());
        Map<String, Object> verification = receiptService.verify(events, chain);

        PolicyCatalog.Snapshot catalog = policyCatalog.current();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PolicyCatalog.PurposeView view : catalog.orderedPurposes()) {
            ConsentState state = states.stream()
                    .filter(s -> s.getPurposeCode().equals(view.code())).findFirst().orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("purpose", view);
            row.put("state", state);
            boolean consentBased = view.legalBasis() == com.consentledger.domain.LegalBasis.CONSENT;
            boolean stale = consentBased && state != null && state.isUsable()
                    && !state.getPolicyVersionId().equals(view.policyVersionId());
            row.put("staleVersion", stale);
            row.put("consentBased", consentBased);
            // 非同意基础的用途按合法性基础直接可用，与用户是否勾选无关
            row.put("usable", consentBased
                    ? state != null && state.isUsable() && !(stale && view.reconsentRequired())
                    : true);
            rows.add(row);
        }
        // 已从当前政策中移除、但用户仍留有历史记录的用途：单独列出，避免"消失"
        List<Map<String, Object>> retiredRows = new ArrayList<>();
        for (ConsentState state : states) {
            if (catalog.contains(state.getPurposeCode())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("purpose", null);
            row.put("state", state);
            row.put("retired", true);
            row.put("usable", false);
            retiredRows.add(row);
        }
        long usableCount = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("usable"))).count();
        model.addAttribute("activePage", "subjects");
        model.addAttribute("subject", subject);
        model.addAttribute("rows", rows);
        model.addAttribute("retiredRows", retiredRows);
        model.addAttribute("usableCount", usableCount);
        model.addAttribute("events", reversed(events));
        model.addAttribute("chain", chain);
        model.addAttribute("verification", verification);
        model.addAttribute("eventCount", events.size());
        return "subject-detail";
    }

    @PostMapping("/admin/subjects/{externalId}/consents")
    public String recordOnBehalf(@PathVariable String externalId,
                                 @RequestParam String purposeCode,
                                 @RequestParam String action,
                                 @RequestParam(required = false) String note,
                                 Authentication auth, RedirectAttributes redirect) {
        try {
            ConsentSubject subject = subjectRepository.findByExternalId(externalId)
                    .orElseThrow(() -> ApiException.notFound("SUBJECT_NOT_FOUND", "未找到主体：" + externalId));
            consentService.record(externalId, subject.getDisplayName(), subject.getSourceSystem(),
                    List.of(new ConsentService.ChangeRequest(purposeCode,
                            com.consentledger.domain.ConsentAction.valueOf(action))),
                    new ConsentService.WriteContext("ADMIN", "员工:" + auth.getName(), "管理后台",
                            null, null, "admin-console", Instant.now()));
            redirect.addFlashAttribute("message", "已代客记录 " + ("GRANT".equals(action) ? "同意" : "撤回")
                    + "：" + purposeCode + (note == null || note.isBlank() ? "" : "（备注：" + note + "）"));
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/subjects/" + externalId;
    }

    @GetMapping("/admin/subjects/{externalId}/receipt.json")
    public org.springframework.http.ResponseEntity<Map<String, Object>> downloadReceipt(@PathVariable String externalId,
                                                                                      Authentication auth) {
        var receipt = receiptService.build(externalId, true);
        auditService.record(auth.getName(), "RECEIPT_EXPORTED", "SUBJECT", externalId, null);
        String filename = "consent-receipt-" + externalId + ".json";
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Cache-Control", "no-store")
                .body(receipt.document());
    }

    @GetMapping("/admin/history")
    public String history(@RequestParam(defaultValue = "0") int page, Model model) {
        Pageable pageable = PageRequest.of(Math.max(0, page), 50);
        Page<ConsentEvent> events = eventRepository.findAll(
                org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "occurredAt", "id")));
        model.addAttribute("activePage", "history");
        model.addAttribute("events", events.getContent());
        model.addAttribute("page", page);
        model.addAttribute("totalPages", events.getTotalPages());
        model.addAttribute("totalElements", events.getTotalElements());
        return "history";
    }

    @GetMapping("/admin/audit")
    public String audit(@RequestParam(defaultValue = "0") int page, Model model) {
        Page<AdminAuditLog> logs = auditLogRepository.findAllByOrderByOccurredAtDesc(
                PageRequest.of(Math.max(0, page), 50));
        model.addAttribute("activePage", "audit");
        model.addAttribute("logs", logs.getContent());
        model.addAttribute("page", page);
        model.addAttribute("totalPages", logs.getTotalPages());
        return "audit";
    }

    private List<ConsentEvent> recentEvents(int limit) {
        return eventRepository.findRecent(PageRequest.of(0, limit));
    }

    private List<ConsentEvent> reversed(List<ConsentEvent> events) {
        List<ConsentEvent> copy = new ArrayList<>(events);
        java.util.Collections.reverse(copy);
        return copy;
    }

    private String trim(List<String> values, int index) {
        if (values == null || index >= values.size() || values.get(index) == null) {
            return "";
        }
        return values.get(index).trim();
    }

    private int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
