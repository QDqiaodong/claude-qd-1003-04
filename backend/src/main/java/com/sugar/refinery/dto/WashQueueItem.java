package com.sugar.refinery.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.sugar.refinery.entity.EvapPan;
import com.sugar.refinery.entity.PanWash;

/**
 * 待洗清单行：罐体（罐型 / 当前状态 / 距上次洗罐时长 / 运行资格）与其当前未完成的
 * 洗罐安排（洗罐窗口 / 排程状态 / 失败可否重试）在同一行对齐，
 * 页面刷新后两边始终一致，而不是各给一个互不相干的列表。
 *
 * @param panId          罐 id
 * @param panCode        罐号
 * @param panName        罐名
 * @param panType        罐型：蒸发罐 / 结晶罐
 * @param capacityM3     容积
 * @param status         当前罐体状态：运行 / 洗罐 / 停用
 * @param lastWashAt     上次洗罐时间
 * @param hoursSinceWash 距上次洗罐小时数（未洗过为 null）
 * @param washDue        超过 72 小时，必须先洗罐
 * @param operable       运行资格
 * @param inoperableReason 无运行资格的原因
 * @param activeWash     当前未完成的洗罐安排（无则为 null）
 */
public record WashQueueItem(
        Long panId,
        String panCode,
        String panName,
        String panType,
        BigDecimal capacityM3,
        String status,
        LocalDateTime lastWashAt,
        Long hoursSinceWash,
        boolean washDue,
        boolean operable,
        String inoperableReason,
        ActiveWash activeWash) {

    /** 未完成洗罐安排的行内信息：窗口、排程状态与失败可重试提示 */
    public record ActiveWash(
            Long washId,
            String washNo,
            String washStatus,
            LocalDateTime windowAt,
            LocalDateTime startedAt,
            String failedReason,
            boolean retryable) {

        public static ActiveWash from(PanWash w) {
            return new ActiveWash(w.getId(), w.getWashNo(), w.getStatus(), w.getWindowAt(),
                    w.getStartedAt(), w.getFailedReason(), w.isRetryable());
        }
    }

    public static WashQueueItem from(EvapPan p, PanWash activeWash) {
        return new WashQueueItem(
                p.getId(), p.getPanCode(), p.getPanName(), p.getPanType(), p.getCapacityM3(),
                p.getStatus(), p.getLastWashAt(), p.getHoursSinceWash(), p.isWashDue(),
                p.isOperable(), p.getInoperableReason(),
                activeWash == null ? null : ActiveWash.from(activeWash));
    }
}
