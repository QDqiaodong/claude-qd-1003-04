package com.sugar.refinery.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sugar.refinery.common.ApiResponse;
import com.sugar.refinery.common.BizException;
import com.sugar.refinery.entity.PressStation;
import com.sugar.refinery.service.PressStationService;

/** 压榨机组接口：调负荷与运行 / 停机 / 检修状态切换，写操作均按 requestId 幂等 */
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

    /**
     * 调整负荷，超过设计处理量或机组不在运行都会被拦。
     * requestId 是幂等键：同一意图重复点击 / 超时重试必须带同一个。
     */
    @PostMapping("/{id}/load")
    public ApiResponse<PressStation> setLoad(@PathVariable Long id,
                                             @RequestParam BigDecimal loadTph,
                                             @RequestParam String requestId) {
        try {
            return ApiResponse.success("负荷已调整", pressService.setLoad(id, loadTph, requestId));
        } catch (DataIntegrityViolationException e) {
            mergeIfDuplicate(requestId);
            // 并发下同一 requestId 已被另一事务落库：本事务已整体回滚（机组无任何改动），
            // 返回当前机组最新状态，前端与第一次成功的结果对齐，不重复落一条
            return ApiResponse.success("负荷已调整（重复请求已合并）", pressService.get(id));
        }
    }

    /**
     * 状态切换。转检修会同事务把负荷归零并记录检修时间；非法流转返回 400 且原状态不变。
     * requestId 是幂等键：重复点击 / 超时重试只落一条一致结果。
     */
    @PostMapping("/{id}/transfer")
    public ApiResponse<PressStation> transfer(@PathVariable Long id,
                                              @RequestParam String target,
                                              @RequestParam String requestId) {
        try {
            return ApiResponse.success("状态已更新", pressService.transfer(id, target, requestId));
        } catch (DataIntegrityViolationException e) {
            mergeIfDuplicate(requestId);
            // 并发重复提交：另一条相同请求已先提交，本事务整体回滚，
            // 返回机组最新状态（已含第一次切换的结果），值班员重试不会把检修时间再覆盖一遍
            return ApiResponse.success("状态已更新（重复请求已合并）", pressService.get(id));
        }
    }

    /** 若唯一冲突并非来自 requestId 重复（理论上的其他约束冲突），照常抛出走兜底处理 */
    private void mergeIfDuplicate(String requestId) {
        if (requestId == null || requestId.isBlank()
                || pressService.findControl(requestId.trim()).isEmpty()) {
            throw new BizException("存在数据约束冲突，本次操作未生效，请刷新后重试");
        }
    }
}
