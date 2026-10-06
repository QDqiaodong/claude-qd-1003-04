package com.sugar.refinery.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 投料批（投料交接单）。
 *
 * <p>值班员把「已验收」磅单拆成一笔或多笔投料批，分配给「运行」中的压榨机组。
 * 每笔批保留来源磅单、目标机组、计划投料量与交接状态；提交交接的同一事务里
 * 扣减磅单可投余量并增加机组占用，撤回 / 少投完成时再同步归还两侧。</p>
 *
 * <p>状态机：待投料 → 投料中 → 已完成；待投料可撤回，进入投料中后不可撤回、
 * 不可改换机组。</p>
 */
@Entity
@Table(name = "feed_batch")
@Getter
@Setter
public class FeedBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 投料批号，唯一 */
    @Column(name = "batch_no", nullable = false, unique = true, length = 32)
    private String batchNo;

    /** 幂等键：值班员一次交接请求只落一笔（重复点击 / 超时重试共用同一键） */
    @Column(name = "request_id", nullable = false, unique = true, length = 36)
    private String requestId;

    /** 来源验收磅单 */
    @Column(name = "intake_id", nullable = false)
    private Long intakeId;

    /** 磅单号快照 */
    @Column(name = "ticket_no", nullable = false, length = 32)
    private String ticketNo;

    /** 目标压榨机组 */
    @Column(name = "station_id", nullable = false)
    private Long stationId;

    /** 机组名快照 */
    @Column(name = "station_name", nullable = false, length = 64)
    private String stationName;

    /** 计划投料量（千克） */
    @Column(name = "planned_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal plannedKg;

    /** 实际投料量（千克），完成后回填 */
    @Column(name = "actual_kg", precision = 12, scale = 2)
    private BigDecimal actualKg;

    /** 待投料 / 投料中 / 已完成 / 已撤回 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** 计划投料量折合吨数，派生字段 */
    @Transient
    public BigDecimal getPlannedTon() {
        return plannedKg == null ? BigDecimal.ZERO
                : plannedKg.divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
    }
}
