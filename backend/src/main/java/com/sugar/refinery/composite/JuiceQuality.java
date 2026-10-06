package com.sugar.refinery.composite;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 品质三指标值对象：糖度（Brix）/ 纯度 / 纤维分。
 *
 * <p>本仓的后端路线是 Hibernate 6 的 {@code @CompositeType}：它不是
 * {@code @Embeddable} 也不是实体，由 {@link JuiceQualityCompositeType}
 * 负责把三指标拆成 brix / purity / fiber 三列、再从三列组装回来，
 * 业务代码里始终当成一个整体用。</p>
 *
 * <p>注：这里没有用 record —— Hibernate 6.5 绑定复合类型时按属性访问找 setter，
 * record 没有 setter 会在启动期报
 * {@code Could not locate setter method for property 'brix'}。
 * 用带 getter/setter 的值对象即可，不可变语义由业务侧保证。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class JuiceQuality {

    /** 糖度 Brix */
    private BigDecimal brix;

    /** 纯度 % */
    private BigDecimal purity;

    /** 纤维分 % */
    private BigDecimal fiber;

    /** 三指标都达标（糖度 >= 11、纤维 <= 15）才算合格 */
    public boolean acceptable() {
        return brix != null && brix.compareTo(new BigDecimal("11.0")) >= 0
                && fiber != null && fiber.compareTo(new BigDecimal("15.0")) <= 0;
    }

    /** 简并度：纯度与糖度之差 */
    public BigDecimal impurityGap() {
        if (brix == null || purity == null) {
            return BigDecimal.ZERO;
        }
        return brix.subtract(purity);
    }
}
