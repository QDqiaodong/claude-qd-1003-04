package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sugar.refinery.entity.SugarQualityInspection;

public interface SugarQualityInspectionRepository extends JpaRepository<SugarQualityInspection, Long> {

    Optional<SugarQualityInspection> findByRequestId(String requestId);

    Optional<SugarQualityInspection> findByBatchIdAndVersion(Long batchId, Integer version);

    List<SugarQualityInspection> findByBatchIdOrderByVersionAsc(Long batchId);

    List<SugarQualityInspection> findByBatchIdInOrderByVersionAsc(List<Long> batchIds);
}
