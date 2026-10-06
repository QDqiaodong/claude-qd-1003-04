package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sugar.refinery.entity.CaneIntake;

import jakarta.persistence.LockModeType;

public interface CaneIntakeRepository extends JpaRepository<CaneIntake, Long> {

    Optional<CaneIntake> findByTicketNo(String ticketNo);

    List<CaneIntake> findByStatus(String status);

    List<CaneIntake> findByResult(String result);

    List<CaneIntake> findBySupplier(String supplier);

    /** 行锁（SELECT ... FOR UPDATE）：投料交接扣减余量时串行化同一磅单的并发分配 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CaneIntake c where c.id = :id")
    Optional<CaneIntake> lockById(@Param("id") Long id);
}
