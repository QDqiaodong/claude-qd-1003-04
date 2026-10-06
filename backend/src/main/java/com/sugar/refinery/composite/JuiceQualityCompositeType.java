package com.sugar.refinery.composite;

import java.io.Serializable;
import java.math.BigDecimal;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.spi.ValueAccess;
import org.hibernate.usertype.CompositeUserType;

/**
 * {@link JuiceQuality} 的复合类型映射：一个 record ↔ brix / purity / fiber 三列。
 *
 * <p>实体上写作 {@code @CompositeType(JuiceQualityCompositeType.class)}。
 * 本接口在 Hibernate 6.5 里 extends {@code EmbeddableInstantiator}，
 * 属性名与类型由 record 的组件自动推导，不需要再写
 * {@code getPropertyNames()} / {@code getPropertyTypes()}。</p>
 */
public class JuiceQualityCompositeType implements CompositeUserType<JuiceQuality> {

    /**
     * 下标顺序：Hibernate 6.5 绑定复合类型时按属性名排序（brix, fiber, purity），
     * 不是类里字段的声明顺序（brix, purity, fiber）。下标必须按
     * 0=brix / 1=fiber / 2=purity 取，否则纯度和纤维分会读串。
     */
    private static final int IDX_BRIX = 0;
    private static final int IDX_FIBER = 1;
    private static final int IDX_PURITY = 2;

    @Override
    public Object getPropertyValue(JuiceQuality component, int property) {
        return switch (property) {
            case IDX_BRIX -> component.getBrix();
            case IDX_FIBER -> component.getFiber();
            case IDX_PURITY -> component.getPurity();
            default -> throw new IllegalArgumentException("下标越界：" + property);
        };
    }

    @Override
    public JuiceQuality instantiate(ValueAccess valueAccess, SessionFactoryImplementor sessionFactory) {
        BigDecimal brix = valueAccess.getValue(IDX_BRIX, BigDecimal.class);
        BigDecimal fiber = valueAccess.getValue(IDX_FIBER, BigDecimal.class);
        BigDecimal purity = valueAccess.getValue(IDX_PURITY, BigDecimal.class);
        JuiceQuality q = new JuiceQuality();
        q.setBrix(brix);
        q.setPurity(purity);
        q.setFiber(fiber);
        return q;
    }

    /** 被映射的组件类型 */
    @Override
    public Class<?> embeddable() {
        return JuiceQuality.class;
    }

    @Override
    public Class<JuiceQuality> returnedClass() {
        return JuiceQuality.class;
    }

    @Override
    public boolean equals(JuiceQuality x, JuiceQuality y) {
        return x == null ? y == null : x.equals(y);
    }

    @Override
    public int hashCode(JuiceQuality x) {
        return x == null ? 0 : x.hashCode();
    }

    @Override
    public JuiceQuality deepCopy(JuiceQuality value) {
        // record 不可变，直接返回
        return value;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(JuiceQuality value) {
        if (value == null) {
            return null;
        }
        // 顺序与 IDX_* 保持一致：brix, fiber, purity
        return new String[]{
                String.valueOf(value.getBrix()),
                String.valueOf(value.getFiber()),
                String.valueOf(value.getPurity())
        };
    }

    @Override
    public JuiceQuality assemble(Serializable cached, Object owner) {
        if (cached == null) {
            return null;
        }
        String[] p = (String[]) cached;
        JuiceQuality q = new JuiceQuality();
        q.setBrix(new BigDecimal(p[IDX_BRIX]));
        q.setFiber(new BigDecimal(p[IDX_FIBER]));
        q.setPurity(new BigDecimal(p[IDX_PURITY]));
        return q;
    }

    @Override
    public JuiceQuality replace(JuiceQuality detached, JuiceQuality merged, Object owner) {
        return detached;
    }
}
