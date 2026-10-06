package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sugar.refinery.entity.PressStation;

import jakarta.persistence.LockModeType;

public interface PressStationRepository extends JpaRepository<PressStation, Long> {

    Optional<PressStation> findByStationCode(String stationCode);

    List<PressStation> findByStatus(String status);

    /** 行锁（SELECT ... FOR UPDATE）：投料交接占用能力时串行化同一机组的并发分配 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PressStation p where p.id = :id")
    Optional<PressStation> lockById(@Param("id") Long id);
}
