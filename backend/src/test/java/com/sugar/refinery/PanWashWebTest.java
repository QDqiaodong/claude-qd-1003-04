package com.sugar.refinery;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.sugar.refinery.entity.EvapPan;
import com.sugar.refinery.repository.EvapPanRepository;
import com.sugar.refinery.repository.PanWashRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 洗罐排程 HTTP 闭环测试：待洗清单对齐、落单 / 开始 / 完成的联动、
 * 失败时控制器兜底落「可重试」原因而状态不变。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PanWashWebTest {

    @Autowired private MockMvc mvc;
    @Autowired private PanWashRepository washRepository;
    @Autowired private EvapPanRepository panRepository;

    private Long duePanId;

    @BeforeEach
    void setUp() {
        washRepository.deleteAll();
        panRepository.deleteAll();
        EvapPan p = new EvapPan();
        p.setPanCode("EP-W1");
        p.setPanName("Web到期罐");
        p.setPanType("蒸发罐");
        p.setCapacityM3(new BigDecimal("90.00"));
        p.setStatus("运行");
        p.setLastWashAt(LocalDateTime.now().minusHours(90));
        duePanId = panRepository.save(p).getId();
    }

    @Test
    void queue_shows_due_flag_and_schedule_flow_works() throws Exception {
        mvc.perform(get("/api/pan-washes/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].washDue").value(true))
                .andExpect(jsonPath("$.data[0].operable").value(false))
                .andExpect(jsonPath("$.data[0].activeWash").doesNotExist());

        String washId = mvc.perform(post("/api/pan-washes")
                        .param("panId", String.valueOf(duePanId))
                        .param("windowAt", "2026-10-06T09:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("待执行"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll(".*?\"id\":(\\d+).*", "$1");

        // 清单上挂出未完成安排与窗口
        mvc.perform(get("/api/pan-washes/queue").param("dueOnly", "true"))
                .andExpect(jsonPath("$.data[0].activeWash.washStatus").value("待执行"))
                .andExpect(jsonPath("$.data[0].activeWash.windowAt").value("2026-10-06T09:00:00"));

        mvc.perform(post("/api/pan-washes/{id}/start", washId))
                .andExpect(jsonPath("$.data.status").value("清洗中"));
        // 罐已联动转洗罐
        mvc.perform(get("/api/evap-pans/{id}", duePanId))
                .andExpect(jsonPath("$.data.status").value("洗罐"));

        mvc.perform(post("/api/pan-washes/{id}/complete", washId))
                .andExpect(jsonPath("$.data.status").value("已完成"))
                .andExpect(jsonPath("$.data.completedAt").exists());
        // 同事务恢复运行资格、回写洗罐时间
        mvc.perform(get("/api/evap-pans/{id}", duePanId))
                .andExpect(jsonPath("$.data.status").value("运行"))
                .andExpect(jsonPath("$.data.lastWashAt").exists())
                .andExpect(jsonPath("$.data.operable").value(true));
    }

    @Test
    void failed_complete_records_retryable_reason_but_keeps_status() throws Exception {
        String washId = mvc.perform(post("/api/pan-washes")
                        .param("panId", String.valueOf(duePanId))
                        .param("windowAt", "2026-10-06T10:00:00"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).replaceAll(".*?\"id\":(\\d+).*", "$1");
        mvc.perform(post("/api/pan-washes/{id}/start", washId)).andExpect(status().isOk());

        // 把罐手动改成运行（制造完成前提不满足）；complete 主事务回滚 + 控制器落失败原因
        EvapPan p = panRepository.findById(duePanId).orElseThrow();
        p.setStatus("运行");
        panRepository.saveAndFlush(p);

        mvc.perform(post("/api/pan-washes/{id}/complete", washId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("无法完成洗罐")));

        // 排程仍是清洗中（可安全重试），失败原因已落库
        mvc.perform(get("/api/pan-washes"))
                .andExpect(jsonPath("$.data[0].status").value("清洗中"))
                .andExpect(jsonPath("$.data[0].completedAt").doesNotExist())
                .andExpect(jsonPath("$.data[0].failedReason").exists())
                .andExpect(jsonPath("$.data[0].retryable").value(true));
        // 清单行内同样能看到失败可重试
        mvc.perform(get("/api/pan-washes/queue"))
                .andExpect(jsonPath("$.data[0].activeWash.retryable").value(true));
    }

    @Test
    void stopped_pan_schedule_rejected() throws Exception {
        EvapPan stopped = new EvapPan();
        stopped.setPanCode("EP-W2");
        stopped.setPanName("Web停用罐");
        stopped.setPanType("结晶罐");
        stopped.setCapacityM3(new BigDecimal("60.00"));
        stopped.setStatus("停用");
        stopped.setLastWashAt(LocalDateTime.now().minusHours(200));
        Long id = panRepository.save(stopped).getId();

        mvc.perform(post("/api/pan-washes")
                        .param("panId", String.valueOf(id))
                        .param("windowAt", "2026-10-06T11:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("停用中")));
    }
}
