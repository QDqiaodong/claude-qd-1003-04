package com.sugar.refinery.controller;

import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sugar.refinery.common.ApiResponse;
import com.sugar.refinery.dto.SugarInspectionRequest;
import com.sugar.refinery.entity.SugarBatch;
import com.sugar.refinery.entity.SugarQualityInspection;
import com.sugar.refinery.service.SugarBatchService;

import jakarta.validation.Valid;

/** 成品糖批次接口 */
@RestController
@RequestMapping("/api/sugar-batches")
public class SugarBatchController {

    private final SugarBatchService batchService;

    public SugarBatchController(SugarBatchService batchService) {
        this.batchService = batchService;
    }

    @GetMapping
    public ApiResponse<List<SugarBatch>> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String grade,
                                              @RequestParam(required = false) String warehouse,
                                              @RequestParam(required = false) String inspectionStatus) {
        return ApiResponse.success(batchService.list(status, grade, warehouse, inspectionStatus));
    }

    @GetMapping("/{id}")
    public ApiResponse<SugarBatch> get(@PathVariable Long id) {
        return ApiResponse.success(batchService.get(id));
    }

    @GetMapping("/{id}/inspections")
    public ApiResponse<List<SugarQualityInspection>> inspections(@PathVariable Long id) {
        return ApiResponse.success(batchService.inspections(id));
    }

    @PostMapping
    public ApiResponse<SugarBatch> create(@RequestBody SugarBatch input) {
        return ApiResponse.success("批次已建立，等待质量复检", batchService.create(input));
    }

    @PostMapping("/{id}/inspections")
    public ApiResponse<SugarBatch> inspect(@PathVariable Long id,
                                           @Valid @RequestBody SugarInspectionRequest input) {
        SugarBatch batch;
        try {
            batch = batchService.inspect(id, input);
        } catch (DataIntegrityViolationException e) {
            // 并发下唯一索引兜底：事务回滚后按幂等键读回已落的那笔，绝不返回第二份记录
            SugarQualityInspection existing = batchService.getInspectionByRequestId(input.getRequestId());
            if (existing != null && existing.getBatchId().equals(id)) {
                batch = batchService.get(id);
            } else {
                throw e;
            }
        }
        String message = "通过".equals(input.getConclusion()) ? "复检通过，已解锁入库" : "复检不通过，原因已保留";
        return ApiResponse.success(message, batch);
    }

    @PostMapping("/{id}/store")
    public ApiResponse<SugarBatch> store(@PathVariable Long id) {
        return ApiResponse.success("已入库", batchService.store(id));
    }

    @PostMapping("/{id}/ship")
    public ApiResponse<SugarBatch> ship(@PathVariable Long id) {
        return ApiResponse.success("已发运", batchService.ship(id));
    }

    @GetMapping("/stock-by-warehouse")
    public ApiResponse<List<Map<String, Object>>> stockByWarehouse() {
        return ApiResponse.success(batchService.stockByWarehouse());
    }
}
