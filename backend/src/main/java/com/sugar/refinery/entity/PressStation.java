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
 * 压榨机组。
 *
 * <p>甘蔗压榨出蔗汁的第一道主力设备；负荷超过设计处理量即视为超载，
 * 不允许再往上压。状态：运行 ↔ 停机；检修只能从运行/停机进，检修完回停机。</p>
 */
@Entity
@Table(name = "press_station")
@Getter
@Setter
public class PressStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 机组编号，唯一 */
    @Column(name = "station_code", nullable = false, unique = true, length = 24)
    private String stationCode;

    @Column(name = "station_name", nullable = false, length = 64)
    private String stationName;

    /** 设计处理量（吨/小时） */
    @Column(name = "design_capacity_tph", nullable = false, precision = 8, scale = 2)
    private BigDecimal designCapacityTph;

    /** 当前负荷（吨/小时） */
    @Column(name = "current_load_tph", nullable = false, precision = 8, scale = 2)
    private BigDecimal currentLoadTph;

    /**
     * 投料批已占用（千克），由投料交接闭环在同一事务里维护。
     * 交接占用按「设计处理量对应的千克数」累计：occupiedKg 永远不得超过
     * designCapacityTph × 1000，剩余可交接能力 = 设计处理量×1000 - 已占用。
     */
    @Column(name = "occupied_kg", nullable = false, precision = 14, scale = 2)
    private BigDecimal occupiedKg = BigDecimal.ZERO;

    /** 运行 / 停机 / 检修 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_maintain_at")
    private LocalDateTime lastMaintainAt;

    /** 负荷率（%），派生字段 */
    @Transient
    public Double getLoadRate() {
        if (designCapacityTph == null || designCapacityTph.signum() <= 0 || currentLoadTph == null) {
            return 0.0;
        }
        return currentLoadTph.multiply(BigDecimal.valueOf(100))
                .divide(designCapacityTph, 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 是否超载，派生字段 */
    @Transient
    public boolean isOverload() {
        if (designCapacityTph == null || currentLoadTph == null) {
            return false;
        }
        return currentLoadTph.compareTo(designCapacityTph) > 0;
    }

    /** 已占用（吨），派生字段 */
    @Transient
    public BigDecimal getOccupiedTon() {
        return occupiedKg == null ? BigDecimal.ZERO
                : occupiedKg.divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
    }

    /** 剩余可交接能力（千克）= 设计处理量×1000 - 已占用，派生字段 */
    @Transient
    public BigDecimal getRemainingKg() {
        if (designCapacityTph == null) {
            return BigDecimal.ZERO;
        }
        return designCapacityTph.multiply(BigDecimal.valueOf(1000))
                .subtract(occupiedKg == null ? BigDecimal.ZERO : occupiedKg);
    }

    /** 剩余可交接能力（吨），派生字段 */
    @Transient
    public BigDecimal getRemainingTon() {
        return getRemainingKg().divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
    }
}
