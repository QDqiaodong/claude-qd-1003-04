package com.sugar.refinery.entity;

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
 * 洗罐安排（蒸发罐 / 结晶罐洗罐排程单）。
 *
 * <p>值班员按罐型、当前状态和上次洗罐时间查看待洗清单，为到期罐安排洗罐窗口。
 * 落单时快照罐号 / 罐名 / 罐型，罐型此后不可改换。同一罐同一时刻只允许存在一笔
 * 未完成（待执行 / 清洗中）的安排（数据库生成列 + 唯一索引兜底）。</p>
 *
 * <p>状态机：待执行 → 清洗中 → 已完成。待执行可取消；进入清洗中后不能重复开始、
 * 不能改换罐型。完成时在同一事务里写完成时间（同时回写罐体 lastWashAt）并恢复
 * 该罐的运行资格（转回「运行」）。任一步失败整体回滚，罐体仍停在可安全重试的状态。</p>
 *
 * <p>{@code failedReason} / {@code failedAt} 只记录最近一次执行失败的信息，不改变状态机，
 * 刷新页面后值班员据此判断失败罐是否可以重试；成功推进或取消时清空。</p>
 */
@Entity
@Table(name = "pan_wash")
@Getter
@Setter
public class PanWash {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 排程单号，唯一 */
    @Column(name = "wash_no", nullable = false, unique = true, length = 32)
    private String washNo;

    /** 目标罐 */
    @Column(name = "pan_id", nullable = false)
    private Long panId;

    /** 罐号快照 */
    @Column(name = "pan_code", nullable = false, length = 24)
    private String panCode;

    /** 罐名快照 */
    @Column(name = "pan_name", nullable = false, length = 64)
    private String panName;

    /** 罐型快照（蒸发罐 / 结晶罐），进入清洗中后不可改换 */
    @Column(name = "pan_type", nullable = false, length = 16)
    private String panType;

    /** 洗罐窗口（计划开始时间） */
    @Column(name = "window_at", nullable = false)
    private LocalDateTime windowAt;

    /** 待执行 / 清洗中 / 已完成 / 已取消 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 开始清洗时间（待执行 → 清洗中） */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** 完成时间（清洗中 → 已完成）；与 evap_pan.last_wash_at 在同一事务写入 */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** 最近一次执行失败的原因（审计字段，不参与状态机） */
    @Column(name = "failed_reason", length = 255)
    private String failedReason;

    /** 最近一次执行失败的时间 */
    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    /** 是否未完成安排（待执行 / 清洗中），派生字段 */
    @Transient
    public boolean isActive() {
        return com.sugar.refinery.common.Dict.isActiveWash(status);
    }

    /**
     * 失败后是否可安全重试，派生字段：
     * 待执行可重新开始 / 一键清洗，清洗中可重新完成；已完成 / 已取消不可重试。
     */
    @Transient
    public boolean isRetryable() {
        return failedReason != null && ("待执行".equals(status) || "清洗中".equals(status));
    }
}
