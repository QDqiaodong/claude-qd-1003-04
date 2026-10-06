package com.sugar.refinery;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.sugar.refinery.entity.PressStation;
import com.sugar.refinery.repository.PressStationRepository;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 压榨机组 HTTP 闭环测试：转检修归零 + 记时间、检修完成回停机、检修中拦截调负荷 /
 * 开机 / 重复检修，非法转换 400 且保留原状态；同一 requestId 重试只生效一次；
 * 刷新（GET）后看到的状态 / 负荷 / 最近检修时间与操作结果一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PressStationWebTest {

    @Autowired private MockMvc mvc;
    @Autowired private PressStationRepository pressRepository;

    private Long runningId;

    /**
     * 数值匹配：BigDecimal.ZERO 序列化为 0（整数），库中读出的 0.00 序列化为 0.00（小数），
     * 按 double 比较两种形态都接受。
     */
    private static org.hamcrest.Matcher<Object> numEq(double expected) {
        return new org.hamcrest.BaseMatcher<>() {
            @Override
            public boolean matches(Object item) {
                return item instanceof Number && ((Number) item).doubleValue() == expected;
            }

            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText("numeric ").appendValue(expected);
            }
        };
    }

    @BeforeEach
    void setUp() {
        pressRepository.deleteAll();
        PressStation p = new PressStation();
        p.setStationCode("PS-W1");
        p.setStationName("Web压榨机组");
        p.setDesignCapacityTph(new BigDecimal("200.00"));
        p.setCurrentLoadTph(new BigDecimal("120.00"));
        p.setOccupiedKg(BigDecimal.ZERO);
        p.setStatus("运行");
        runningId = pressRepository.save(p).getId();
    }

    @Test
    void maintain_flow_zeroes_load_records_time_and_finishes_to_stopped() throws Exception {
        // 运行 → 检修：负荷归零 + 记录本次检修时间
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "检修").param("requestId", "web-m1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("检修"))
                .andExpect(jsonPath("$.data.currentLoadTph").value(numEq(0)))
                .andExpect(jsonPath("$.data.lastMaintainAt").exists());

        // 刷新还原：GET 看到的就是落下的一致状态
        String maintainAt = mvc.perform(get("/api/press-stations/{id}", runningId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("检修"))
                .andExpect(jsonPath("$.data.currentLoadTph").value(numEq(0)))
                .andExpect(jsonPath("$.data.lastMaintainAt").exists())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll(".*?\"lastMaintainAt\":\"([^\"]+)\".*", "$1");

        // 检修中调负荷：400 + 原因，负荷保持 0
        mvc.perform(post("/api/press-stations/{id}/load", runningId).param("loadTph", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("检修")));
        // 检修中开机：400 + 原因（检修完成只能回停机）
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "运行").param("requestId", "web-m2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("不允许从「检修」转为「运行」")));
        // 检修中再次转检修（新键）：400，检修时间不被覆盖
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "检修").param("requestId", "web-m3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("不能重复进入检修")));
        mvc.perform(get("/api/press-stations/{id}", runningId))
                .andExpect(jsonPath("$.data.status").value("检修"))
                .andExpect(jsonPath("$.data.currentLoadTph").value(numEq(0)))
                .andExpect(jsonPath("$.data.lastMaintainAt").value(maintainAt));

        // 检修完成 → 停机；随后可正常开机
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "停机").param("requestId", "web-m4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("停机"))
                .andExpect(jsonPath("$.data.lastMaintainAt").value(maintainAt));
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "运行").param("requestId", "web-m5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("运行"));
    }

    @Test
    void duplicate_request_id_replays_same_result() throws Exception {
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "检修").param("requestId", "web-dup"))
                .andExpect(status().isOk());
        // 以库中落下的检修时间为基准
        String first = mvc.perform(get("/api/press-stations/{id}", runningId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll(".*?\"lastMaintainAt\":\"([^\"]+)\".*", "$1");

        // 超时重试 / 重复点击带同一键：仍成功，且检修时间是第一次落下的值
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "检修").param("requestId", "web-dup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("检修"))
                .andExpect(jsonPath("$.data.lastMaintainAt").value(first));

        // 同一键换目标重提：拒绝并提示核对，状态不被翻动
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "停机").param("requestId", "web-dup"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("已受理")));
        mvc.perform(get("/api/press-stations/{id}", runningId))
                .andExpect(jsonPath("$.data.status").value("检修"))
                .andExpect(jsonPath("$.data.lastMaintainAt").value(first));
    }

    @Test
    void illegal_transfers_keep_original_state() throws Exception {
        // 运行 → 运行 不是合法流转
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "运行").param("requestId", "web-i1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("不允许")));
        // 非法目标值
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "报废").param("requestId", "web-i2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("状态不合法")));
        // 缺少请求标识
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId).param("target", "停机"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("缺少请求标识")));
        // 全部失败后原状态原负荷保留，可安全重试合法动作
        mvc.perform(get("/api/press-stations/{id}", runningId))
                .andExpect(jsonPath("$.data.status").value("运行"))
                .andExpect(jsonPath("$.data.currentLoadTph").value(numEq(120)));
        mvc.perform(post("/api/press-stations/{id}/transfer", runningId)
                        .param("target", "停机").param("requestId", "web-i3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("停机"));
    }

    @Test
    void set_load_over_capacity_rejected_and_list_shows_truth() throws Exception {
        mvc.perform(post("/api/press-stations/{id}/load", runningId).param("loadTph", "250"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("超过设计处理量")));
        // 列表页刷新后看到状态 / 当前负荷 / 最近检修时间字段
        mvc.perform(get("/api/press-stations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("运行"))
                .andExpect(jsonPath("$.data[0].currentLoadTph").value(numEq(120)))
                .andExpect(jsonPath("$.data[0].loadRate").exists());
    }
}
