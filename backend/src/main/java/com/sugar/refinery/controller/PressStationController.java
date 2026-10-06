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
import com.sugar.refinery.entity.PressStation;
import com.sugar.refinery.service.PressStationService;

/** 压榨机组接口 */
@RestController
@RequestMapping("/api/press-stations")
public class PressStationController {

    private final PressStationService pressService;

    public PressStationController(PressStationService pressService) {
        this.pressService = pressService;
    }

    @GetMapping
    public ApiResponse<List<PressStation>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(pressService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<PressStation> get(@PathVariable Long id) {
        return ApiResponse.success(pressService.get(id));
    }

    @PostMapping
    public ApiResponse<PressStation> create(@RequestBody PressStation input) {
        return ApiResponse.success("机组已建档", pressService.create(input));
    }

    /** 调整负荷，超过设计处理量会被拦 */
    @PostMapping("/{id}/load")
    public ApiResponse<PressStation> setLoad(@PathVariable Long id, @RequestParam BigDecimal loadTph) {
        return ApiResponse.success("负荷已调整", pressService.setLoad(id, loadTph));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<PressStation> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", pressService.transfer(id, target));
    }
}
