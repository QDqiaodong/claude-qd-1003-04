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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.sugar.refinery.entity.PressStation;
import com.sugar.refinery.repository.PressControlRepository;
import com.sugar.refinery.repository.PressStationRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 压榨机组 HTTP 闭环测试。
 *
 * <p>覆盖：转检修联动（状态 + 负荷归零 + 检修时间）、非法转换 400 且状态保留、
 * requestId 缺失被拦、重复 / 超时重试请求合并、并发双击只落一条一致结果、
 * 刷新后 GET 与最近一次操作结果一致。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PressStationWebTest {

    @Autowired private MockMvc mvc;
    @Autowired private PressStationRepository pressRepository;
    @Autowired private PressControlRepository controlRepository;

    private Long runningId;
    private Long stoppedId;
    private Long maintainId;

    @BeforeEach
    void setUp() {
        controlRepository.deleteAll();
        pressRepository.deleteAll();
        runningId = save("PS-W1", "运行", "180.00", "200.00");
        stoppedId = save("PS-W2", "停机", "0.00", "180.00");
        maintainId = save("PS-W3", "检修", "0.00", "160.00");
    }

    private Long save(String code, String status, String load, String capacity) {
        PressStation p = new PressStation();
        p.setStationCode(code);
        p.setStationName(code + "机组");
        p.setDesignCapacityTph(new BigDecimal(capacity));
        p.setCurrentLoadTph(new BigDecimal(load));
        p.setOccupiedKg(BigDecimal.ZERO);
        p.setStatus(status);
        p.setLastMaintainAt(LocalDateTime.now().minusDays(10));
        return pressRepository.save(p).getId();
    }

    @Test
    void transfer_to_maintenance_zeroes_load_and_returns_consistent_state() throws Exception {
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "检修").param("requestId", "web-maint-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.status").value("检修"))
                .andExpect(jsonPath("$.data.currentLoadTph").value(0))
                .andExpect(jsonPath("$.data.lastMaintainAt").exists());

        // 刷新还原：GET 与切换响应完全一致
        mvc.perform(get("/api/press-stations/{id}", runningId))
                .andExpect(jsonPath("$.data.status").value("检修"))
                .andExpect(jsonPath("$.data.currentLoadTph").value(0));

        // 检修完成只能回停机
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "停机").param("requestId", "web-finish-1"))
                .andExpect(jsonPath("$.data.status").value("停机"))
                .andExpect(jsonPath("$.data.currentLoadTph").value(0));
    }

    @Test
    void setload_requires_running_and_respects_capacity() throws Exception {
        mvc.perform(post("/api/press-stations/{id}/load", runningId)
                        .param("loadTph", "190").param("requestId", "web-load-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentLoadTph").value(190));

        // 超载 400
        mvc.perform(post("/api/press-stations/{id}/load", runningId)
                        .param("loadTph", "201").param("requestId", "web-load-over"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超过设计处理量")));

        // 停机机组调负荷 400
        mvc.perform(post("/api/press-stations/{id}/load", stoppedId)
                        .param("loadTph", "10").param("requestId", "web-load-stopped"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("只有「运行」")));
    }

    @Test
    void maintenance_forbidden_actions_return_400_and_keep_status() throws Exception {
        mvc.perform(post("/api/press-stations/{id}/transfer", maintainId)
                        .param("target", "运行").param("requestId", "web-start-x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不能直接开机")));

        mvc.perform(post("/api/press-stations/{id}/transfer", maintainId)
                        .param("target", "检修").param("requestId", "web-maint-x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不能重复进入检修")));

        mvc.perform(post("/api/press-stations/{id}/load", maintainId)
                        .param("loadTph", "10").param("requestId", "web-load-x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("只有「运行」")));

        // 原状态保留：仍是检修、负荷 0
        mvc.perform(get("/api/press-stations/{id}", maintainId))
                .andExpect(jsonPath("$.data.status").value("检修"))
                .andExpect(jsonPath("$.data.currentLoadTph").value(0));
    }

    @Test
    void missing_request_id_is_rejected() throws Exception {
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId).param("target", "停机"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/press-stations/{id}/load", runningId).param("loadTph", "10"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicate_request_is_merged_and_maintain_time_not_overwritten() throws Exception {
        String rid = "web-dup-maint";
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "检修").param("requestId", rid))
                .andExpect(status().isOk());
        String firstBody = mvc.perform(get("/api/press-stations/{id}", runningId))
                .andReturn().getResponse().getContentAsString();
        String firstTime = firstBody.replaceAll(".*\"lastMaintainAt\":\"([^\"]+)\".*", "$1");
        long rowsAfterFirst = controlRepository.count();

        Thread.sleep(1100);

        // 超时重试 / 重复点击：同键再发一次，成功合并，检修时间不变
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "检修").param("requestId", rid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("检修"))
                .andExpect(jsonPath("$.data.currentLoadTph").value(0))
                .andExpect(jsonPath("$.data.lastMaintainAt").value(firstTime));
        assertEquals(rowsAfterFirst, controlRepository.count(), "重复请求不得新增操作流水");
    }

    @Test
    void concurrent_duplicate_clicks_over_http_land_single_consistent_result() throws Exception {
        // 模拟双击：两个完全相同的转检修请求（同一 requestId）同时打到接口
        String rid = "web-concurrent-maint";
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger bad = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    MvcResult r = mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                                    .param("target", "检修").param("requestId", rid))
                            .andReturn();
                    if (r.getResponse().getStatus() == 200) {
                        ok.incrementAndGet();
                    } else {
                        bad.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        // 控制器兜底：并发重复请求全部拿到成功响应（合并到第一次的结果）
        assertEquals(threads, ok.get() + bad.get());
        assertEquals(0, bad.get(), "重复请求应被合并为成功响应，而不是抛给值班员报错");

        mvc.perform(get("/api/press-stations/{id}", runningId))
                .andExpect(jsonPath("$.data.status").value("检修"))
                .andExpect(jsonPath("$.data.currentLoadTph").value(0))
                .andExpect(jsonPath("$.data.lastMaintainAt").exists());
        assertEquals(1, controlRepository.findAll().stream()
                .filter(c -> rid.equals(c.getRequestId())).count(), "同一意图只落一条流水");
    }

    @Test
    void concurrent_load_and_maintenance_over_http_never_leave_load_in_maintenance() throws Exception {
        for (int round = 0; round < 5; round++) {
            Long id = save("PS-CW" + round, "运行", "100.00", "200.00");
            String loadRid = "web-c-load-" + round;
            String maintRid = "web-c-maint-" + round;
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            pool.submit(() -> {
                try {
                    start.await();
                    mvc.perform(post("/api/press-stations/{id}/load", id)
                            .param("loadTph", "190").param("requestId", loadRid));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    mvc.perform(post("/api/press-stations/{id}/transfer", id)
                            .param("target", "检修").param("requestId", maintRid));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

            PressStation row = pressRepository.findById(id).orElseThrow();
            if ("检修".equals(row.getStatus())) {
                assertEquals(0, row.getCurrentLoadTph().compareTo(BigDecimal.ZERO),
                        "第 " + round + " 轮：不得出现「已检修但负荷仍在」");
            } else {
                assertEquals("运行", row.getStatus());
            }
        }
    }
}
