package com.sugar.refinery.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sugar.refinery.entity.PressControl;

public interface PressControlRepository extends JpaRepository<PressControl, Long> {

    Optional<PressControl> findByRequestId(String requestId);

    /**
     * 条件更新负荷：只有机组当前正是 {@code expectStatus} 时才写入。
     * 行锁之外再在数据库层兜一道：并发下若机组状态已被另一事务改成检修，
     * 这条 UPDATE 影响行数为 0，绝不允许出现「状态已检修、负荷还在涨」。
     */
    @Modifying
    @Query("update PressStation p set p.currentLoadTph = :load "
            + "where p.id = :id and p.status = :expectStatus")
    int updateLoadIfStatus(@Param("id") Long id,
                           @Param("load") BigDecimal load,
                           @Param("expectStatus") String expectStatus);

    /**
     * 条件切换状态：只允许从 {@code fromStatus} 转到 {@code toStatus}。
     * 并发重复点击时，先提交的事务把状态改走，后到者影响行数为 0，
     * 由服务层识别为「状态已被别的操作改变」而不是重复覆盖。
     */
    @Modifying
    @Query("update PressStation p set p.status = :toStatus "
            + "where p.id = :id and p.status = :fromStatus")
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus);

    /**
     * 转入检修的原子写入：仅当状态仍是 {@code fromStatus} 时，
     * 同一条 UPDATE 把状态置为检修、当前负荷归零、记录本次检修时间。
     * 三件事一条语句同生共死，数据库层不可能落下「已检修但负荷还在 / 没有检修时间」的行。
     */
    @Modifying
    @Query("update PressStation p set p.status = '检修', p.currentLoadTph = 0, "
            + "p.lastMaintainAt = :maintainAt where p.id = :id and p.status = :fromStatus")
    int enterMaintenance(@Param("id") Long id,
                         @Param("fromStatus") String fromStatus,
                         @Param("maintainAt") LocalDateTime maintainAt);
}
