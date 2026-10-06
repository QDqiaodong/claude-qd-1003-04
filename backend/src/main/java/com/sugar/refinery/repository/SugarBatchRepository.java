package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sugar.refinery.entity.SugarBatch;

import jakarta.persistence.LockModeType;

public interface SugarBatchRepository extends JpaRepository<SugarBatch, Long> {

    Optional<SugarBatch> findByBatchNo(String batchNo);

    List<SugarBatch> findByStatus(String status);

    List<SugarBatch> findByGrade(String grade);

    List<SugarBatch> findByWarehouse(String warehouse);

    /** 复检提交先锁批次行，串行化同批新版本生成与状态校验 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from SugarBatch b where b.id = :id")
    Optional<SugarBatch> lockById(@Param("id") Long id);
}
