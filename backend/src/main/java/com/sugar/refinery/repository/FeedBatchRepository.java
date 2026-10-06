package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sugar.refinery.entity.FeedBatch;

import jakarta.persistence.LockModeType;

public interface FeedBatchRepository extends JpaRepository<FeedBatch, Long> {

    Optional<FeedBatch> findByRequestId(String requestId);

    List<FeedBatch> findByStatusOrderByIdDesc(String status);

    List<FeedBatch> findAllByOrderByIdDesc();

    List<FeedBatch> findByIntakeId(Long intakeId);

    List<FeedBatch> findByStationId(Long stationId);

    /** 行锁（SELECT ... FOR UPDATE）：开始 / 撤回 / 完成时串行化同一批的并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FeedBatch f where f.id = :id")
    Optional<FeedBatch> lockById(@Param("id") Long id);
}
