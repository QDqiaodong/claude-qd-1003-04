package com.sugar.refinery.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sugar.refinery.common.ApiResponse;
import com.sugar.refinery.entity.CaneIntake;
import com.sugar.refinery.service.CaneIntakeService;

/** 甘蔗进厂验收接口（品质三指标以 record 组件整体收发） */
@RestController
@RequestMapping("/api/cane-intakes")
public class CaneIntakeController {

    private final CaneIntakeService intakeService;

    public CaneIntakeController(CaneIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @GetMapping
    public ApiResponse<List<CaneIntake>> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String result) {
        return ApiResponse.success(intakeService.list(status, result));
    }

    @GetMapping("/{id}")
    public ApiResponse<CaneIntake> get(@PathVariable Long id) {
        return ApiResponse.success(intakeService.get(id));
    }

    @PostMapping
    public ApiResponse<CaneIntake> create(@RequestBody CaneIntake input) {
        return ApiResponse.success("磅单已登记", intakeService.create(input));
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<CaneIntake> accept(@PathVariable Long id) {
        return ApiResponse.success("验收完成", intakeService.accept(id));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<CaneIntake> reject(@PathVariable Long id, @RequestParam String reason) {
        return ApiResponse.success("已拒收", intakeService.reject(id, reason));
    }

    @GetMapping("/daily-ton")
    public ApiResponse<BigDecimal> dailyTon() {
        return ApiResponse.success(intakeService.dailyIntakeTon());
    }
}
