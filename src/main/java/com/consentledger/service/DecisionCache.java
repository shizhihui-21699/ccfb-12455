package com.consentledger.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.consentledger.config.ConsentProperties;
import com.consentledger.domain.CacheRevision;
import com.consentledger.repo.CacheRevisionRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * 决策缓存与跨实例失效。
 *
 * <h2>新鲜度</h2>
 * 数据库里有一行全局单调递增的 {@code cache_revision}。每个缓存条目记下
 * 加载开始时的修订号；读取时若本地已知的修订号已前进，条目即视为失效。
 * 各实例每 250ms 轮询一次，因此<b>跨实例</b>的撤回生效上界约为
 * 轮询间隔 + 一次决策查询耗时。
 *
 * <h2>本实例写入立即生效</h2>
 * 写入事务提交后，在本实例<b>精确失效</b>受影响的 key。失效与加载通过
 * Caffeine 的 {@code asMap().compute()} 串行化在同一把桶锁上，因此不存在
 * "加载读到旧值、随后写回缓存"的竞态：失效一定排在旧加载之后，把旧条目删掉。
 *
 * <h2>缓存什么</h2>
 * 只缓存 (是否已同意, 同意所依据的政策版本) 这类原始事实；"这个用途现在能否
 * 使用"由 {@link DecisionService} 结合当前政策目录纯函数计算。政策发布新版本后
 * 即便事实不变，结论也会立即跟着变。
 *
 * <h2>防击穿</h2>
 * 同一 key 的加载在桶锁内单飞，热点 key 失效瞬间不会被上千并发打穿到数据库。
 */
@Component
public class DecisionCache {

    private static final Logger log = LoggerFactory.getLogger(DecisionCache.class);

    private final Cache<String, StampedFacts> cache;
    private final CacheRevisionRepository revisionRepository;
    private final ConsentProperties properties;

    /** 本地已知的数据库修订号；-1 表示尚未初始化 */
    private final AtomicLong dbRevision = new AtomicLong(-1);
    private final AtomicLong preciseInvalidations = new AtomicLong();
    private final AtomicLong loads = new AtomicLong();

    public DecisionCache(CacheRevisionRepository revisionRepository, ConsentProperties properties) {
        this.revisionRepository = revisionRepository;
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getDecisionCacheMaxSize())
                .expireAfterWrite(properties.getDecisionCacheTtl())
                .recordStats()
                .build();
    }

    public static String key(long subjectId, String purposeCode) {
        return subjectId + "\u0000" + purposeCode;
    }

    /** 决策路径需要的原始事实，不含任何政策判断。 */
    public record StateFacts(boolean subjectKnown, boolean hasState, boolean granted,
                             Long policyVersionId, String policyVersionLabel,
                             Instant grantedAt, Instant withdrawnAt, Instant lastChangedAt) {

        public static StateFacts unknownSubject() {
            return new StateFacts(false, false, false, null, null, null, null, null);
        }

        public static StateFacts noState() {
            return new StateFacts(true, false, false, null, null, null, null, null);
        }
    }

    private record StampedFacts(long dbRevision, StateFacts facts) {
        boolean freshAt(long currentDbRevision) {
            return dbRevision >= currentDbRevision;
        }
    }

    /**
     * 读缓存；未命中则在桶锁内加载。加载期间若有写入提交，失效会在加载之后
     * 删除条目，下一次读取自然重载——不会把旧数据固化进缓存。
     */
    public StateFacts getOrLoad(String key, java.util.function.Function<String, StateFacts> loader) {
        long revision = currentRevision();
        StampedFacts stamped = cache.asMap().compute(key, (k, existing) -> {
            if (existing != null && existing.freshAt(revision)) {
                return existing;
            }
            loads.incrementAndGet();
            return new StampedFacts(revision, loader.apply(k));
        });
        if (!stamped.freshAt(currentRevision())) {
            // 加载过程中全局修订号前进（其它实例写入并已被本实例轮询到）
            cache.invalidate(key);
            return loader.apply(key);
        }
        return stamped.facts();
    }

    /** 批量场景：把已查到的多个事实一次性放入缓存。 */
    public void putAll(Map<String, StateFacts> facts) {
        if (facts.isEmpty()) {
            return;
        }
        long revision = currentRevision();
        facts.forEach((key, value) -> cache.asMap().put(key, new StampedFacts(revision, value)));
    }

    public Optional<StateFacts> peek(String key) {
        StampedFacts stamped = cache.getIfPresent(key);
        if (stamped == null || !stamped.freshAt(currentRevision())) {
            return Optional.empty();
        }
        return Optional.of(stamped.facts());
    }

    /** 写入事务提交后调用：精确失效本实例的对应键。 */
    public void invalidateLocal(String key) {
        cache.invalidate(key);
        preciseInvalidations.incrementAndGet();
    }

    public void invalidateAllLocal() {
        cache.invalidateAll();
    }

    public long currentRevision() {
        long local = dbRevision.get();
        if (local >= 0) {
            return local;
        }
        synchronized (this) {
            if (dbRevision.get() < 0) {
                dbRevision.set(readRevision());
            }
            return dbRevision.get();
        }
    }

    /** 轮询全局修订号；发现前进则清空本地缓存，使其它实例的写入生效。 */
    @Scheduled(fixedDelayString = "${consent.revision-poll-interval:250ms}")
    public void pollRevision() {
        try {
            long current = revisionRepository.current();
            long previous = dbRevision.get();
            if (current <= previous) {
                return;
            }
            dbRevision.set(current);
            if (previous >= 0) {
                cache.invalidateAll();
                log.debug("Global revision {} -> {}, invalidated decision cache", previous, current);
            }
        } catch (RuntimeException e) {
            log.warn("Unable to poll cache revision: {}", e.getMessage());
        }
    }

    private long readRevision() {
        try {
            return revisionRepository.current();
        } catch (RuntimeException e) {
            log.warn("Unable to read cache revision: {}", e.getMessage());
            return 0L;
        }
    }

    /** 写入事务内调用：全局修订号 +1，使其它实例的缓存失效。 */
    public void bumpRevision() {
        CacheRevision existing = revisionRepository.findSingleton().orElse(null);
        if (existing == null) {
            CacheRevision created = new CacheRevision();
            created.setRevision(1);
            created.setUpdatedAt(Instant.now());
            revisionRepository.saveAndFlush(created);
        } else {
            revisionRepository.increment(CacheRevision.SINGLETON_ID, Instant.now());
        }
        // 本实例的即时生效由提交后的 invalidateLocal 负责；dbRevision 只跟随轮询，
        // 以保证它反映数据库真实值且单调不回退。
    }

    public CacheStats stats() {
        return cache.stats();
    }

    public long estimatedSize() {
        return cache.estimatedSize();
    }

    public long preciseInvalidationCount() {
        return preciseInvalidations.get();
    }

    public long loadCount() {
        return loads.get();
    }

    public Duration ttl() {
        return properties.getDecisionCacheTtl();
    }
}
