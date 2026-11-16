package com.consentledger.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.consentledger.config.ApiKeyAuthFilter;
import com.consentledger.domain.ApiClient;
import com.consentledger.domain.ConsentEvent;
import com.consentledger.domain.ConsentState;
import com.consentledger.domain.ConsentSubject;
import com.consentledger.repo.ConsentEventRepository;
import com.consentledger.repo.ConsentStateRepository;
import com.consentledger.service.ConsentService;
import com.consentledger.service.DecisionService;
import com.consentledger.service.PolicyCatalog;
import com.consentledger.service.ReceiptService;
import com.consentledger.web.dto.ApiDtos.ConsentEventItem;
import com.consentledger.web.dto.ApiDtos.ConsentStateItem;
import com.consentledger.web.dto.ApiDtos.ConsentWriteRequest;
import com.consentledger.web.dto.ApiDtos.ConsentWriteResponse;
import com.consentledger.web.dto.ApiDtos.DecisionItem;
import com.consentledger.web.dto.ApiDtos.DecisionQuery;
import com.consentledger.web.dto.ApiDtos.DecisionResponse;
import com.consentledger.web.dto.ApiDtos.PolicyVersionView;
import com.consentledger.web.dto.ApiDtos.PurposeCatalogResponse;
import com.consentledger.web.dto.ApiDtos.PurposeView;
import com.consentledger.web.dto.ApiDtos.SubjectStatusResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 面向业务系统的接入 API。
 *
 * <p>鉴权：所有请求带 {@code X-API-Key}。响应不缓存（Cache-Control: no-store），
 * 决策结果由客户端按 {@code cacheTtlSeconds} 自行决定是否短期复用。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "业务接入 API", description = "同意决策、同意记录、主体状态与凭证导出")
public class ConsentApiController {

    private final DecisionService decisionService;
    private final ConsentService consentService;
    private final PolicyCatalog policyCatalog;
    private final ReceiptService receiptService;
    private final ConsentStateRepository stateRepository;
    private final ConsentEventRepository eventRepository;

    public ConsentApiController(DecisionService decisionService,
                                ConsentService consentService,
                                PolicyCatalog policyCatalog,
                                ReceiptService receiptService,
                                ConsentStateRepository stateRepository,
                                ConsentEventRepository eventRepository) {
        this.decisionService = decisionService;
        this.consentService = consentService;
        this.policyCatalog = policyCatalog;
        this.receiptService = receiptService;
        this.stateRepository = stateRepository;
        this.eventRepository = eventRepository;
    }

