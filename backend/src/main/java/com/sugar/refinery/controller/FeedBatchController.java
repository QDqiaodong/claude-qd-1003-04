package com.sugar.refinery.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sugar.refinery.common.ApiResponse;
import com.sugar.refinery.entity.FeedBatch;
import com.sugar.refinery.service.FeedBatchService;

/** 投料交接接口：已验收磅单 → 投料批 → 运行中机组的闭环 */
@RestController
@RequestMapping("/api/feed-batches")
public class FeedBatchController {

    private final FeedBatchService feedService;

    public FeedBatchController(FeedBatchService feedService) {
        this.feedService = feedService;
    }

    /** 交接请求：requestId 是幂等键，重复点击 / 超时重试必须带同一个 */
    public record HandoverRequest(Long intakeId, Long stationId, BigDecimal plannedKg, String requestId) {
    }

    @GetMapping
    public ApiResponse<List<FeedBatch>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(feedService.list(status));
    }

    @PostMapping
    public ApiResponse<FeedBatch> handover(@RequestBody HandoverRequest req) {
        try {
            return ApiResponse.success("交接已提交，磅单余量与机组占用已同步扣减",
                    feedService.handover(req.intakeId(), req.stationId(), req.plannedKg(), req.requestId()));
        } catch (DataIntegrityViolationException e) {
            // 并发下同一 requestId 已被另一事务落库：本事务已整体回滚（不留半成品），
            // 直接返回已存在的那笔，保证「同一请求只成功落下一份有效占用」
            Optional<FeedBatch> existing = req.requestId() == null ? Optional.empty()
                    : feedService.findByRequestId(req.requestId());
            if (existing.isPresent()) {
                return ApiResponse.success("交接已提交（重复请求已合并）", existing.get());
            }
            throw e;
        }
    }

    @PostMapping("/{id}/start")
    public ApiResponse<FeedBatch> start(@PathVariable Long id) {
        return ApiResponse.success("已开始投料", feedService.start(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<FeedBatch> cancel(@PathVariable Long id) {
        return ApiResponse.success("已撤回，磅单余量与机组能力已同步归还", feedService.cancel(id));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<FeedBatch> complete(@PathVariable Long id, @RequestParam BigDecimal actualKg) {
        return ApiResponse.success("投料完成，少投差额已释放回两侧余量", feedService.complete(id, actualKg));
    }
}
