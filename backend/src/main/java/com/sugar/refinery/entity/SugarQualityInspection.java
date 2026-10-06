package com.sugar.refinery.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 成品糖质量复检记录。
 *
 * <p>复检按批号 + 版本追加保存：每次重新复检生成新版本，历史记录不覆盖；
 * sugar_batch.current_version 指向当前有效版本。request_id 标识一次提交意图，
 * 重复点击和超时重试只能返回同一条记录。</p>
 */
@Entity
@Table(name = "sugar_quality_inspection", uniqueConstraints = {
        @UniqueConstraint(name = "uq_inspection_request", columnNames = "request_id"),
        @UniqueConstraint(name = "uq_inspection_version", columnNames = {"batch_id", "version"})
})
@Getter
@Setter
public class SugarQualityInspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 批号快照，便于按批号追溯且与批次生命周期一致 */
    @Column(name = "batch_no", nullable = false, length = 32)
    private String batchNo;

    /** 从 1 开始；重新复检追加新版本，不覆盖旧结果 */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 一次提交意图的幂等键 */
    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "inspected_at", nullable = false)
    private LocalDateTime inspectedAt;

    @Column(name = "brix", nullable = false, precision = 5, scale = 2)
    private BigDecimal brix;

    @Column(name = "purity", nullable = false, precision = 5, scale = 2)
    private BigDecimal purity;

    @Column(name = "fiber", nullable = false, precision = 5, scale = 2)
    private BigDecimal fiber;

    /** 通过 / 不通过 */
    @Column(name = "conclusion", nullable = false, length = 16)
    private String conclusion;

    /** 不通过时必填的原因；通过时为空 */
    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
