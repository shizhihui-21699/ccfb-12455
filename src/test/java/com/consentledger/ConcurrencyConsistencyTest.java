package com.consentledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * 并发一致性测试：同一主体的并发写入不能产生分叉或多条并行链头。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConcurrencyConsistencyTest {

    private static final String API_KEY = "test-key-0123456789abcdef";

    @Autowired
    private TestRestTemplate rest;

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-API-Key", API_KEY);
        return h;
    }

    @Test
    void concurrentWritesOnSameSubjectKeepSingleChain() throws Exception {
        String subject = "CONC-USER-1";
        int threads = 8;
        int perThread = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            tasks.add(() -> {
                int ok = 0;
                for (int i = 0; i < perThread; i++) {
                    String action = (threadIndex + i) % 2 == 0 ? "GRANT" : "WITHDRAW";
                    String body = """
                            {"subjectId":"%s","changes":[
                              {"purposeCode":"analytics_improvement","action":"%s"}]}
                            """.formatted(subject, action);
                    var response = rest.exchange("/api/v1/consents", HttpMethod.POST,
                            new HttpEntity<>(body, headers()),
                            new ParameterizedTypeReference<Map<String, Object>>() {
                            });
                    if (response.getStatusCode().is2xxSuccessful()) {
                        ok++;
                    }
                }
                return ok;
            });
        }
        List<Future<Integer>> results = pool.invokeAll(tasks);
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        int successes = 0;
        for (Future<Integer> f : results) {
            successes += f.get();
        }

        var receipt = rest.exchange("/api/v1/subjects/" + subject + "/receipt", HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) receipt.getBody().get("document");
        @SuppressWarnings("unchecked")
        Map<String, Object> verification = (Map<String, Object>) doc.get("verification");
        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) doc.get("events");

        assertThat(verification.get("chainValid")).isEqualTo(true);
        assertThat(verification.get("headMatchesLatestEvent")).isEqualTo(true);
        // 撤回在已撤回状态下是幂等的，因此事件数 ≤ 成功写入数，但链必须完整
        assertThat(events.size()).isBetween(1, successes);
        @SuppressWarnings("unchecked")
        Map<String, Object> chain = (Map<String, Object>) doc.get("chain");
        assertThat(((Number) chain.get("eventCount")).longValue()).isEqualTo(events.size());
    }
}
