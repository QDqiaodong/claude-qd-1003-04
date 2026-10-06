package com.sugar.refinery.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sugar.refinery.common.ApiResponse;
import com.sugar.refinery.common.BizException;
import com.sugar.refinery.dto.WashQueueItem;
import com.sugar.refinery.entity.PanWash;
import com.sugar.refinery.service.PanWashService;

/**
 * 洗罐排程接口：待洗清单 + 待执行 → 清洗中 → 已完成 的闭环。
 *
 * <p>开始 / 完成 / 一键清洗失败时，主事务已整体回滚，再由
 * {@link PanWashService#recordFailure} 独立落一笔失败原因——排程状态不变，
 * 值班员刷新后仍能在对应安排上看到「可否重试」。</p>
 */
@RestController
@RequestMapping("/api/pan-washes")
public class PanWashController {

    private final PanWashService washService;

    public PanWashController(PanWashService washService) {
        this.washService = washService;
    }

    /** 待洗清单：可按罐型、罐体当前状态过滤，dueOnly=1 只看需先洗罐 */
    @GetMapping("/queue")
    public ApiResponse<List<WashQueueItem>> queue(@RequestParam(required = false) String panType,
                                                  @RequestParam(required = false) String panStatus,
                                                  @RequestParam(required = false, defaultValue = "false")
                                                      boolean dueOnly) {
        return ApiResponse.success(washService.queue(panType, panStatus, dueOnly));
    }

    /** 洗罐安排看板（默认按 id 倒序） */
    @GetMapping
    public ApiResponse<List<PanWash>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(washService.list(status));
    }

    /** 为罐安排洗罐窗口（windowAt 格式 yyyy-MM-ddTHH:mm:ss，与列表里的时间串一致） */
    @PostMapping
    public ApiResponse<PanWash> schedule(
            @RequestParam Long panId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime windowAt) {
        return ApiResponse.success("洗罐窗口已安排", washService.schedule(panId, windowAt));
    }

    /** 开始清洗：待执行 → 清洗中 */
    @PostMapping("/{id}/start")
    public ApiResponse<PanWash> start(@PathVariable Long id) {
        try {
            return ApiResponse.success("已开始清洗", washService.start(id));
        } catch (BizException e) {
            washService.recordFailure(id, e.getMessage());
            throw e;
        }
    }

    /** 取消：仅待执行可取消 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<PanWash> cancel(@PathVariable Long id) {
        return ApiResponse.success("安排已取消", washService.cancel(id));
    }

    /** 完成：清洗中 → 已完成，写完成时间并恢复罐体运行资格 */
    @PostMapping("/{id}/complete")
    public ApiResponse<PanWash> complete(@PathVariable Long id) {
        try {
            return ApiResponse.success("洗罐完成，已写入洗罐时间并恢复运行资格", washService.complete(id));
        } catch (BizException e) {
            washService.recordFailure(id, e.getMessage());
            throw e;
        }
    }

    /** 一键清洗：待执行 / 清洗中 → 已完成，一笔事务走完 */
    @PostMapping("/{id}/execute")
    public ApiResponse<PanWash> execute(@PathVariable Long id) {
        try {
            return ApiResponse.success("洗罐完成，已写入洗罐时间并恢复运行资格", washService.execute(id));
        } catch (BizException e) {
            washService.recordFailure(id, e.getMessage());
            throw e;
        }
    }
}
