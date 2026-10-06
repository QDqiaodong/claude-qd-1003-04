package com.sugar.refinery;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.entity.PressStation;
import com.sugar.refinery.repository.PressControlRepository;
import com.sugar.refinery.repository.PressStationRepository;
import com.sugar.refinery.service.PressStationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压榨机组检修切换与负荷归零闭环测试。
 *
 * <p>覆盖：① 运行 / 停机转检修负荷归零并记录本次检修时间；② 检修完成只能回停机；
 * ③ 检修三禁（不能调负荷 / 开机 / 再次进入检修），非法转换保留原状态并给出原因；
 * ④ requestId 幂等：重复请求只落一条、检修时间不被覆盖；⑤ 并发竞态：调负荷与
 * 转检修同时发生、重复点击，最终只有一条一致结果，不出现「已检修但负荷仍在」；
 * ⑥ 刷新还原：从库中重新读出的状态 / 负荷 / 检修时间与接口结果一致。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PressStationWorkflowTest {

    @Autowired private PressStationService pressService;
    @Autowired private PressStationRepository pressRepository;
    @Autowired private PressControlRepository controlRepository;

    @BeforeEach
    void setUp() {
        controlRepository.deleteAll();
        pressRepository.deleteAll();
    }

    private PressStation station(String code, String status, String load, String capacity) {
        PressStation p = new PressStation();
        p.setStationCode(code);
        p.setStationName(code + "机组");
        p.setDesignCapacityTph(new BigDecimal(capacity));
        p.setCurrentLoadTph(new BigDecimal(load));
        p.setOccupiedKg(BigDecimal.ZERO);
        p.setStatus(status);
        p.setLastMaintainAt(LocalDateTime.now().minusDays(30));
        return pressRepository.save(p);
    }

    private static String rid(String tag) {
        // request_id 列为 VARCHAR(36)（与投料批幂等键一致，前端用标准 UUID），故用纯 UUID
        return java.util.UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------
    // 状态迁移 + 负荷归零 + 检修时间
    // ------------------------------------------------------------------

    @Test
    void running_to_maintenance_zeroes_load_and_stamps_time() {
        PressStation p = station("PS-T1", "运行", "180.00", "200.00");
        LocalDateTime oldTime = p.getLastMaintainAt();

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        PressStation after = pressService.transfer(p.getId(), "检修", rid("run-maint"));
        LocalDateTime afterWhen = LocalDateTime.now().plusSeconds(1);

        assertEquals("检修", after.getStatus());
        assertEquals(0, after.getCurrentLoadTph().compareTo(BigDecimal.ZERO), "转检修后当前负荷必须归零");
        assertNotNull(after.getLastMaintainAt());
        assertFalse(after.getLastMaintainAt().isBefore(before) || after.getLastMaintainAt().isAfter(afterWhen),
                "必须记录本次检修时间");
        assertTrue(after.getLastMaintainAt().isAfter(oldTime.minusSeconds(1)), "检修时间应更新为本次时刻");

        // 刷新还原：从库里重新读出，状态、负荷、检修时间完全一致
        PressStation reloaded = pressService.get(p.getId());
        assertEquals("检修", reloaded.getStatus());
        assertEquals(0, reloaded.getCurrentLoadTph().compareTo(BigDecimal.ZERO));
        assertEquals(after.getLastMaintainAt(), reloaded.getLastMaintainAt());
    }

    @Test
    void stopped_to_maintenance_zeroes_load_and_maintenance_finish_only_to_stopped() {
        PressStation p = station("PS-T2", "停机", "0.00", "180.00");
        PressStation m = pressService.transfer(p.getId(), "检修", rid("stop-maint"));
        assertEquals("检修", m.getStatus());
        assertEquals(0, m.getCurrentLoadTph().compareTo(BigDecimal.ZERO));

        // 检修完成只能回停机
        PressStation back = pressService.transfer(p.getId(), "停机", rid("finish"));
        assertEquals("停机", back.getStatus());
        // 回停机不会清掉最近检修时间（保留本次检修记录）
        assertNotNull(back.getLastMaintainAt());
        // 回到停机后可以重新开机，且开机后负荷仍是 0（需值班员重新调）
        PressStation running = pressService.transfer(p.getId(), "运行", rid("start"));
        assertEquals("运行", running.getStatus());
        assertEquals(0, running.getCurrentLoadTph().compareTo(BigDecimal.ZERO));
    }

    @Test
    void running_stopped_transitions_and_load_adjustment() {
        PressStation p = station("PS-T3", "运行", "100.00", "200.00");
        // 运行中调负荷
        assertEquals(0, pressService.setLoad(p.getId(), new BigDecimal("150.00"), rid("load"))
                .getCurrentLoadTph().compareTo(new BigDecimal("150.00")));
        // 运行 → 停机
        assertEquals("停机", pressService.transfer(p.getId(), "停机", rid("stop")).getStatus());
        // 停机 → 运行
        assertEquals("运行", pressService.transfer(p.getId(), "运行", rid("start")).getStatus());
        assertEquals(0, pressService.get(p.getId()).getCurrentLoadTph().compareTo(new BigDecimal("150.00")),
                "停机再开机不应改动已设定的负荷");
    }

    // ------------------------------------------------------------------
    // 非法转换：保留原状态 + 说明原因
    // ------------------------------------------------------------------

    @Test
    void illegal_transitions_keep_original_status_and_explain_reason() {
        PressStation m = station("PS-T4", "检修", "0.00", "200.00");
        // 以数据库实际存储（微秒精度）为基线，避免纳秒时钟与列精度差异造成误判
        LocalDateTime maintainAt = pressService.get(m.getId()).getLastMaintainAt();

        // 检修中不能调负荷
        BizException e1 = assertThrows(BizException.class,
                () -> pressService.setLoad(m.getId(), new BigDecimal("100.00"), rid("load-in-maint")));
        assertTrue(e1.getMessage().contains("只有「运行」"), e1.getMessage());

        // 检修中不能开机
        BizException e2 = assertThrows(BizException.class,
                () -> pressService.transfer(m.getId(), "运行", rid("start-in-maint")));
        assertTrue(e2.getMessage().contains("不能直接开机"), e2.getMessage());

        // 检修中不能再次进入检修
        BizException e3 = assertThrows(BizException.class,
                () -> pressService.transfer(m.getId(), "检修", rid("maint-again")));
        assertTrue(e3.getMessage().contains("不能重复进入检修"), e3.getMessage());

        // 非法目标状态
        assertThrows(BizException.class,
                () -> pressService.transfer(m.getId(), "不存在", rid("bad-target")));

        // 原状态、零负荷、检修时间全部保留
        PressStation unchanged = pressService.get(m.getId());
        assertEquals("检修", unchanged.getStatus());
        assertEquals(0, unchanged.getCurrentLoadTph().compareTo(BigDecimal.ZERO));
        assertEquals(maintainAt, unchanged.getLastMaintainAt(), "非法转换不得改写检修时间");
    }

    @Test
    void stopped_cannot_adjust_load_and_overload_rejected() {
        PressStation stopped = station("PS-T5", "停机", "0.00", "200.00");
        BizException e = assertThrows(BizException.class,
                () -> pressService.setLoad(stopped.getId(), new BigDecimal("10.00"), rid("load-stopped")));
        assertTrue(e.getMessage().contains("只有「运行」"), e.getMessage());
        assertEquals("停机", pressService.get(stopped.getId()).getStatus());

        PressStation running = station("PS-T6", "运行", "100.00", "200.00");
        BizException over = assertThrows(BizException.class,
                () -> pressService.setLoad(running.getId(), new BigDecimal("201.00"), rid("overload")));
        assertTrue(over.getMessage().contains("超过设计处理量"), over.getMessage());
        // 超负荷设定被拦，负荷仍是原值
        assertEquals(0, pressService.get(running.getId()).getCurrentLoadTph().compareTo(new BigDecimal("100.00")));

        assertThrows(BizException.class,
                () -> pressService.setLoad(running.getId(), new BigDecimal("-1.00"), rid("negative")));
        assertThrows(BizException.class,
                () -> pressService.setLoad(running.getId(), new BigDecimal("10.00"), "  "));
    }

    // ------------------------------------------------------------------
    // 幂等：重复请求只落一条
    // ------------------------------------------------------------------

    @Test
    void duplicate_transfer_request_applies_once_and_keeps_first_maintain_time() throws Exception {
        PressStation p = station("PS-T7", "运行", "180.00", "200.00");
        String sameRid = rid("dup-maint");

        PressStation first = pressService.transfer(p.getId(), "检修", sameRid);
        LocalDateTime firstTime = first.getLastMaintainAt();
        long controlCount = controlRepository.count();

        // 等待时钟跨过秒级精度，确保若被覆盖就能看出时间不同
        Thread.sleep(1200);

        // 重复点击 / 超时重试，同一 requestId：直接返回已有结果，不重复推进、不覆盖检修时间
        PressStation again = pressService.transfer(p.getId(), "检修", sameRid);
        assertEquals("检修", again.getStatus());
        assertEquals(firstTime, again.getLastMaintainAt(), "重复请求不得覆盖检修时间");
        assertEquals(controlCount, controlRepository.count(), "同一意图只落一条操作流水");

        // 检修完成的重复请求同样幂等：第一次把状态带回停机，第二次（同键）返回停机而非报错
        String finishRid = rid("dup-finish");
        assertEquals("停机", pressService.transfer(p.getId(), "停机", finishRid).getStatus());
        assertEquals("停机", pressService.transfer(p.getId(), "停机", finishRid).getStatus());
    }

    @Test
    void duplicate_setload_request_applies_once() {
        PressStation p = station("PS-T8", "运行", "100.00", "200.00");
        String sameRid = rid("dup-load");
        pressService.setLoad(p.getId(), new BigDecimal("120.00"), sameRid);
        // 同键重复（哪怕拖到超时后再点）：负荷不再变化，流水只有一条
        PressStation dup = pressService.setLoad(p.getId(), new BigDecimal("120.00"), sameRid);
        assertEquals(0, dup.getCurrentLoadTph().compareTo(new BigDecimal("120.00")));
        assertEquals(1, controlRepository.count());

        // 同键但内容不一致：明确拒绝，不静默合并
        assertThrows(BizException.class,
                () -> pressService.setLoad(p.getId(), new BigDecimal("130.00"), sameRid));
        assertEquals(0, pressService.get(p.getId()).getCurrentLoadTph().compareTo(new BigDecimal("120.00")));
    }

    // ------------------------------------------------------------------
    // 并发竞态：调负荷 vs 转检修同时发生
    // ------------------------------------------------------------------

    @Test
    void concurrent_load_and_maintenance_lands_one_consistent_result() throws Exception {
        // 每个机组跑一轮，重复多轮提高竞态命中率
        for (int round = 0; round < 5; round++) {
            PressStation p = station("PS-C" + round, "运行", "100.00", "200.00");
            Long id = p.getId();
            String loadRid = rid("c-load-" + round);
            String maintRid = rid("c-maint-" + round);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();

            Runnable loadTask = () -> {
                try {
                    start.await();
                    pressService.setLoad(id, new BigDecimal("190.00"), loadRid);
                    ok.incrementAndGet();
                } catch (BizException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            Runnable maintTask = () -> {
                try {
                    start.await();
                    pressService.transfer(id, "检修", maintRid);
                    ok.incrementAndGet();
                } catch (BizException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            pool.submit(loadTask);
            pool.submit(maintTask);
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "并发任务应在超时前结束");
            assertEquals(2, ok.get() + rejected.get(), "每个并发任务都应得到确定结果");

            PressStation finalRow = pressService.get(id);
            if ("检修".equals(finalRow.getStatus())) {
                // 检修胜出：负荷必须为零（行锁串行 + 条件更新保证，绝不会带着负荷进检修）
                assertEquals(0, finalRow.getCurrentLoadTph().compareTo(BigDecimal.ZERO),
                        "第 " + round + " 轮：状态已检修，负荷必须归零");
                assertNotNull(finalRow.getLastMaintainAt());
            } else {
                // 调负荷胜出（机组仍运行）：负荷是新值；之后转检修仍能正常归零
                assertEquals("运行", finalRow.getStatus());
                assertEquals(0, finalRow.getCurrentLoadTph().compareTo(new BigDecimal("190.00")));
                PressStation m = pressService.transfer(id, "检修", rid("c-after-" + round));
                assertEquals(0, m.getCurrentLoadTph().compareTo(BigDecimal.ZERO));
            }
        }
    }

    @Test
    void concurrent_duplicate_maintenance_clicks_keep_single_timestamp() throws Exception {
        for (int round = 0; round < 5; round++) {
            PressStation p = station("PS-D" + round, "运行", "150.00", "200.00");
            // 同一意图（同一 requestId）被双击 / 超时重试并发发出
            String sameRid = rid("d-maint-" + round);
            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        pressService.transfer(p.getId(), "检修", sameRid);
                        success.incrementAndGet();
                    } catch (Exception e) {
                        // 唯一索引冲突等并发错误由控制器兜底合并；服务层直接调用时允许抛出
                        errors.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

            PressStation row = pressService.get(p.getId());
            assertEquals("检修", row.getStatus());
            assertEquals(0, row.getCurrentLoadTph().compareTo(BigDecimal.ZERO));
            assertNotNull(row.getLastMaintainAt());
            // 同一 requestId 的幂等流水最多一条（数据库唯一索引兜底）
            assertEquals(1, controlRepository.findAll().stream()
                    .filter(c -> sameRid.equals(c.getRequestId())).count(),
                    "同一意图只允许落下一条操作流水");
            assertEquals(threads, success.get() + errors.get());
        }
    }

    @Test
    void failed_operation_changes_nothing_and_is_safely_retryable() {
        PressStation p = station("PS-T9", "运行", "100.00", "200.00");
        // 以数据库实际存储（微秒精度）为基线
        LocalDateTime maintainAt = pressService.get(p.getId()).getLastMaintainAt();

        // 非法转检修目标 / 非法状态切换整体回滚，机组无任何改动
        assertThrows(BizException.class, () -> pressService.transfer(p.getId(), "运行", rid("self")));
        PressStation same = pressService.get(p.getId());
        assertEquals("运行", same.getStatus());
        assertEquals(0, same.getCurrentLoadTph().compareTo(new BigDecimal("100.00")));
        assertEquals(maintainAt, same.getLastMaintainAt());
        assertNull(controlRepository.findAll().stream().findAny().orElse(null),
                "失败操作不得留下操作流水");

        // 安全重试：换一个合法意图（转停机）正常生效
        assertEquals("停机", pressService.transfer(p.getId(), "停机", rid("retry-stop")).getStatus());
    }

    @Test
    void maintenance_time_survives_full_cycle_and_refresh() {
        PressStation p = station("PS-T10", "运行", "160.00", "200.00");
        PressStation m = pressService.transfer(p.getId(), "检修", rid("cycle-m"));
        LocalDateTime maintainAt = m.getLastMaintainAt();
        pressService.transfer(p.getId(), "停机", rid("cycle-s"));
        pressService.transfer(p.getId(), "运行", rid("cycle-r"));
        pressService.setLoad(p.getId(), new BigDecimal("90.00"), rid("cycle-l"));

        PressStation reloaded = pressRepository.findById(p.getId()).orElseThrow();
        assertEquals("运行", reloaded.getStatus());
        assertEquals(0, reloaded.getCurrentLoadTph().compareTo(new BigDecimal("90.00")));
        assertEquals(maintainAt, reloaded.getLastMaintainAt(), "检修时间在后续流转后仍可还原");
    }
}
