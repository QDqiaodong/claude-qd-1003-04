package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sugar.refinery.entity.PanWash;

import jakarta.persistence.LockModeType;

public interface PanWashRepository extends JpaRepository<PanWash, Long> {

    Optional<PanWash> findByWashNo(String washNo);

    List<PanWash> findByStatusOrderByIdDesc(String status);

    List<PanWash> findAllByOrderByIdDesc();

    List<PanWash> findByPanIdOrderByIdDesc(Long panId);

    /** 该罐所有未完成（待执行 / 清洗中）的安排；正常至多一笔 */
    @Query("select w from PanWash w where w.panId = :panId and w.status in ('待执行', '清洗中')")
    List<PanWash> findActiveByPanId(@Param("panId") Long panId);

    /** 行锁（SELECT ... FOR UPDATE）：开始 / 完成 / 取消 / 一键清洗时串行化同一安排的并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from PanWash w where w.id = :id")
    Optional<PanWash> lockById(@Param("id") Long id);
}
