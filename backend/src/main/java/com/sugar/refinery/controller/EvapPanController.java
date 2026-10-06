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
import com.sugar.refinery.entity.EvapPan;
import com.sugar.refinery.service.EvapPanService;

/** 蒸发 / 结晶罐接口 */
@RestController
@RequestMapping("/api/evap-pans")
public class EvapPanController {

    private final EvapPanService panService;

    public EvapPanController(EvapPanService panService) {
        this.panService = panService;
    }

    @GetMapping
    public ApiResponse<List<EvapPan>> list(@RequestParam(required = false) String panType,
                                           @RequestParam(required = false) String status) {
        return ApiResponse.success(panService.list(panType, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<EvapPan> get(@PathVariable Long id) {
        return ApiResponse.success(panService.get(id));
    }

    @PostMapping
    public ApiResponse<EvapPan> create(@RequestBody EvapPan input) {
        return ApiResponse.success("罐体已建档", panService.create(input));
    }

    /** 洗罐闭环 */
    @PostMapping("/{id}/wash")
    public ApiResponse<EvapPan> wash(@PathVariable Long id) {
        return ApiResponse.success("洗罐已记录", panService.wash(id));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<EvapPan> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", panService.transfer(id, target));
    }

    @GetMapping("/running-capacity")
    public ApiResponse<BigDecimal> runningCapacity() {
        return ApiResponse.success(panService.runningCapacity());
    }
}
