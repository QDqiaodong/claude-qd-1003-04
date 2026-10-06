package com.sugar.refinery.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.hibernate.annotations.CompositeType;

import com.sugar.refinery.composite.JuiceQuality;
import com.sugar.refinery.composite.JuiceQualityCompositeType;

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
 * 甘蔗进厂验收（磅单）。
 *
 * <p>榨季里蔗农送蔗过磅，扣杂后按品质三指标定结论：
 * 糖度不足 {@code MIN_CANE_BRIX} 拒收，纤维分超 {@code MAX_FIBER} 扣杂，否则合格。</p>
 *
 * <p>品质三指标（糖度 / 纯度 / 纤维分）在 Java 侧是一个 record
 * {@link JuiceQuality}，经 {@code @CompositeType} 摊平成 brix / purity / fiber 三列。</p>
 */
@Entity
@Table(name = "cane_intake")
@Getter
@Setter
public class CaneIntake {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 磅单号，唯一 */
    @Column(name = "ticket_no", nullable = false, unique = true, length = 32)
    private String ticketNo;

    @Column(name = "supplier", nullable = false, length = 64)
    private String supplier;

    @Column(name = "truck_no", nullable = false, length = 24)
    private String truckNo;

    /** 毛重（千克） */
    @Column(name = "gross_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossKg;

    /** 皮重（千克） */
    @Column(name = "tare_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal tareKg;

    /** 净重（千克）= 毛重 - 皮重 - 扣杂 */
    @Column(name = "net_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal netKg;

    /** 扣杂（千克） */
    @Column(name = "deduction_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal deductionKg;

    /** 已交接投料的净重（千克），由投料交接闭环在同一事务里维护 */
    @Column(name = "allocated_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal allocatedKg = BigDecimal.ZERO;

    // ------------------------------------------------------------------
    // 本仓后端路线：record 值对象 ↔ brix / purity / fiber 三列
    // ------------------------------------------------------------------
    @CompositeType(JuiceQualityCompositeType.class)
    private JuiceQuality quality;

    /** 合格 / 扣杂 / 拒收 */
    @Column(name = "result", nullable = false, length = 16)
    private String result;

    /** 待验 / 已验收 / 已拒收 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "intake_at")
    private LocalDateTime intakeAt;

    /** 扣杂率（%），派生字段 */
    @Transient
    public Double getDeductionRate() {
        if (grossKg == null || tareKg == null || deductionKg == null) {
            return 0.0;
        }
        BigDecimal base = grossKg.subtract(tareKg);
        if (base.signum() <= 0) {
            return 0.0;
        }
        return deductionKg.multiply(BigDecimal.valueOf(100))
                .divide(base, 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 折合吨数，派生字段 */
    @Transient
    public BigDecimal getNetTon() {
        return netKg == null ? BigDecimal.ZERO
                : netKg.divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
    }

    /** 可投余量（千克）= 净重 - 已交接，派生字段 */
    @Transient
    public BigDecimal getAvailableKg() {
        if (netKg == null) {
            return BigDecimal.ZERO;
        }
        return netKg.subtract(allocatedKg == null ? BigDecimal.ZERO : allocatedKg);
    }
}
