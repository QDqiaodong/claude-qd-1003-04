package com.sugar.refinery;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.entity.PressStation;
import com.sugar.refinery.repository.PressStationRepository;
import com.sugar.refinery.service.PressStationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压榨机组状态闭环测试。
 *
 * <p>覆盖：运行 ↔ 停机 ↔ 检修 全状态机；转检修负荷归零 + 记录本次检修时间；
 * 检修中不能调负荷 / 开机 / 再次进入检修；非法转换保留原状态并说明原因；
 * requestId 幂等键下重复点击 / 超时重试只生效一次、检修时间不被覆盖；
 * 调负荷与转检修并发、重复流转并发时只落下一条一致结果。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PressStationServiceTest {

    /** 种子里检修机组的固定检修时间（整点，避免精度差干扰比较） */
    private static final LocalDateTime MAINTAIN_AT = LocalDateTime.of(2026, 9, 1, 8, 0, 0);

    @Autowired private PressStationService pressService;
    @Autowired private PressStationRepository pressRepository;

    private PressStation running;      // 运行，负荷 120 / 设计 200
    private PressStation stopped;      // 停机
    private PressStation maintaining;  // 检修（带历史检修时间）

    private PressStation save(String code, String status, String load, LocalDateTime maintainAt) {
        PressStation p = new PressStation();
        p.setStationCode(code);
        p.setStationName(code + "机组");
        p.setDesignCapacityTph(new BigDecimal("200.00"));
        p.setCurrentLoadTph(new BigDecimal(load));
        p.setOccupiedKg(BigDecimal.ZERO);
        p.setStatus(status);
        p.setLastMaintainAt(maintainAt);
        return pressRepository.save(p);
    }

    private PressStation reload(Long id) {
        return pressRepository.findById(id).orElseThrow();
    }

    @BeforeEach
    void setUp() {
        pressRepository.deleteAll();
        running = save("PS-T1", "运行", "120.00", null);
        stopped = save("PS-T2", "停机", "0.00", null);
        maintaining = save("PS-T3", "检修", "0.00", MAINTAIN_AT);
    }

    // ------------------------------------------------------------------
    // 状态机：转检修归零 + 记时间；检修完成只能回停机
    // ------------------------------------------------------------------

    @Test
    void maintain_from_running_zeroes_load_and_records_time() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        PressStation p = pressService.transfer(running.getId(), "检修", "req-m1");
        assertEquals("检修", p.getStatus());
        assertEquals(0, p.getCurrentLoadTph().compareTo(BigDecimal.ZERO), "转检修必须把负荷归零");
        assertNotNull(p.getLastMaintainAt(), "转检修必须记录本次检修时间");
        assertFalse(before.isAfter(p.getLastMaintainAt()), "检修时间应落在本次操作时刻附近");

        // 从库里重读确认三件事同事务持久化，不是只改了内存
        PressStation db = reload(running.getId());
        assertEquals("检修", db.getStatus());
        assertEquals(0, db.getCurrentLoadTph().compareTo(BigDecimal.ZERO));
        assertNotNull(db.getLastMaintainAt());
        assertEquals("req-m1", db.getLastRequestId());
    }

    @Test
    void maintain_from_stopped_records_time() {
        PressStation p = pressService.transfer(stopped.getId(), "检修", "req-m2");
        assertEquals("检修", p.getStatus());
        assertEquals(0, p.getCurrentLoadTph().compareTo(BigDecimal.ZERO));
        assertNotNull(p.getLastMaintainAt());
    }

    @Test
    void running_and_stopped_can_switch_and_stopped_keeps_load_rules() {
        // 运行 → 停机
        PressStation p = pressService.transfer(running.getId(), "停机", "req-s1");
        assertEquals("停机", p.getStatus());
        // 停机 → 运行
        p = pressService.transfer(running.getId(), "运行", "req-s2");
        assertEquals("运行", p.getStatus());
        // 停机 → 停机 / 运行 → 运行 不是合法流转，原状态保留
        assertThrows(BizException.class, () -> pressService.transfer(stopped.getId(), "停机", "req-s3"));
        assertEquals("停机", reload(stopped.getId()).getStatus());
        assertThrows(BizException.class, () -> pressService.transfer(running.getId(), "运行", "req-s4"));
        assertEquals("运行", reload(running.getId()).getStatus());
    }

    @Test
    void maintenance_can_only_finish_to_stopped() {
        // 检修中不能开机
        BizException e1 = assertThrows(BizException.class,
                () -> pressService.transfer(maintaining.getId(), "运行", "req-x1"));
        assertTrue(e1.getMessage().contains("不允许从「检修」转为「运行」"), e1.getMessage());
        assertTrue(e1.getMessage().contains("停机"), "原因要说明检修完成只能回停机：" + e1.getMessage());
        // 检修中不能再次进入检修
        BizException e2 = assertThrows(BizException.class,
                () -> pressService.transfer(maintaining.getId(), "检修", "req-x2"));
        assertTrue(e2.getMessage().contains("不能重复进入检修"), e2.getMessage());
        // 非法转换保留原状态：仍检修、负荷仍 0、历史检修时间未被覆盖
        PressStation db = reload(maintaining.getId());
        assertEquals("检修", db.getStatus());
        assertEquals(0, db.getCurrentLoadTph().compareTo(BigDecimal.ZERO));
        assertEquals(MAINTAIN_AT, db.getLastMaintainAt());

        // 检修完成 → 停机：状态推进，检修时间保留
        PressStation done = pressService.transfer(maintaining.getId(), "停机", "req-x3");
        assertEquals("停机", done.getStatus());
        assertEquals(MAINTAIN_AT, done.getLastMaintainAt());
        assertEquals(0, done.getCurrentLoadTph().compareTo(BigDecimal.ZERO));
    }

    @Test
    void invalid_target_rejected_and_state_kept() {
        BizException e = assertThrows(BizException.class,
                () -> pressService.transfer(running.getId(), "报废", "req-v1"));
        assertTrue(e.getMessage().contains("状态不合法"), e.getMessage());
        assertEquals("运行", reload(running.getId()).getStatus());
    }

    @Test
    void missing_request_id_rejected_and_state_kept() {
        assertThrows(BizException.class, () -> pressService.transfer(running.getId(), "停机", null));
        assertThrows(BizException.class, () -> pressService.transfer(running.getId(), "停机", "  "));
        assertEquals("运行", reload(running.getId()).getStatus());
    }

    // ------------------------------------------------------------------
    // 调负荷规则：仅运行中；检修 / 停机不可调；超载与负数拦截
    // ------------------------------------------------------------------

    @Test
    void set_load_rules() {
        // 运行中可调
        PressStation p = pressService.setLoad(running.getId(), new BigDecimal("150.00"));
        assertEquals(0, p.getCurrentLoadTph().compareTo(new BigDecimal("150.00")));
        // 超过设计处理量被拦，负荷保持原值
        assertThrows(BizException.class, () -> pressService.setLoad(running.getId(), new BigDecimal("200.01")));
        assertEquals(0, reload(running.getId()).getCurrentLoadTph().compareTo(new BigDecimal("150.00")));
        // 负数被拦
        assertThrows(BizException.class, () -> pressService.setLoad(running.getId(), new BigDecimal("-1")));
        // 停机不能调
        BizException e1 = assertThrows(BizException.class,
                () -> pressService.setLoad(stopped.getId(), new BigDecimal("50")));
        assertTrue(e1.getMessage().contains("停机"), e1.getMessage());
        // 检修中不能调，且负荷保持 0
        BizException e2 = assertThrows(BizException.class,
                () -> pressService.setLoad(maintaining.getId(), new BigDecimal("50")));
        assertTrue(e2.getMessage().contains("检修"), e2.getMessage());
        assertEquals(0, reload(maintaining.getId()).getCurrentLoadTph().compareTo(BigDecimal.ZERO));
    }

    // ------------------------------------------------------------------
    // 幂等：重复点击 / 超时重试只生效一次，检修时间不被覆盖
    // ------------------------------------------------------------------

    @Test
    void duplicate_transfer_replays_without_overwriting_maintain_time() {
        pressService.transfer(running.getId(), "检修", "req-dup");
        LocalDateTime t1 = reload(running.getId()).getLastMaintainAt();
        assertNotNull(t1);

        // 同一 requestId 重试（超时重发 / 重复点击）：直接回放现状，不重复记录检修时间
        PressStation replay = pressService.transfer(running.getId(), "检修", "req-dup");
        assertEquals("检修", replay.getStatus());
        assertEquals(t1, reload(running.getId()).getLastMaintainAt(), "重复请求不得覆盖检修时间");

        // 换个新键再点「转检修」：已是检修，拒绝且时间仍不被覆盖
        assertThrows(BizException.class, () -> pressService.transfer(running.getId(), "检修", "req-new"));
        assertEquals(t1, reload(running.getId()).getLastMaintainAt());
    }

    @Test
    void reused_request_id_with_different_target_rejected() {
        pressService.transfer(running.getId(), "停机", "req-k1");
        // 同一键换目标重提：不静默合并，拒绝并提示核对；状态不被翻动
        BizException e = assertThrows(BizException.class,
                () -> pressService.transfer(running.getId(), "检修", "req-k1"));
        assertTrue(e.getMessage().contains("已受理"), e.getMessage());
        PressStation db = reload(running.getId());
        assertEquals("停机", db.getStatus());
        assertNull(db.getLastMaintainAt(), "被拒绝的请求不能留下检修时间");
    }

    // ------------------------------------------------------------------
    // 并发竞态：只落下一条一致结果
    // ------------------------------------------------------------------

    @Test
    void concurrent_set_load_and_maintain_settle_consistently() throws Exception {
        Long id = running.getId();
        AtomicReference<Throwable> loadErr = new AtomicReference<>();
        AtomicReference<Throwable> transferErr = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);

        Thread t1 = new Thread(() -> {
            await(go);
            try {
                pressService.setLoad(id, new BigDecimal("180.00"));
            } catch (Throwable e) {
                loadErr.set(e);
            }
        });
        Thread t2 = new Thread(() -> {
            await(go);
            try {
                pressService.transfer(id, "检修", "req-race");
            } catch (Throwable e) {
                transferErr.set(e);
            }
        });
        t1.start();
        t2.start();
        go.countDown();
        t1.join();
        t2.join();

        // 运行 → 检修 必定成功；调负荷要么先落（随后被转检修归零），要么读到检修被拦
        assertNull(transferErr.get(), "运行 → 检修的流转必须成功");
        if (loadErr.get() != null) {
            assertTrue(loadErr.get() instanceof BizException);
            assertTrue(loadErr.get().getMessage().contains("运行"), loadErr.get().getMessage());
        }
        // 唯一一致结果：检修 + 负荷归零 + 有检修时间；绝不出现「状态已是检修但负荷仍在」
        PressStation db = reload(id);
        assertEquals("检修", db.getStatus());
        assertEquals(0, db.getCurrentLoadTph().compareTo(BigDecimal.ZERO),
                "转检修后负荷必须归零，不能残留并发调负荷写入的值");
        assertNotNull(db.getLastMaintainAt());
    }

    @Test
    void concurrent_same_request_id_applies_once() throws Exception {
        Long id = running.getId();
        AtomicReference<Throwable> e1 = new AtomicReference<>();
        AtomicReference<Throwable> e2 = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);

        // 两个线程带同一幂等键（双击 / 超时重发）：一次生效 + 一次回放
        Thread t1 = new Thread(() -> {
            await(go);
            try {
                pressService.transfer(id, "检修", "req-same");
            } catch (Throwable e) {
                e1.set(e);
            }
        });
        Thread t2 = new Thread(() -> {
            await(go);
            try {
                pressService.transfer(id, "检修", "req-same");
            } catch (Throwable e) {
                e2.set(e);
            }
        });
        t1.start();
        t2.start();
        go.countDown();
        t1.join();
        t2.join();

        assertNull(e1.get(), "同一幂等键的回放必须成功");
        assertNull(e2.get(), "同一幂等键的回放必须成功");
        PressStation db = reload(id);
        assertEquals("检修", db.getStatus());
        assertEquals(0, db.getCurrentLoadTph().compareTo(BigDecimal.ZERO));
        assertNotNull(db.getLastMaintainAt());
        assertEquals("req-same", db.getLastRequestId());
    }

    @Test
    void concurrent_distinct_requests_only_one_applies() throws Exception {
        Long id = running.getId();
        AtomicReference<Throwable> e1 = new AtomicReference<>();
        AtomicReference<Throwable> e2 = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);

        // 两个不同请求同时转检修：行锁串行后只有一个生效，另一个读到「检修」被拦
        Thread t1 = new Thread(() -> {
            await(go);
            try {
                pressService.transfer(id, "检修", "req-a");
            } catch (Throwable e) {
                e1.set(e);
            }
        });
        Thread t2 = new Thread(() -> {
            await(go);
            try {
                pressService.transfer(id, "检修", "req-b");
            } catch (Throwable e) {
                e2.set(e);
            }
        });
        t1.start();
        t2.start();
        go.countDown();
        t1.join();
        t2.join();

        int errors = (e1.get() != null ? 1 : 0) + (e2.get() != null ? 1 : 0);
        assertEquals(1, errors, "两个不同的转检修请求只能生效一个");
        Throwable loser = e1.get() != null ? e1.get() : e2.get();
        assertTrue(loser instanceof BizException);
        assertTrue(loser.getMessage().contains("不能重复进入检修"), loser.getMessage());

        PressStation db = reload(id);
        assertEquals("检修", db.getStatus());
        assertEquals(0, db.getCurrentLoadTph().compareTo(BigDecimal.ZERO));
        assertNotNull(db.getLastMaintainAt());
        // 落下来的幂等键是胜者的那个，败者没有留下任何痕迹
        String winnerKey = e1.get() == null ? "req-a" : "req-b";
        assertEquals(winnerKey, db.getLastRequestId());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
