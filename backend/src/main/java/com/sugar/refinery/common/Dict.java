package com.sugar.refinery.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 制糖厂词典与业务规则参数表：榨季与进厂验收等级、压榨机组与蒸发结晶罐状态、
 * 成品糖品类与状态，以及糖度 / 纯度 / 纤维分各类阈值。
 *
 * <p>规则集中在此，便于出题时被要求调整或扩展。</p>
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 甘蔗进厂 ----------------
    /** 验收结论：合格 / 扣杂 / 拒收 */
    public static final List<String> INTAKE_RESULTS = List.of("合格", "扣杂", "拒收");

    public static final List<String> INTAKE_STATUSES = List.of("待验", "已验收", "已拒收");

    // ---------------- 压榨机组 ----------------
    public static final List<String> PRESS_STATUSES = List.of("运行", "停机", "检修");

    private static final Map<String, List<String>> PRESS_NEXT = Map.of(
            "运行", List.of("停机", "检修"),
            "停机", List.of("运行", "检修"),
            "检修", List.of("停机"));

    // ---------------- 投料交接 ----------------
    /** 投料批状态：待投料 / 投料中 / 已完成 / 已撤回 */
    public static final List<String> FEED_STATUSES = List.of("待投料", "投料中", "已完成", "已撤回");

    /** 待投料可开始投料或撤回；一旦投料中只能完成，不可撤回、不可改机组 */
    private static final Map<String, List<String>> FEED_NEXT = Map.of(
            "待投料", List.of("投料中", "已撤回"),
            "投料中", List.of("已完成"),
            "已完成", List.of(),
            "已撤回", List.of());

    // ---------------- 蒸发 / 结晶罐 ----------------
    /** 罐型：蒸发罐 / 结晶罐 */
    public static final List<String> PAN_TYPES = List.of("蒸发罐", "结晶罐");

    public static final List<String> PAN_STATUSES = List.of("运行", "洗罐", "停用");

    private static final Map<String, List<String>> PAN_NEXT = Map.of(
            "运行", List.of("洗罐", "停用"),
            "洗罐", List.of("运行", "停用"),
            "停用", List.of("洗罐"));

    /** 距上次洗罐超过该小时数即标记「需先洗罐」，未洗到点不许继续运行 */
    public static final long PAN_WASH_DUE_HOURS = 72L;

    // ---------------- 洗罐排程 ----------------
    /** 洗罐安排状态：待执行 / 清洗中 / 已完成（另有终态 已取消，不参与向前流转） */
    public static final List<String> WASH_STATUSES = List.of("待执行", "清洗中", "已完成", "已取消");

    /** 未完成（仍占罐）的洗罐安排：同一罐同一时刻只允许存在一笔 */
    public static final List<String> WASH_ACTIVE_STATUSES = List.of("待执行", "清洗中");

    /** 待执行 → 清洗中 → 已完成；待执行还可取消。已完成 / 已取消为终态 */
    private static final Map<String, List<String>> WASH_NEXT = Map.of(
            "待执行", List.of("清洗中", "已取消"),
            "清洗中", List.of("已完成"),
            "已完成", List.of(),
            "已取消", List.of());

    // ---------------- 成品糖 ----------------
    /** 品类：白砂糖 / 赤砂糖 / 绵白糖 / 冰糖 */
    public static final List<String> SUGAR_GRADES = List.of("白砂糖", "赤砂糖", "绵白糖", "冰糖");

    public static final List<String> BATCH_STATUSES = List.of("待复检", "已入库", "已发运");

    /** 质量复检结论：通过 / 不通过；尚无复检记录时对外派生为「待复检」 */
    public static final List<String> INSPECTION_RESULTS = List.of("通过", "不通过");

    public static final List<String> INSPECTION_STATUSES = List.of("待复检", "通过", "不通过");

    /** 待复检 → 已入库 → 已发运，复检通过是入库与发运的共同前置条件 */
    private static final Map<String, List<String>> BATCH_NEXT = Map.of(
            "待复检", List.of("已入库"),
            "已入库", List.of("已发运"),
            "已发运", List.of());

    public static final List<String> WAREHOUSES = List.of("1号糖仓", "2号糖仓", "3号糖仓");

    // ---------------- 业务阈值 ----------------
    /** 甘蔗糖度（Brix）下限，低于此值拒收 */
    public static final BigDecimal MIN_CANE_BRIX = BigDecimal.valueOf(11.0);

    /** 甘蔗纤维分上限（%），超过则扣杂 */
    public static final BigDecimal MAX_FIBER = BigDecimal.valueOf(15.0);

    /** 扣杂比例上限（%），超过需复检 */
    public static final BigDecimal MAX_DEDUCTION_RATE = BigDecimal.valueOf(12.0);

    /** 成品糖纯度下限（%），低于此值只能判为赤砂糖 */
    public static final BigDecimal MIN_WHITE_PURITY = BigDecimal.valueOf(99.5);

    /** 复检指标百分比取值范围 */
    public static final BigDecimal MIN_INSPECTION_PERCENT = BigDecimal.ZERO;
    public static final BigDecimal MAX_INSPECTION_PERCENT = BigDecimal.valueOf(100);

    /** 单批次最大装载量（千克），超过需拆分 */
    public static final BigDecimal MAX_BATCH_KG = BigDecimal.valueOf(60000);

    /** 榨季单日进厂上限（吨） */
    public static final BigDecimal MAX_DAILY_INTAKE_T = BigDecimal.valueOf(6000);

    // ---------------- 取值方法 ----------------

    public static boolean isValidIntakeResult(String r) {
        return r != null && INTAKE_RESULTS.contains(r);
    }

    public static boolean isValidIntakeStatus(String s) {
        return s != null && INTAKE_STATUSES.contains(s);
    }

    public static boolean isValidPressStatus(String s) {
        return s != null && PRESS_STATUSES.contains(s);
    }

    public static boolean canTransferPress(String from, String to) {
        return PRESS_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidFeedStatus(String s) {
        return s != null && FEED_STATUSES.contains(s);
    }

    public static boolean canTransferFeed(String from, String to) {
        return FEED_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidPanType(String t) {
        return t != null && PAN_TYPES.contains(t);
    }

    public static boolean isValidPanStatus(String s) {
        return s != null && PAN_STATUSES.contains(s);
    }

    public static boolean canTransferPan(String from, String to) {
        return PAN_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidWashStatus(String s) {
        return s != null && WASH_STATUSES.contains(s);
    }

    public static boolean canTransferWash(String from, String to) {
        return WASH_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    /** 未完成的洗罐安排：待执行 / 清洗中 */
    public static boolean isActiveWash(String s) {
        return s != null && WASH_ACTIVE_STATUSES.contains(s);
    }

    public static boolean isValidSugarGrade(String g) {
        return g != null && SUGAR_GRADES.contains(g);
    }

    public static boolean isValidBatchStatus(String s) {
        return s != null && BATCH_STATUSES.contains(s);
    }

    public static boolean canTransferBatch(String from, String to) {
        return BATCH_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidInspectionResult(String r) {
        return r != null && INSPECTION_RESULTS.contains(r);
    }

    public static boolean isValidInspectionStatus(String s) {
        return s != null && INSPECTION_STATUSES.contains(s);
    }

    public static boolean isPercent(BigDecimal v) {
        return v != null
                && v.compareTo(MIN_INSPECTION_PERCENT) >= 0
                && v.compareTo(MAX_INSPECTION_PERCENT) <= 0;
    }

    public static boolean isValidWarehouse(String w) {
        return w != null && WAREHOUSES.contains(w);
    }

    /**
     * 按糖度与纤维分判进厂结论：糖度不足或纤维超标拒收/扣杂，否则合格。
     */
    public static String decideIntake(BigDecimal brix, BigDecimal fiber) {
        if (brix != null && brix.compareTo(MIN_CANE_BRIX) < 0) {
            return "拒收";
        }
        if (fiber != null && fiber.compareTo(MAX_FIBER) > 0) {
            return "扣杂";
        }
        return "合格";
    }

    /**
     * 按纯度判成品糖品类：纯度达标记白砂糖，否则只能赤砂糖。
     */
    public static String decideGrade(BigDecimal purity) {
        if (purity != null && purity.compareTo(MIN_WHITE_PURITY) >= 0) {
            return "白砂糖";
        }
        return "赤砂糖";
    }
}
