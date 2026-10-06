package com.sugar.refinery.entity;

import java.math.BigDecimal;
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

import com.sugar.refinery.common.Dict;

/**
 * 蒸发罐 / 结晶罐。
 *
 * <p>蔗汁先经蒸发罐浓缩成糖浆，再进结晶罐煮成糖膏。
 * 罐体运行一段时间必须洗罐，洗罐中不允许进新料。
 * 状态：运行 → 洗罐 → 运行；停用需先洗罐。</p>
 *
 * <p>洗罐排程闭环（{@link com.sugar.refinery.entity.PanWash}）会在安排开始时把罐
 * 转为「洗罐」、完成时写回 lastWashAt 并转回「运行」，罐的运行资格随之恢复。</p>
 */
@Entity
@Table(name = "evap_pan")
@Getter
@Setter
public class EvapPan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 罐号，唯一 */
    @Column(name = "pan_code", nullable = false, unique = true, length = 24)
    private String panCode;

    @Column(name = "pan_name", nullable = false, length = 64)
    private String panName;

    /** 蒸发罐 / 结晶罐 */
    @Column(name = "pan_type", nullable = false, length = 16)
    private String panType;

    /** 容积（立方米） */
    @Column(name = "capacity_m3", nullable = false, precision = 8, scale = 2)
    private BigDecimal capacityM3;

    /** 运行 / 洗罐 / 停用 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_wash_at")
    private LocalDateTime lastWashAt;

    /** 距上次洗罐小时数，派生字段；超过 72 小时提示该洗了 */
    @Transient
    public Long getHoursSinceWash() {
        if (lastWashAt == null) {
            return null;
        }
        return java.time.Duration.between(lastWashAt, LocalDateTime.now()).toHours();
    }

    /** 是否该洗罐（需先洗罐），派生字段：距上次洗罐超过 {@link Dict#PAN_WASH_DUE_HOURS} 小时 */
    @Transient
    public boolean isWashDue() {
        Long h = getHoursSinceWash();
        return h != null && h > Dict.PAN_WASH_DUE_HOURS;
    }

    /**
     * 运行资格，派生字段：只有「运行」中且未到洗罐期限才有资格；
     * 停用、洗罐中、超 72 小时未洗均无运行资格。洗罐排程完成写回洗罐时间后自动恢复。
     */
    @Transient
    public boolean isOperable() {
        return "运行".equals(status) && !isWashDue();
    }

    /** 无运行资格的原因，派生字段；有资格时返回 {@code null} */
    @Transient
    public String getInoperableReason() {
        if ("停用".equals(status)) {
            return "罐体停用中";
        }
        if ("洗罐".equals(status)) {
            return "罐体清洗中";
        }
        if (isWashDue()) {
            return "距上次洗罐已 " + getHoursSinceWash() + " 小时，超过 "
                    + Dict.PAN_WASH_DUE_HOURS + " 小时，需先洗罐";
        }
        return null;
    }
}
