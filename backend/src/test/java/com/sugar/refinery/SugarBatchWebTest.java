package com.sugar.refinery;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sugar.refinery.composite.JuiceQuality;
import com.sugar.refinery.entity.SugarBatch;
import com.sugar.refinery.repository.SugarBatchRepository;
import com.sugar.refinery.repository.SugarQualityInspectionRepository;
import com.sugar.refinery.service.SugarBatchService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 成品糖复检与发运锁定 HTTP 接口测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SugarBatchWebTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SugarBatchService batchService;
    @Autowired private SugarBatchRepository batchRepository;
    @Autowired private SugarQualityInspectionRepository inspectionRepository;

    @BeforeEach
    void setUp() {
        inspectionRepository.deleteAll();
        batchRepository.deleteAll();
    }

    @Test
    void api_exposes_current_inspection_and_enforces_lock() throws Exception {
        SugarBatch batch = create("SB-WEB-01");

        mvc.perform(get("/api/sugar-batches/{id}", batch.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("待复检"))
                .andExpect(jsonPath("$.data.currentVersion").value(0))
                .andExpect(jsonPath("$.data.inspectionStatus").value("待复检"))
                .andExpect(jsonPath("$.data.shipmentEligible").value(false))
                .andExpect(jsonPath("$.data.storeEligible").value(false))
                .andExpect(jsonPath("$.data.lastInspectedAt").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(post("/api/sugar-batches/{id}/store", batch.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("尚未复检")));

        String requestId = "req-web-pass";
        mvc.perform(post("/api/sugar-batches/{id}/inspections", batch.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionJson(0, requestId, "通过", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value(1))
                .andExpect(jsonPath("$.data.inspectionStatus").value("通过"))
                .andExpect(jsonPath("$.data.storeEligible").value(true))
                .andExpect(jsonPath("$.data.shipmentEligible").value(false));

        // 完全相同请求重试：接口成功返回同一份结果，不新增版本
        mvc.perform(post("/api/sugar-batches/{id}/inspections", batch.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionJson(0, requestId, "通过", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value(1));

        mvc.perform(post("/api/sugar-batches/{id}/store", batch.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已入库"))
                .andExpect(jsonPath("$.data.shipmentEligible").value(true));

        // 入库后旧基准版本提交失败
        mvc.perform(post("/api/sugar-batches/{id}/inspections", batch.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionJson(0, "req-web-old", "不通过", "旧页面回写")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("基准版本已过期")));

        // 当前版本重检不通过，发运按钮对应资格立即变 false；再发运被锁
        mvc.perform(post("/api/sugar-batches/{id}/inspections", batch.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionJson(1, "req-web-fail", "不通过", "复检发现结块")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value(2))
                .andExpect(jsonPath("$.data.inspectionStatus").value("不通过"))
                .andExpect(jsonPath("$.data.rejectReason").value("复检发现结块"))
                .andExpect(jsonPath("$.data.shipmentEligible").value(false));

        mvc.perform(post("/api/sugar-batches/{id}/ship", batch.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("发运资格已锁定")));

        mvc.perform(get("/api/sugar-batches").param("inspectionStatus", "不通过"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(batch.getId()))
                .andExpect(jsonPath("$.data[0].inspectionStatus").value("不通过"));

        mvc.perform(get("/api/sugar-batches/{id}/inspections", batch.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[1].version").value(2))
                .andExpect(jsonPath("$.data[1].rejectReason").value("复检发现结块"));
    }

    @Test
    void reject_requires_reason_and_stock_uses_three_buckets() throws Exception {
        SugarBatch waiting = create("SB-WEB-02");
        SugarBatch shippable = create("SB-WEB-03");
        SugarBatch shipped = create("SB-WEB-04");

        mvc.perform(post("/api/sugar-batches/{id}/inspections", waiting.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectionJson(0, "req-web-no-reason", "不通过", "  ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不通过时必须填写原因")));

        batchService.inspect(shippable.getId(), serviceInspection(0, "req-web-shippable", "通过", null));
        batchService.store(shippable.getId());
        batchService.inspect(shipped.getId(), serviceInspection(0, "req-web-shipped-pass", "通过", null));
        batchService.store(shipped.getId());
        batchService.ship(shipped.getId());

        mvc.perform(get("/api/sugar-batches/stock-by-warehouse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].warehouse").value("1号糖仓"))
                .andExpect(jsonPath("$.data[0].pendingInspectionTon").value(10.00))
                .andExpect(jsonPath("$.data[0].shippableTon").value(10.00))
                .andExpect(jsonPath("$.data[0].shippedTon").value(10.00));
    }

    private SugarBatch create(String no) {
        SugarBatch input = new SugarBatch();
        input.setBatchNo(no);
        input.setGrade("白砂糖");
        input.setQuantityKg(new BigDecimal("10000.00"));
        input.setWarehouse("1号糖仓");
        input.setQuality(new JuiceQuality(new BigDecimal("99.80"), new BigDecimal("99.60"), BigDecimal.ZERO));
        return batchService.create(input);
    }

    private String inspectionJson(int baseVersion, String requestId, String conclusion, String reason) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("baseVersion", baseVersion);
        node.put("requestId", requestId);
        node.put("inspectedAt", LocalDateTime.now().toString());
        node.put("brix", new BigDecimal("99.82"));
        node.put("purity", new BigDecimal("99.63"));
        node.put("fiber", new BigDecimal("0.01"));
        node.put("conclusion", conclusion);
        node.put("rejectReason", reason);
        return objectMapper.writeValueAsString(node);
    }

    private com.sugar.refinery.dto.SugarInspectionRequest serviceInspection(
            int baseVersion, String requestId, String conclusion, String reason) {
        com.sugar.refinery.dto.SugarInspectionRequest request = new com.sugar.refinery.dto.SugarInspectionRequest();
        request.setBaseVersion(baseVersion);
        request.setRequestId(requestId);
        request.setInspectedAt(LocalDateTime.now());
        request.setBrix(new BigDecimal("99.82"));
        request.setPurity(new BigDecimal("99.63"));
        request.setFiber(new BigDecimal("0.01"));
        request.setConclusion(conclusion);
        request.setRejectReason(reason);
        return request;
    }
}
