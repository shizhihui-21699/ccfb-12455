package com.consentledger.repo;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.consentledger.domain.ConsentEvent;

public interface ConsentEventRepository extends JpaRepository<ConsentEvent, Long> {

    /** 按写入顺序（id）返回，与哈希链定义一致；occurredAt 作为业务时间单独携带。 */
    List<ConsentEvent> findBySubjectIdOrderByIdAsc(Long subjectId);

    Page<ConsentEvent> findBySubjectIdOrderByOccurredAtDescIdDesc(Long subjectId, Pageable pageable);

    long countBySubjectId(Long subjectId);

    @Query("select e from ConsentEvent e order by e.id desc")
    List<ConsentEvent> findRecent(Pageable pageable);
}
