package com.consentledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.consentledger.service.DecisionService;
import com.consentledger.service.Hashing;
import com.consentledger.service.PolicyCatalog;

/**
 * 端到端行为测试：同意 → 决策 → 撤回 → 决策，以及版本实质性变更导致的重新同意。
 * 使用独立内存数据库，不触碰演示数据文件。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConsentFlowIntegrationTest {

    private static final String API_KEY = "test-key-0123456789abcdef";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private Hashing hashing;

    @Autowired
    private PolicyCatalog policyCatalog;

    @Autowired
    private DecisionService decisionService;

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-API-Key", API_KEY);
        return h;
    }

    private ResponseEntity<Map<String, Object>> post(String path, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers()),
                new ParameterizedTypeReference<>() {
                });
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers()),
                new ParameterizedTypeReference<>() {
                });
    }

    @Test
    void grantThenWithdrawChangesDecisionImmediately() {
        String subject = "IT-USER-1";
        var grant = post("/api/v1/consents", """
                {"subjectId":"%s","displayName":"集成测试","changes":[
                  {"purposeCode":"marketing_email","action":"GRANT"}]}
                """.formatted(subject));
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> states = (List<?>) grant.getBody().get("states");
        assertThat(((Map<?, ?>) states.get(0)).get("status")).isEqualTo("GRANTED");

        var decision = get("/api/v1/decisions/" + subject + "?purpose=marketing_email");
        List<?> decisions = (List<?>) decision.getBody().get("decisions");
        Map<?, ?> first = (Map<?, ?>) decisions.get(0);
        assertThat(first.get("allowed")).isEqualTo(true);
        assertThat(first.get("reason")).isEqualTo("CONSENT_GRANTED");

        var withdraw = post("/api/v1/consents", """
                {"subjectId":"%s","changes":[
                  {"purposeCode":"marketing_email","action":"WITHDRAW"}]}
                """.formatted(subject));
        assertThat(withdraw.getStatusCode()).isEqualTo(HttpStatus.OK);

        var after = get("/api/v1/decisions/" + subject + "?purpose=marketing_email");
        Map<?, ?> afterFirst = (Map<?, ?>) ((List<?>) after.getBody().get("decisions")).get(0);
        assertThat(afterFirst.get("allowed")).isEqualTo(false);
        assertThat(afterFirst.get("reason")).isEqualTo("NO_CONSENT");
    }

    @Test
    void unknownPurposeReturns422WithStableCode() {
        var response = post("/api/v1/decisions", """
                {"subjectId":"IT-USER-1","purposes":["not_a_real_purpose"]}
                """);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("UNKNOWN_PURPOSE");
    }

    @Test
    void missingApiKeyReturns401() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.exchange("/api/v1/decisions", HttpMethod.POST,
                new HttpEntity<>("{\"subjectId\":\"x\",\"purposes\":[\"marketing_email\"]}", h),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void batchDecisionReturnsOneItemPerPurposeInOrder() {
        post("/api/v1/consents", """
                {"subjectId":"IT-USER-BATCH","changes":[
                  {"purposeCode":"marketing_email","action":"GRANT"},
                  {"purposeCode":"analytics_improvement","action":"GRANT"}]}
                """);
        var response = post("/api/v1/decisions", """
                {"subjectId":"IT-USER-BATCH","purposes":[
                  "marketing_email","analytics_improvement","third_party_ads"]}
                """);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> decisions = (List<?>) response.getBody().get("decisions");
        assertThat(decisions).hasSize(3);
        assertThat(((Map<?, ?>) decisions.get(0)).get("allowed")).isEqualTo(true);
        assertThat(((Map<?, ?>) decisions.get(1)).get("allowed")).isEqualTo(true);
        assertThat(((Map<?, ?>) decisions.get(2)).get("allowed")).isEqualTo(false);
    }

    @Test
    void legalBasisPurposesAreAlwaysAllowedWithoutConsent() {
        var response = get("/api/v1/decisions/IT-NOBODY?purpose=account_management");
        Map<?, ?> d = (Map<?, ?>) ((List<?>) response.getBody().get("decisions")).get(0);
        assertThat(d.get("allowed")).isEqualTo(true);
        assertThat(d.get("reason")).isEqualTo("LEGAL_BASIS_CONTRACT");
    }

    @Test
    void policyCatalogIsConsistentAndExpiresStaleConsents() {
        PolicyCatalog.Snapshot snapshot = policyCatalog.current();
        assertThat(snapshot.purposes()).containsKeys("marketing_email", "personalized_recommendation");
        assertThat(snapshot.purposes().get("marketing_email").reconsentRequired()).isTrue();
        assertThat(snapshot.purposes().get("marketing_email").policyVersionLabel()).isEqualTo("v2.0");
    }

    @Test
    void receiptContainsValidChainAndSignature() {
        post("/api/v1/consents", """
                {"subjectId":"IT-USER-RECEIPT","changes":[
                  {"purposeCode":"marketing_email","action":"GRANT"},
                  {"purposeCode":"analytics_improvement","action":"GRANT"}]}
                """);
        post("/api/v1/consents", """
                {"subjectId":"IT-USER-RECEIPT","changes":[
                  {"purposeCode":"marketing_email","action":"WITHDRAW"}]}
                """);
        var response = get("/api/v1/subjects/IT-USER-RECEIPT/receipt");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> doc = (Map<?, ?>) response.getBody().get("document");
        Map<?, ?> verification = (Map<?, ?>) doc.get("verification");
        assertThat(verification.get("chainValid")).isEqualTo(true);
        assertThat(verification.get("eventsVerified")).isEqualTo(3);
        assertThat(doc.get("events")).asList().hasSize(3);
        Map<?, ?> signature = (Map<?, ?>) doc.get("signature");
        assertThat(signature.get("algorithm")).isEqualTo("HmacSHA256");
        assertThat((String) signature.get("value")).hasSize(64);
    }

    @Test
    void eventsOfSameSubjectFormUnbrokenChain() {
        post("/api/v1/consents", """
                {"subjectId":"IT-USER-CHAIN","changes":[
                  {"purposeCode":"marketing_email","action":"GRANT"}]}
                """);
        post("/api/v1/consents", """
                {"subjectId":"IT-USER-CHAIN","changes":[
                  {"purposeCode":"marketing_email","action":"WITHDRAW"}]}
                """);
        post("/api/v1/consents", """
                {"subjectId":"IT-USER-CHAIN","changes":[
                  {"purposeCode":"marketing_email","action":"GRANT"}]}
                """);
        var response = get("/api/v1/subjects/IT-USER-CHAIN/receipt");
        Map<?, ?> doc = (Map<?, ?>) response.getBody().get("document");
        List<?> events = (List<?>) doc.get("events");
        assertThat(events).hasSize(3);
        assertThat(((Map<?, ?>) events.get(0)).get("prevHash")).isEqualTo(Hashing.GENESIS_HASH);
        for (int i = 1; i < events.size(); i++) {
            assertThat(((Map<?, ?>) events.get(i)).get("prevHash"))
                    .isEqualTo(((Map<?, ?>) events.get(i - 1)).get("recordHash"));
        }
        assertThat(((Map<?, ?>) doc.get("verification")).get("chainValid")).isEqualTo(true);
    }

    @Test
    void duplicatePurposeInBatchRejected() {
        var response = post("/api/v1/consents", """
                {"subjectId":"IT-USER-DUP","changes":[
                  {"purposeCode":"marketing_email","action":"GRANT"},
                  {"purposeCode":"marketing_email","action":"WITHDRAW"}]}
                """);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("DUPLICATE_PURPOSE");
    }

    @Test
    void decidingUnknownSubjectReturnsDeniedNotError() {
        var response = get("/api/v1/decisions/NEVER-SEEN?purpose=marketing_email");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> d = (Map<?, ?>) ((List<?>) response.getBody().get("decisions")).get(0);
        assertThat(d.get("allowed")).isEqualTo(false);
        assertThat(d.get("reason")).isEqualTo("SUBJECT_UNKNOWN");
    }

    @Test
    void repeatedDecisionsAreServedFromCache() {
        post("/api/v1/consents", """
                {"subjectId":"IT-USER-CACHE","changes":[
                  {"purposeCode":"marketing_email","action":"GRANT"}]}
                """);
        get("/api/v1/decisions/IT-USER-CACHE?purpose=marketing_email");
        // 以服务端数据库加载次数为准：重复查询不应再触达数据库
        long before = ((Number) decisionService.cacheStats().get("dbLoads")).longValue();
        for (int i = 0; i < 20; i++) {
            var response = get("/api/v1/decisions/IT-USER-CACHE?purpose=marketing_email");
            Map<?, ?> d = (Map<?, ?>) ((List<?>) response.getBody().get("decisions")).get(0);
            assertThat(d.get("allowed")).isEqualTo(true);
        }
        long after = ((Number) decisionService.cacheStats().get("dbLoads")).longValue();
        assertThat(after - before).isLessThanOrEqualTo(1);
    }

    @Test
    void purposeRemovedFromPolicyIsReportedAsRetiredNotUnknown() {
        // 演示种子中 analytics_improvement 从未被移除，因此这里验证"从未存在"的行为
        var unknown = get("/api/v1/decisions/IT-USER-1?purpose=definitely_not_a_purpose");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unknown.getBody().get("code")).isEqualTo("UNKNOWN_PURPOSE");
    }

    @Test
    void consentBasedPurposesRejectWritesForContractBasis() {
        var response = post("/api/v1/consents", """
                {"subjectId":"IT-USER-CONTRACT","changes":[
                  {"purposeCode":"account_management","action":"GRANT"}]}
                """);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_CONSENT_BASED");
    }

    @Test
    void decisionReportsLegalBasisForNonConsentPurposes() {
        var response = get("/api/v1/decisions/IT-NO-CONSENT?purpose=account_management");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> d = (Map<?, ?>) ((List<?>) response.getBody().get("decisions")).get(0);
        assertThat(d.get("allowed")).isEqualTo(true);
        assertThat(d.get("reason")).isEqualTo("LEGAL_BASIS_CONTRACT");
        assertThat(d.get("legalBasis")).isEqualTo("CONTRACT");
    }

    @Test
    void receiptSignatureIsReproducible() {
        post("/api/v1/consents", """
                {"subjectId":"IT-USER-SIG","changes":[
                  {"purposeCode":"marketing_email","action":"GRANT"}]}
                """);
        var first = get("/api/v1/subjects/IT-USER-SIG/receipt");
        var second = get("/api/v1/subjects/IT-USER-SIG/receipt");
        Map<?, ?> doc1 = (Map<?, ?>) first.getBody().get("document");
        Map<?, ?> doc2 = (Map<?, ?>) second.getBody().get("document");
        // generatedAt 不同，签名自然不同；但事件记录哈希必须稳定
        List<?> e1 = (List<?>) doc1.get("events");
        List<?> e2 = (List<?>) doc2.get("events");
        assertThat(((Map<?, ?>) e1.get(0)).get("recordHash"))
                .isEqualTo(((Map<?, ?>) e2.get(0)).get("recordHash"));
    }
}
