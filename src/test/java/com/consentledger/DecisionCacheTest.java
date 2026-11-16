package com.consentledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.consentledger.config.ConsentProperties;
import com.consentledger.domain.ConsentStatus;
import com.consentledger.repo.CacheRevisionRepository;
import com.consentledger.repo.ConsentStateRepository;
import com.consentledger.service.ConsentService;
import com.consentledger.service.DecisionCache;
import com.consentledger.service.DecisionService;
import com.consentledger.service.Hashing;

/**
 * 缓存一致性单元级验证：
 * 1) 写入推进全局修订号；
 * 2) 修订号前进后旧缓存条目不再被采用；
 * 3) 决策只依赖状态事实 + 政策目录，不缓存结论。
 */
@SpringBootTest
@ActiveProfiles("test")
class DecisionCacheTest {

    @Autowired
    private DecisionCache cache;

    @Autowired
    private CacheRevisionRepository revisionRepository;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private ConsentStateRepository stateRepository;

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void writesAdvanceGlobalRevision() {
        long before = revisionRepository.current();
        consentService.record("CACHE-REV-1", "缓存测试", "test",
                java.util.List.of(new ConsentService.ChangeRequest("marketing_email",
                        com.consentledger.domain.ConsentAction.GRANT)),
                ConsentService.WriteContext.of("API", "user", "test", null, null, null));
        assertThat(revisionRepository.current()).isGreaterThan(before);
    }

    @Test
    void staleCacheEntryIsDiscardedAfterRevisionMoves() {
        String key = "999999\u0000marketing_email";
        cache.putAll(Map.of(key, DecisionCache.StateFacts.noState()));
        assertThat(cache.peek(key)).isPresent();
        // 模拟其它实例写入：直接推进数据库修订号，轮询后本地缓存应被清空
        transactionTemplate.executeWithoutResult(status -> {
            var current = revisionRepository.findSingleton().orElseGet(() -> {
                var created = new com.consentledger.domain.CacheRevision();
                created.setRevision(0);
                return revisionRepository.save(created);
            });
            current.setRevision(current.getRevision() + 1);
            revisionRepository.saveAndFlush(current);
        });
        cache.pollRevision();
        assertThat(cache.peek(key)).isEmpty();
    }

    @Test
    void decisionResolvesCurrentPolicyVersionFromCatalog() {
        String subject = "CACHE-CATALOG-1";
        consentService.record(subject, null, "test",
                java.util.List.of(new ConsentService.ChangeRequest("marketing_email",
                        com.consentledger.domain.ConsentAction.GRANT)),
                ConsentService.WriteContext.of("API", "user", "test", null, null, null));
        DecisionService.Decision decision = decisionService.decide(subject, "marketing_email");
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.policyVersionLabel()).isNotBlank();
    }

    @Test
    void withdrawingImmediatelyMakesDecisionDenied() {
        String subject = "CACHE-WITHDRAW-1";
        consentService.record(subject, null, "test",
                java.util.List.of(new ConsentService.ChangeRequest("marketing_email",
                        com.consentledger.domain.ConsentAction.GRANT)),
                ConsentService.WriteContext.of("API", "user", "test", null, null, null));
        assertThat(decisionService.decide(subject, "marketing_email").allowed()).isTrue();
        consentService.record(subject, null, "test",
                java.util.List.of(new ConsentService.ChangeRequest("marketing_email",
                        com.consentledger.domain.ConsentAction.WITHDRAW)),
                ConsentService.WriteContext.of("API", "user", "test", null, null, null));
        assertThat(decisionService.decide(subject, "marketing_email").allowed()).isFalse();
    }
}
