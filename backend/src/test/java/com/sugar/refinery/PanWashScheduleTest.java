package com.sugar.refinery;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.dto.WashQueueItem;
import com.sugar.refinery.entity.EvapPan;
import com.sugar.refinery.entity.PanWash;
import com.sugar.refinery.repository.EvapPanRepository;
import com.sugar.refinery.repository.PanWashRepository;
import com.sugar.refinery.service.EvapPanService;
import com.sugar.refinery.service.PanWashService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 蒸发结晶罐洗罐排程闭环测试。
 *
 * <p>覆盖：待洗清单与 72h 判定、停用罐不可入洗、同一罐唯一未完成安排（服务层 + 生成列
 * 唯一索引）、待执行→清洗中→已完成全流转、取消、重复开始 / 改罐型拦截、完成时完成时间
 * 与上次洗罐时间同事务写回并恢复运行资格、一键清洗部分失败（已完成保留、失败罐安全重试）、
 * 失败原因落库且刷新后可重试、手工状态流转互锁。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PanWashScheduleTest {

    @Autowired private PanWashService washService;
    @Autowired private EvapPanService panService;
    @Autowired private PanWashRepository washRepository;
    @Autowired private EvapPanRepository panRepository;

    private EvapPan duePan;       // 超 72h 未洗、运行中
    private EvapPan freshPan;     // 刚洗过、运行中
    private EvapPan stoppedPan;   // 停用中
    private EvapPan crystalPan;   // 结晶罐

    private static EvapPan pan(String code, String name, String type, String status, int washAgoHours) {
        EvapPan p = new EvapPan();
        p.setPanCode(code);
        p.setPanName(name);
        p.setPanType(type);
        p.setCapacityM3(new BigDecimal("100.00"));
        p.setStatus(status);
        p.setLastWashAt(washAgoHours < 0 ? null : LocalDateTime.now().minusHours(washAgoHours));
        return p;
    }

    @BeforeEach
    void setUp() {
        washRepository.deleteAll();
        panRepository.deleteAll();
        duePan = panRepository.save(pan("EP-T1", "到期蒸发罐", "蒸发罐", "运行", 80));
        freshPan = panRepository.save(pan("EP-T2", "新鲜蒸发罐", "蒸发罐", "运行", 5));
        stoppedPan = panRepository.save(pan("EP-T3", "停用蒸发罐", "蒸发罐", "停用", 100));
        crystalPan = panRepository.save(pan("CP-T1", "到期结晶罐", "结晶罐", "运行", 96));
    }

    // ------------------------------------------------------------------
    // 待洗清单
    // ------------------------------------------------------------------

    @Test
    void queue_marks_over_72h_as_due_and_aligns_active_wash() {
        List<WashQueueItem> rows = washService.queue(null, null, false);
        assertEquals(4, rows.size());

        WashQueueItem due = rows.stream().filter(r -> r.panId().equals(duePan.getId())).findFirst().orElseThrow();
        assertTrue(due.washDue(), "超过 72h 必须标为需先洗罐");
        assertFalse(due.operable(), "超期罐无运行资格");
        assertNotNull(due.inoperableReason());
        assertTrue(due.inoperableReason().contains("需先洗罐"));

        WashQueueItem fresh = rows.stream().filter(r -> r.panId().equals(freshPan.getId())).findFirst().orElseThrow();
        assertFalse(fresh.washDue());
        assertTrue(fresh.operable());
        assertNull(fresh.inoperableReason());

        // 到期罐排在未到期罐前面
        assertTrue(rows.indexOf(due) < rows.indexOf(fresh));

        // 罐型过滤
        List<WashQueueItem> crystals = washService.queue("结晶罐", null, false);
        assertEquals(1, crystals.size());
        assertEquals("结晶罐", crystals.get(0).panType());

        // 状态过滤 + 只看到期（到期蒸发罐、到期结晶罐、超期停用罐共 3 只）
        assertEquals(1, washService.queue(null, "停用", false).size());
        assertEquals(3, washService.queue(null, null, true).size());
    }

    // ------------------------------------------------------------------
    // 安排 / 开始 / 完成
    // ------------------------------------------------------------------

    @Test
    void schedule_start_complete_writes_times_and_restores_operability() {
        // 到期罐无运行资格
        assertFalse(panService.get(duePan.getId()).isOperable());

        LocalDateTime window = LocalDateTime.now().plusHours(2);
        PanWash w = washService.schedule(duePan.getId(), window);
        assertEquals("待执行", w.getStatus());
        assertEquals("蒸发罐", w.getPanType());
        assertNull(w.getStartedAt());
        assertNull(w.getCompletedAt());
        assertEquals(duePan.getId(), w.getPanId());

        // 清单项与未完成排程对齐
        WashQueueItem row = washService.queue(null, null, false).stream()
                .filter(r -> r.panId().equals(duePan.getId())).findFirst().orElseThrow();
        assertNotNull(row.activeWash());
        assertEquals(w.getId(), row.activeWash().washId());
        assertEquals("待执行", row.activeWash().washStatus());

        // 同一罐不能重复安排
        assertThrows(BizException.class, () -> washService.schedule(duePan.getId(), window));

        // 开始：罐转洗罐、写开始时间
        w = washService.start(w.getId());
        assertEquals("清洗中", w.getStatus());
        assertNotNull(w.getStartedAt());
        assertEquals("洗罐", panService.get(duePan.getId()).getStatus());

        final Long activeId = w.getId();
        // 清洗中不能重复开始
        assertThrows(BizException.class, () -> washService.start(activeId));
        // 清洗中不能取消
        assertThrows(BizException.class, () -> washService.cancel(activeId));

        // 完成：完成时间 + 罐体上次洗罐时间同事务写回，罐回运行、恢复资格
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        w = washService.complete(w.getId());
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);
        // 两边都从库中重新读出，比较持久化后的时间（数据库精度下一致）
        PanWash wDone = washRepository.findById(w.getId()).orElseThrow();
        assertEquals("已完成", wDone.getStatus());
        assertNotNull(wDone.getCompletedAt(), "完成必须写入完成时间");
        assertFalse(wDone.getCompletedAt().isBefore(before) || wDone.getCompletedAt().isAfter(after),
                "完成时间应在本次完成时刻附近");

        EvapPan reloaded = panService.get(duePan.getId());
        assertEquals("运行", reloaded.getStatus());
        assertNotNull(reloaded.getLastWashAt());
        assertEquals(wDone.getCompletedAt(), reloaded.getLastWashAt(),
                "完成时间必须与罐体上次洗罐时间同一事务一起写");
        assertTrue(reloaded.isOperable(), "洗完应恢复运行资格");
        assertFalse(reloaded.isWashDue());

        // 完成后该罐可以再安排新窗口
        PanWash next = washService.schedule(duePan.getId(), LocalDateTime.now().plusHours(1));
        assertEquals("待执行", next.getStatus());
    }

    @Test
    void stop_pan_cannot_be_scheduled_or_started() {
        // 停用中的罐不能直接进入洗罐
        assertThrows(BizException.class,
                () -> washService.schedule(stoppedPan.getId(), LocalDateTime.now().plusHours(1)));

        // 即便绕过服务直接落一笔待执行，开始时仍拦得住（且事务回滚，罐与排程保持安全状态）
        PanWash raw = new PanWash();
        raw.setWashNo("PW-RAW-0001");
        raw.setPanId(stoppedPan.getId());
        raw.setPanCode(stoppedPan.getPanCode());
        raw.setPanName(stoppedPan.getPanName());
        raw.setPanType(stoppedPan.getPanType());
        raw.setWindowAt(LocalDateTime.now().plusHours(1));
        raw.setStatus("待执行");
        raw.setCreatedAt(LocalDateTime.now());
        raw = washRepository.saveAndFlush(raw);

        final Long rawId = raw.getId();
        assertThrows(BizException.class, () -> washService.start(rawId));
        // 开始失败整体回滚：仍是待执行、罐仍停用
        assertEquals("待执行", washRepository.findById(rawId).orElseThrow().getStatus());
        assertEquals("停用", panService.get(stoppedPan.getId()).getStatus());
    }

    @Test
    void cancel_releases_pan_for_new_schedule() {
        PanWash w = washService.schedule(freshPan.getId(), LocalDateTime.now().plusHours(3));
        washService.cancel(w.getId());
        assertEquals("已取消", washRepository.findById(w.getId()).orElseThrow().getStatus());
        assertNotNull(washRepository.findById(w.getId()).orElseThrow().getCancelledAt());

        // 取消后不再占罐，可重新安排；历史记录保留
        PanWash again = washService.schedule(freshPan.getId(), LocalDateTime.now().plusHours(4));
        assertEquals("待执行", again.getStatus());
        assertEquals(2, washRepository.findByPanIdOrderByIdDesc(freshPan.getId()).size());
    }

    // ------------------------------------------------------------------
    // 一键清洗 + 部分失败
    // ------------------------------------------------------------------

    @Test
    void execute_runs_pending_to_completed_in_one_transaction() {
        PanWash w = washService.schedule(freshPan.getId(), LocalDateTime.now().plusHours(1));
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        w = washService.execute(w.getId());
        PanWash wDone = washRepository.findById(w.getId()).orElseThrow();
        assertEquals("已完成", wDone.getStatus());
        assertNotNull(wDone.getStartedAt());
        assertNotNull(wDone.getCompletedAt());
        EvapPan p = panService.get(freshPan.getId());
        assertEquals("运行", p.getStatus());
        assertEquals(wDone.getCompletedAt(), p.getLastWashAt());
        assertFalse(p.getLastWashAt().isBefore(before));
    }

    @Test
    void execute_batch_partial_failure_keeps_completed_and_failed_stays_retryable() {
        // A：待执行的到期罐，可一键洗完
        PanWash a = washService.schedule(duePan.getId(), LocalDateTime.now().plusHours(1));
        // B：把到期结晶罐手动推进到清洗中（罐状态联动为洗罐）
        PanWash b = washService.schedule(crystalPan.getId(), LocalDateTime.now().plusHours(1));
        washService.start(b.getId());
        // 制造 B 的失败：把罐手工置为「运行」且未洗（模拟外部异常 / 状态被改动），
        // complete/execute 必须整体回滚，绝不能只把排程改成已完成
        EvapPan bPan = panService.get(crystalPan.getId());
        bPan.setStatus("运行");
        bPan.setLastWashAt(LocalDateTime.now().minusHours(96));
        panRepository.saveAndFlush(bPan);

        // A 成功
        washService.execute(a.getId());
        assertEquals("已完成", washRepository.findById(a.getId()).orElseThrow().getStatus());

        // B 失败：抛错、状态不变、没有完成时间（不能只改状态没有洗罐时间）
        assertThrows(BizException.class, () -> washService.execute(b.getId()));
        PanWash bAfter = washRepository.findById(b.getId()).orElseThrow();
        assertEquals("清洗中", bAfter.getStatus());
        assertNull(bAfter.getCompletedAt());

        // 模拟控制器兜底：失败原因落库，但状态仍是清洗中、可安全重试
        washService.recordFailure(b.getId(), "罐当前为「运行」而非清洗中，无法完成洗罐");
        PanWash bFailed = washRepository.findById(b.getId()).orElseThrow();
        assertEquals("清洗中", bFailed.getStatus());
        assertNotNull(bFailed.getFailedReason());
        assertTrue(bFailed.isRetryable(), "失败罐必须能看出可以重试");

        // 清单上失败信息长在对应罐那一行，不是独立列表提示
        WashQueueItem bRow = washService.queue(null, null, false).stream()
                .filter(r -> r.panId().equals(crystalPan.getId())).findFirst().orElseThrow();
        assertNotNull(bRow.activeWash());
        assertTrue(bRow.activeWash().retryable());
        assertEquals(bFailed.getFailedReason(), bRow.activeWash().failedReason());

        // 修复现场（罐回到洗罐）后重试完成，成功并清空失败原因
        EvapPan fixed = panService.get(crystalPan.getId());
        fixed.setStatus("洗罐");
        panRepository.saveAndFlush(fixed);
        PanWash bRetry = washService.complete(b.getId());
        assertEquals("已完成", bRetry.getStatus());
        assertNotNull(bRetry.getCompletedAt());
        assertNull(bRetry.getFailedReason(), "重试成功后应清空失败原因");
        assertFalse(bRetry.isRetryable());
        assertTrue(panService.get(crystalPan.getId()).isOperable());

        // 已完成的 A 记录始终保留
        assertEquals("已完成", washRepository.findById(a.getId()).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // 唯一未完成安排：数据库生成列唯一索引兜底
    // ------------------------------------------------------------------

    @Test
    void database_unique_index_blocks_two_active_washes() {
        washService.schedule(freshPan.getId(), LocalDateTime.now().plusHours(1));
        // 绕过服务层直接插第二笔待执行：生成列唯一索引必须拒绝
        PanWash dup = new PanWash();
        dup.setWashNo("PW-DUP-0001");
        dup.setPanId(freshPan.getId());
        dup.setPanCode(freshPan.getPanCode());
        dup.setPanName(freshPan.getPanName());
        dup.setPanType(freshPan.getPanType());
        dup.setWindowAt(LocalDateTime.now().plusHours(2));
        dup.setStatus("待执行");
        dup.setCreatedAt(LocalDateTime.now());
        assertThrows(DataIntegrityViolationException.class, () -> washRepository.saveAndFlush(dup));
    }

    // ------------------------------------------------------------------
    // 手工状态流转 / 老洗罐入口互锁
    // ------------------------------------------------------------------

    @Test
    void manual_transfer_is_blocked_by_active_wash() {
        PanWash pending = washService.schedule(freshPan.getId(), LocalDateTime.now().plusHours(1));
        // 有待执行安排时不能手动转入洗罐
        assertThrows(BizException.class, () -> panService.transfer(freshPan.getId(), "洗罐"));
        // 但可以停用（与排程无关的流转仍允许）
        panService.transfer(freshPan.getId(), "停用");
        assertEquals("停用", panService.get(freshPan.getId()).getStatus());

        // 清洗中的罐不能手动改任何状态
        PanWash started = washService.schedule(duePan.getId(), LocalDateTime.now().plusHours(1));
        washService.start(started.getId());
        assertThrows(BizException.class, () -> panService.transfer(duePan.getId(), "运行"));
        assertThrows(BizException.class, () -> panService.transfer(duePan.getId(), "停用"));

        // 老的直接洗罐入口也不能绕过排程
        assertThrows(BizException.class, () -> panService.wash(duePan.getId()));

        // 排程完成后手工流转恢复可用
        washService.complete(started.getId());
        // 取消掉待执行的那笔（freshPan 已手动停用；停用罐的待执行安排仍可取消）
        washService.cancel(pending.getId());
        // 完成排程后 duePan 回到运行且恢复资格；freshPan 保持手动停用
        assertEquals("运行", panService.get(duePan.getId()).getStatus());
        assertTrue(panService.get(duePan.getId()).isOperable());
        assertEquals("停用", panService.get(freshPan.getId()).getStatus());
    }
}
