package com.sugar.refinery;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.sugar.refinery.composite.JuiceQuality;
import com.sugar.refinery.entity.CaneIntake;
import com.sugar.refinery.repository.CaneIntakeRepository;
import com.sugar.refinery.service.CaneIntakeService;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 甘蔗进厂磅单筛选 HTTP 测试。
 *
 * <p>覆盖：验收状态 / 品质结论单条件查询、双条件按「同时满足」组合、
 * 合法空结果保持空态（不回退为全部磅单）、空串与非法条件的口径，
 * 以及「今日进厂总量」按全厂当日有效磅单统计、不被列表条件缩小。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CaneIntakeWebTest {

    @Autowired private MockMvc mvc;
    @Autowired private CaneIntakeService intakeService;
    @Autowired private CaneIntakeRepository intakeRepository;

    @BeforeEach
    void setUp() {
        intakeRepository.deleteAll();
    }

    @Test
    void filters_apply_singly_and_combined_with_and_semantics() throws Exception {
        CaneIntake acceptedPass = create("CI-F-01", "13.50", "12.00");
        intakeService.accept(acceptedPass.getId());                     // 已验收 / 合格
        create("CI-F-02", "13.20", "12.50");                            // 待验 / 合格
        CaneIntake acceptedDeduction = create("CI-F-03", "13.00", "16.20");
        intakeService.accept(acceptedDeduction.getId());                // 已验收 / 扣杂
        CaneIntake rejected = create("CI-F-04", "10.10", "12.00");
        intakeService.accept(rejected.getId());                         // 已拒收 / 拒收

        // 单条件：验收状态
        mvc.perform(get("/api/cane-intakes").param("status", "已验收"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].ticketNo", containsInAnyOrder("CI-F-01", "CI-F-03")))
                .andExpect(jsonPath("$.data[*].status", everyItem(is("已验收"))));

        // 单条件：品质结论
        mvc.perform(get("/api/cane-intakes").param("result", "合格"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].ticketNo", containsInAnyOrder("CI-F-01", "CI-F-02")))
                .andExpect(jsonPath("$.data[*].result", everyItem(is("合格"))));

        // 双条件：同时满足（交集），不能退化成只应用其中一个
        mvc.perform(get("/api/cane-intakes").param("status", "已验收").param("result", "合格"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].ticketNo").value("CI-F-01"))
                .andExpect(jsonPath("$.data[0].status").value("已验收"))
                .andExpect(jsonPath("$.data[0].result").value("合格"));

        // 合法空结果：保持空态，不能混入未命中的磅单
        mvc.perform(get("/api/cane-intakes").param("status", "待验").param("result", "扣杂"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // 空串条件视为未选择，不参与限制
        mvc.perform(get("/api/cane-intakes").param("status", "").param("result", "合格"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].ticketNo", containsInAnyOrder("CI-F-01", "CI-F-02")));

        // 无条件：全部磅单
        mvc.perform(get("/api/cane-intakes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4));

        // 非法条件值：明确报错，而不是静默忽略条件返回全部
        mvc.perform(get("/api/cane-intakes").param("status", "不存在的状态"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("验收状态不合法")));
        mvc.perform(get("/api/cane-intakes").param("result", "不存在的结论"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("品质结论不合法")));
    }

    @Test
    void daily_ton_counts_all_valid_weighbills_not_narrowed_by_list_filter() throws Exception {
        CaneIntake accepted = create("CI-D-01", "13.50", "12.00");
        intakeService.accept(accepted.getId());                         // 已验收，17.60 吨
        create("CI-D-02", "13.20", "12.50");                            // 待验，17.60 吨
        CaneIntake rejected = create("CI-D-03", "10.10", "12.00");
        intakeService.accept(rejected.getId());                         // 已拒收，不计入

        // 列表按条件过滤后只剩 1 张……
        mvc.perform(get("/api/cane-intakes").param("status", "已验收"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // ……今日进厂总量仍按全厂当日有效（非已拒收）磅单统计：17.60 + 17.60 = 35.20 吨
        mvc.perform(get("/api/cane-intakes/daily-ton"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(35.20));
    }

    /** 登记一张今日磅单：毛重 30000 - 皮重 11800 - 扣杂 600 = 净重 17600 千克（17.60 吨） */
    private CaneIntake create(String ticketNo, String brix, String fiber) {
        CaneIntake input = new CaneIntake();
        input.setTicketNo(ticketNo);
        input.setSupplier("测试蔗区");
        input.setTruckNo("桂T00000");
        input.setGrossKg(new BigDecimal("30000.00"));
        input.setTareKg(new BigDecimal("11800.00"));
        input.setDeductionKg(new BigDecimal("600.00"));
        input.setQuality(new JuiceQuality(new BigDecimal(brix), new BigDecimal("86.00"), new BigDecimal(fiber)));
        return intakeService.create(input);
    }
}
