package com.sugar.refinery.entity;

import java.math.BigDecimal;
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
 * 成品糖批次。
 *
 * <p>分蜜干燥后按批打包入库，纯度达到 {@code MIN_WHITE_PURITY} 才能判白砂糖，
 * 否则只能判赤砂糖。状态：待复检 → 已入库 → 已发运；复检通过才允许入库和发运。</p>
 *
 * <p>品质同样用 record {@link JuiceQuality} + {@code @CompositeType} 承载。</p>
 */
@Entity
@Table(name = "sugar_batch")
@Getter
@Setter
public class SugarBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 批号，唯一 */
    @Column(name = "batch_no", nullable = false, unique = true, length = 32)
    private String batchNo;

    /** 白砂糖 / 赤砂糖 / 绵白糖 / 冰糖 */
    @Column(name = "grade", nullable = false, length = 16)
    private String grade;

    /** 数量（千克） */
    @Column(name = "quantity_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantityKg;

    @CompositeType(JuiceQualityCompositeType.class)
    private JuiceQuality quality;

    /** 糖仓 */
    @Column(name = "warehouse", nullable = false, length = 24)
    private String warehouse;

    /** 待复检 / 已入库 / 已发运 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 当前有效复检版本；0 表示尚未复检 */
    @Column(name = "current_version", nullable = false)
    private Integer currentVersion = 0;

    @Column(name = "produced_at")
    private LocalDateTime producedAt;

    /** 折合吨数，派生字段 */
    @Transient
    public BigDecimal getQuantityTon() {
        return quantityKg == null ? BigDecimal.ZERO
                : quantityKg.divide(BigDecimal.valueOf(1000), 2, java.math.RoundingMode.HALF_UP);
    }

    /** 糖度，派生字段：直接把 record 组件透给前端 */
    @Transient
    public BigDecimal getBrix() {
        return quality == null ? null : quality.getBrix();
    }

    /** 纯度，派生字段 */
    @Transient
    public BigDecimal getPurity() {
        return quality == null ? null : quality.getPurity();
    }

    /** 当前版本复检记录，由服务层按 currentVersion 回填，不入库 */
    @Transient
    @Getter
    @Setter
    private SugarQualityInspection currentInspection;

    /** 当前复检状态：待复检 / 通过 / 不通过 */
    @Transient
    public String getInspectionStatus() {
        if (currentInspection == null) {
            return "待复检";
        }
        return currentInspection.getConclusion();
    }

    /** 当前复检结论；尚未复检时返回待复检 */
    @Transient
    public String getInspectionConclusion() {
        return getInspectionStatus();
    }

    /** 最近一次复检时间 */
    @Transient
    public LocalDateTime getLastInspectedAt() {
        return currentInspection == null ? null : currentInspection.getInspectedAt();
    }

    /** 当前版本不通过的原因 */
    @Transient
    public String getRejectReason() {
        return currentInspection == null ? null : currentInspection.getRejectReason();
    }

    /** 只有已入库且当前复检版本通过才具备发运资格 */
    @Transient
    public boolean isShipmentEligible() {
        return "已入库".equals(status)
                && currentInspection != null
                && "通过".equals(currentInspection.getConclusion());
    }

    /** 复检通过且尚未入库；可执行入库动作 */
    @Transient
    public boolean isStoreEligible() {
        return "待复检".equals(status)
                && currentInspection != null
                && "通过".equals(currentInspection.getConclusion());
    }
}
