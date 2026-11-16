package com.consentledger.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.consentledger.domain.ConsentChain;
import com.consentledger.domain.ConsentSubject;
import com.consentledger.repo.ConsentChainRepository;
import com.consentledger.repo.ConsentSubjectRepository;
import com.consentledger.web.ApiException;

import jakarta.annotation.PostConstruct;

/**
 * 主体与链头的安全注册。
 *
 * <p>"先查后插"在并发下必然撞唯一索引：同一新用户首次点击同意时，
 * 多个请求同时发现不存在，然后同时插入。这里把插入放进<b>独立事务</b>，
 * 撞索引就重试一次查询——失败事务的回滚不会污染调用方的主事务。
 *
 * <p>链头同理：必须先提交地创建链头行，主事务才能对它加行锁串行化写入。
 */
@Component
public class SubjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(SubjectRegistry.class);

    private final ConsentSubjectRepository subjectRepository;
    private final ConsentChainRepository chainRepository;
    private final TransactionTemplate requiresNew;

    public SubjectRegistry(ConsentSubjectRepository subjectRepository,
                           ConsentChainRepository chainRepository,
                           PlatformTransactionManager transactionManager) {
        this.subjectRepository = subjectRepository;
        this.chainRepository = chainRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @PostConstruct
    void configure() {
        requiresNew.setName("subject-registry");
    }

    public ConsentSubject resolve(String externalId, String displayName, String sourceSystem) {
        if (externalId == null || externalId.isBlank()) {
            throw ApiException.badRequest("INVALID_SUBJECT_ID", "subjectId 不能为空");
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            var existing = subjectRepository.findByExternalId(externalId);
            if (existing.isPresent()) {
                return existing.get();
            }
            try {
                return requiresNew.execute(status -> {
                    ConsentSubject subject = new ConsentSubject();
                    subject.setExternalId(externalId);
                    subject.setDisplayName(displayName);
                    subject.setSourceSystem(sourceSystem);
                    return subjectRepository.saveAndFlush(subject);
                });
            } catch (DataIntegrityViolationException e) {
                log.debug("Subject {} created concurrently, retrying lookup", externalId);
            }
        }
        return subjectRepository.findByExternalId(externalId)
                .orElseThrow(() -> new IllegalStateException("无法创建主体：" + externalId));
    }

    /**
     * 确保链头存在并已提交，随后主事务才能对它加行锁。
     */
    public void ensureChain(long subjectId) {
        if (chainRepository.findBySubjectId(subjectId).isPresent()) {
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                requiresNew.execute(status -> {
                    ConsentChain chain = new ConsentChain();
                    chain.setSubjectId(subjectId);
                    chain.setLastEventId(0L);
                    chain.setLastHash(Hashing.GENESIS_HASH);
                    chain.setEventCount(0L);
                    return chainRepository.saveAndFlush(chain);
                });
                return;
            } catch (DataIntegrityViolationException e) {
                log.debug("Chain for subject {} created concurrently", subjectId);
            }
        }
        if (chainRepository.findBySubjectId(subjectId).isEmpty()) {
            throw new IllegalStateException("无法初始化主体哈希链：" + subjectId);
        }
    }
}