    @PostMapping("/decisions")
    @Operation(summary = "查询某用户若干用途当前能否使用",
            description = "业务系统使用数据前调用。任一用途被拒绝时 allowed=false，reason 说明原因："
                    + "NO_CONSENT / SUBJECT_UNKNOWN / POLICY_RECONSENT_REQUIRED / LEGAL_BASIS_*。")
    public DecisionResponse decide(@Valid @RequestBody DecisionQuery query,
                                   @RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) ApiClient client,
                                   @RequestHeader(value = "X-Request-Id", required = false) String headerRequestId) {
        for (String purpose : query.purposes()) {
            if (!ApiKeyAuthFilter.mayAccess(client, purpose)) {
                throw ApiException.forbidden("该接入方无权查询用途：" + purpose);
            }
        }
        List<DecisionItem> items = new ArrayList<>();
        for (DecisionService.Decision d : decisionService.decideMany(query.subjectId(), query.purposes())) {
            items.add(toItem(d));
        }
        String requestId = query.requestId() != null ? query.requestId() : headerRequestId;
        return new DecisionResponse(query.subjectId(), Instant.now(), requestId,
                Math.toIntExact(decisionService.cacheTtlSeconds()), items);
    }

    @GetMapping("/decisions/{subjectId}")
    @Operation(summary = "单用途决策（GET，便于前端与网关直接调用）")
    public DecisionResponse decideOne(@PathVariable String subjectId,
                                      @RequestParam String purpose,
                                      @RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) ApiClient client) {
        if (!ApiKeyAuthFilter.mayAccess(client, purpose)) {
            throw ApiException.forbidden("该接入方无权查询用途：" + purpose);
        }
        DecisionService.Decision d = decisionService.decide(subjectId, purpose);
        return new DecisionResponse(subjectId, Instant.now(), null,
                Math.toIntExact(decisionService.cacheTtlSeconds()), List.of(toItem(d)));
    }

    @PostMapping("/consents")
    @Operation(summary = "记录同意或撤回",
            description = "按用途批量写入。每次写入都绑定当前生效的政策版本并追加到哈希链。"
                    + "syncWithdraw=true 时撤回后等待缓存全网失效再返回（用于强一致场景）。")
    public ConsentWriteResponse write(@Valid @RequestBody ConsentWriteRequest request,
                                      @RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) ApiClient client,
                                      HttpServletRequest http) {
        for (var change : request.changes()) {
            if (!ApiKeyAuthFilter.mayAccess(client, change.purposeCode())) {
                throw ApiException.forbidden("该接入方无权写入用途：" + change.purposeCode());
            }
        }
        String channel = request.channel() == null || request.channel().isBlank() ? "API" : request.channel();
        String actor = request.actor() == null || request.actor().isBlank() ? "user" : request.actor();
        ConsentService.WriteResult result = consentService.record(
                request.subjectId(), request.displayName(), request.sourceSystem(),
                request.changes().stream()
                        .map(c -> new ConsentService.ChangeRequest(c.purposeCode(), c.action()))
                        .toList(),
                ConsentService.WriteContext.of(channel, actor, client.getName(), request.requestId(),
                        clientIp(http), http.getHeader("User-Agent")));
        List<ConsentStateItem> states = result.states().stream().map(this::toStateItem).toList();
        List<ConsentEventItem> events = result.events().stream().map(this::toEventItem).toList();
        return new ConsentWriteResponse(result.subjectId(), result.subjectExternalId(), result.batchId(),
                states, events);
    }

    @GetMapping("/subjects/{subjectId}")
    @Operation(summary = "查询用户当前各用途同意状态")
    public SubjectStatusResponse status(@PathVariable String subjectId) {
        ConsentSubject subject = consentService.findSubject(subjectId)
                .orElseThrow(() -> ApiException.notFound("SUBJECT_NOT_FOUND", "未找到该主体：" + subjectId));
        List<ConsentStateItem> states = consentService.statesFor(subject.getId()).stream()
                .map(this::toStateItem).toList();
        var chain = consentService.chainFor(subject.getId());
        return new SubjectStatusResponse(subject.getExternalId(), subject.getDisplayName(),
                subject.getSourceSystem(), subject.getCreatedAt(), states,
                (int) eventRepository.countBySubjectId(subject.getId()),
                chain == null ? null : chain.getLastHash());
    }

    @GetMapping("/subjects/{subjectId}/receipt")
    @Operation(summary = "导出该用户完整同意凭证 JSON",
            description = "含全部事件、政策版本与内容哈希、链校验结果与 HMAC 签名，可直接提交监管。")
    public ReceiptService.Receipt receipt(@PathVariable String subjectId,
                                          @RequestParam(defaultValue = "true") boolean includeEvents) {
        return receiptService.build(subjectId, includeEvents);
    }

    @GetMapping("/purposes")
    @Operation(summary = "当前生效的用途目录（含政策版本与内容哈希）")
    public PurposeCatalogResponse catalog() {
        PolicyCatalog.Snapshot snapshot = policyCatalog.current();
        List<PolicyVersionView> versions = snapshot.versions().stream().map(this::toVersionView).toList();
        // 用途目录是决策判断的输入，接入方可据此缓存 UI 文案
        List<PurposeView> purposes = snapshot.orderedPurposes().stream()
                .map(p -> new PurposeView(p.code(), p.name(), p.description(), p.legalBasis().name(),
                        p.optIn(), p.sensitiveData(), p.retentionMonths(), p.thirdParties(),
                        p.policyCode(), p.policyName(), p.policyVersionId(), p.policyVersionLabel(),
                        p.policyContentHash(), p.effectiveFrom(), p.reconsentRequired()))
                .toList();
        return new PurposeCatalogResponse(Instant.now(), purposes);
    }

    private DecisionItem toItem(DecisionService.Decision d) {
        return new DecisionItem(d.allowed(), d.reason(), d.purposeCode(), d.purposeName(),
                d.legalBasis() == null ? null : d.legalBasis().name(),
                d.policyVersionId(), d.policyVersionLabel(), d.reconsentRequired(), d.cached());
    }

    private ConsentStateItem toStateItem(ConsentState s) {
        return new ConsentStateItem(s.getPurposeCode(), s.getStatus().name(), s.getPolicyVersionId(),
                s.getPolicyVersionLabel(), s.getGrantedAt(), s.getWithdrawnAt());
    }

    private ConsentEventItem toEventItem(ConsentEvent e) {
        return new ConsentEventItem(e.getEventUid(), e.getPurposeCode(), e.getAction().name(),
                e.getResultingStatus().name(), e.getLegalBasis().name(), e.getPolicyVersionId(),
                e.getPolicyVersionLabel(), e.getPolicyContentHash(), e.getOccurredAt(), e.getRecordHash());
    }

    private PolicyVersionView toVersionView(com.consentledger.domain.PolicyVersion v) {
        List<PurposeView> purposes = v.getPurposes().stream()
                .map(p -> new PurposeView(p.getCode(), p.getName(), p.getDescription(), p.getLegalBasis().name(),
                        p.optIn(), p.isSensitiveData(), p.getRetentionMonths(), p.getThirdParties(),
                        v.getPolicy().getCode(), v.getPolicy().getName(), v.getId(), v.getVersionLabel(),
                        v.getContentHash(), v.getEffectiveFrom(), v.isReconsentRequired()))
                .toList();
        return new PolicyVersionView(v.getId(), v.getPolicy().getCode(), v.getPolicy().getName(),
                v.getVersionLabel(), v.getStatus().name(), v.getEffectiveFrom(), v.getPublishedAt(),
                v.getContentHash(), v.getChangeNote(), v.isReconsentRequired(), purposes);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
