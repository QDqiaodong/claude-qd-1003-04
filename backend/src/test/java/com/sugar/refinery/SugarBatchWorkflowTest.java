package com.sugar.refinery;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.composite.JuiceQuality;
import com.sugar.refinery.dto.SugarInspectionRequest;
import com.sugar.refinery.entity.SugarBatch;
import com.sugar.refinery.entity.SugarQualityInspection;
import com.sugar.refinery.repository.SugarBatchRepository;
import com.sugar.refinery.repository.SugarQualityInspectionRepository;
import com.sugar.refinery.service.SugarBatchService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成品糖质量复检与发运锁定闭环测试。
 *
 * <p>覆盖：新批次待复检、复检通过才允许入库 / 发运、不通过原因保留、重复提交幂等、
 * 旧版本不能回写、入库 / 发运后重新复检生成新版本并保留历史、非法状态推进、
 * 糖仓三口径汇总以及数据库唯一约束兜底。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SugarBatchWorkflowTest {

    @Autowired private SugarBatchService batchService;
    @Autowired private SugarBatchRepository batchRepository;
    @Autowired private SugarQualityInspectionRepository inspectionRepository;

    @BeforeEach
    void setUp() {
        inspectionRepository.deleteAll();
        batchRepository.deleteAll();
    }

    @Test
    void inspection_controls_store_and_shipment_and_keeps_version_history() {
        SugarBatch batch = createBatch("SB-FLOW-01", "1号糖仓", "10000.00");
        assertEquals("待复检", batch.getStatus());
        assertEquals(0, batch.getCurrentVersion());
        assertEquals("待复检", batch.getInspectionStatus());
        assertNull(batch.getLastInspectedAt());
        assertFalse(batch.isStoreEligible());
        assertFalse(batch.isShipmentEligible());

        // 未复检不能入库；状态机也不允许跳到发运
        assertThrows(BizException.class, () -> batchService.store(batch.getId()));
        assertThrows(BizException.class, () -> batchService.ship(batch.getId()));

        SugarBatch passed = batchService.inspect(batch.getId(),
                inspection(0, "req-flow-pass-1", "通过", null));
        assertEquals(1, passed.getCurrentVersion());
        assertEquals("通过", passed.getInspectionStatus());
        assertNotNull(passed.getLastInspectedAt());
        assertTrue(passed.isStoreEligible());
        assertFalse(passed.isShipmentEligible());

        SugarBatch stored = batchService.store(batch.getId());
        assertEquals("已入库", stored.getStatus());
        assertTrue(stored.isShipmentEligible());

        // 入库后再次复检不通过：状态保持已入库，但发运资格立即锁定
        SugarBatch failed = batchService.inspect(batch.getId(),
                inspection(1, "req-flow-fail-2", "不通过", "复检色值超标"));
        assertEquals(2, failed.getCurrentVersion());
        assertEquals("不通过", failed.getInspectionStatus());
        assertEquals("复检色值超标", failed.getRejectReason());
        assertEquals("已入库", failed.getStatus());
        assertFalse(failed.isShipmentEligible());
        assertThrows(BizException.class, () -> batchService.ship(batch.getId()));

        // 重新复检通过生成 V3，历史 V1/V2 均保留
        SugarBatch repassed = batchService.inspect(batch.getId(),
                inspection(2, "req-flow-pass-3", "通过", null));
        assertEquals(3, repassed.getCurrentVersion());
        assertEquals("通过", repassed.getInspectionStatus());
        assertTrue(repassed.isShipmentEligible());
        SugarBatch shipped = batchService.ship(batch.getId());
        assertEquals("已发运", shipped.getStatus());

        List<SugarQualityInspection> history = inspectionRepository.findByBatchIdOrderByVersionAsc(batch.getId());
        assertEquals(List.of(1, 2, 3), history.stream().map(SugarQualityInspection::getVersion).toList());
        assertEquals(List.of("通过", "不通过", "通过"),
                history.stream().map(SugarQualityInspection::getConclusion).toList());
        assertEquals(3, batchRepository.findById(batch.getId()).orElseThrow().getCurrentVersion());
    }

    @Test
    void duplicate_submission_and_retry_keep_only_one_valid_record() {
        SugarBatch batch = createBatch("SB-IDEM-01", "1号糖仓", "12000.00");
        SugarInspectionRequest first = inspection(0, "req-idem-only-one", "通过", null);

        SugarBatch one = batchService.inspect(batch.getId(), first);
        SugarBatch retry = batchService.inspect(batch.getId(), first);

        assertEquals(1, one.getCurrentVersion());
        assertEquals(1, retry.getCurrentVersion());
        assertEquals(1, inspectionRepository.findByBatchIdOrderByVersionAsc(batch.getId()).size());
        SugarQualityInspection saved = inspectionRepository.findByRequestId("req-idem-only-one").orElseThrow();
        assertEquals(batch.getId(), saved.getBatchId());

        SugarInspectionRequest changed = inspection(0, "req-idem-only-one", "不通过", "同一幂等键换内容");
        assertThrows(BizException.class, () -> batchService.inspect(batch.getId(), changed));
        assertEquals(1, inspectionRepository.findByBatchIdOrderByVersionAsc(batch.getId()).size());
        assertEquals("通过", inspectionRepository.findByRequestId("req-idem-only-one").orElseThrow().getConclusion());
    }

    @Test
    void old_version_cannot_write_and_failed_validation_leaves_no_orphan() {
        SugarBatch batch = createBatch("SB-VERSION-01", "1号糖仓", "13000.00");
        batchService.inspect(batch.getId(), inspection(0, "req-version-v1", "通过", null));

        // 页面还停在 V0 时提交：必须拒绝，不能插入 V2，也不能改动当前版本
        assertThrows(BizException.class, () -> batchService.inspect(batch.getId(),
                inspection(0, "req-version-old", "不通过", "旧页面结果")));
        SugarBatch unchanged = batchService.get(batch.getId());
        assertEquals(1, unchanged.getCurrentVersion());
        assertEquals("通过", unchanged.getInspectionStatus());
        assertEquals(1, inspectionRepository.findByBatchIdOrderByVersionAsc(batch.getId()).size());

        // 不通过没有原因：整次操作回滚，不留复检记录、批次仍停在 V1
        assertThrows(BizException.class, () -> batchService.inspect(batch.getId(),
                inspection(1, "req-version-invalid", "不通过", "  ")));
        assertEquals(1, inspectionRepository.findByBatchIdOrderByVersionAsc(batch.getId()).size());
        assertEquals(1, batchRepository.findById(batch.getId()).orElseThrow().getCurrentVersion());

        // 指标越界同样在落 INSERT 前回滚，不能产生孤立复检记录
        SugarInspectionRequest outOfRange = inspection(1, "req-version-range", "通过", null);
        outOfRange.setBrix(new BigDecimal("101.00"));
        assertThrows(BizException.class, () -> batchService.inspect(batch.getId(), outOfRange));
        assertEquals(1, inspectionRepository.findByBatchIdOrderByVersionAsc(batch.getId()).size());
        assertEquals(1, batchRepository.findById(batch.getId()).orElseThrow().getCurrentVersion());
    }

    @Test
    void illegal_status_transitions_are_blocked() {
        SugarBatch pending = createBatch("SB-STATUS-01", "1号糖仓", "14000.00");
        assertThrows(BizException.class, () -> batchService.store(pending.getId()));
        assertThrows(BizException.class, () -> batchService.ship(pending.getId()));

        batchService.inspect(pending.getId(), inspection(0, "req-status-fail", "不通过", "水分异常"));
        assertThrows(BizException.class, () -> batchService.store(pending.getId()));

        batchService.inspect(pending.getId(), inspection(1, "req-status-pass", "通过", null));
        batchService.store(pending.getId());
        assertThrows(BizException.class, () -> batchService.store(pending.getId()));

        batchService.ship(pending.getId());
        assertThrows(BizException.class, () -> batchService.store(pending.getId()));
        assertThrows(BizException.class, () -> batchService.ship(pending.getId()));
        assertEquals("已发运", batchService.get(pending.getId()).getStatus());
    }

    @Test
    void stock_summary_separates_pending_shippable_shipped_and_locked() {
        // 10t：未复检，待复检
        createBatch("SB-STOCK-01", "1号糖仓", "10000.00");

        // 20t：复检通过但尚未入库，单列通过待入库，不计入可发运
        SugarBatch passedWaiting = createBatch("SB-STOCK-02", "1号糖仓", "20000.00");
        batchService.inspect(passedWaiting.getId(), inspection(0, "req-stock-pass-wait", "通过", null));

        // 30t：已入库且当前版本通过，可发运
        SugarBatch shippable = createBatch("SB-STOCK-03", "1号糖仓", "30000.00");
        batchService.inspect(shippable.getId(), inspection(0, "req-stock-ship-pass", "通过", null));
        batchService.store(shippable.getId());

        // 40t：入库后重检不通过，不能计入可发运；正常状态机下作为锁定库存单列
        SugarBatch locked = createBatch("SB-STOCK-04", "1号糖仓", "40000.00");
        batchService.inspect(locked.getId(), inspection(0, "req-stock-lock-pass", "通过", null));
        batchService.store(locked.getId());
        batchService.inspect(locked.getId(), inspection(1, "req-stock-lock-fail", "不通过", "返潮"));

        // 50t：已发运
        SugarBatch shipped = createBatch("SB-STOCK-05", "1号糖仓", "50000.00");
        batchService.inspect(shipped.getId(), inspection(0, "req-stock-shipped", "通过", null));
        batchService.store(shipped.getId());
        batchService.ship(shipped.getId());

        // 15t：复检不通过、尚未入库，计入待复检数量
        SugarBatch failedWaiting = createBatch("SB-STOCK-06", "2号糖仓", "15000.00");
        batchService.inspect(failedWaiting.getId(), inspection(0, "req-stock-fail-wait", "不通过", "色值异常"));

        Map<String, Object> first = stock("1号糖仓");
        assertEquals(new BigDecimal("10.00"), first.get("pendingInspectionTon"));
        assertEquals(1, first.get("pendingInspectionBatches"));
        assertEquals(new BigDecimal("20.00"), first.get("awaitingStoreTon"));
        assertEquals(1, first.get("awaitingStoreBatches"));
        assertEquals(new BigDecimal("30.00"), first.get("shippableTon"));
        assertEquals(1, first.get("shippableBatches"));
        assertEquals(new BigDecimal("50.00"), first.get("shippedTon"));
        assertEquals(1, first.get("shippedBatches"));
        assertEquals(new BigDecimal("40.00"), first.get("lockedTon"));
        assertEquals(1, first.get("lockedBatches"));
        assertEquals(new BigDecimal("100.00"), first.get("quantityTon"), "在仓口径=待复检10t+通过待入库20t+可发运30t+锁定40t");

        Map<String, Object> second = stock("2号糖仓");
        assertEquals(new BigDecimal("15.00"), second.get("pendingInspectionTon"));
        assertEquals(new BigDecimal("0.00"), second.get("shippableTon"));
        assertEquals(new BigDecimal("0.00"), second.get("shippedTon"));
    }

    @Test
    void inspection_filters_align_with_current_conclusion() {
        SugarBatch waiting = createBatch("SB-FILTER-01", "1号糖仓", "10000.00");
        SugarBatch passed = createBatch("SB-FILTER-02", "1号糖仓", "11000.00");
        SugarBatch failed = createBatch("SB-FILTER-03", "1号糖仓", "12000.00");
        batchService.inspect(passed.getId(), inspection(0, "req-filter-pass", "通过", null));
        batchService.inspect(failed.getId(), inspection(0, "req-filter-fail", "不通过", "不合格"));

        assertEquals(List.of(waiting.getId()),
                batchService.list(null, null, null, "待复检").stream().map(SugarBatch::getId).toList());
        assertEquals(List.of(passed.getId()),
                batchService.list(null, null, null, "通过").stream().map(SugarBatch::getId).toList());
        assertEquals(List.of(failed.getId()),
                batchService.list(null, null, null, "不通过").stream().map(SugarBatch::getId).toList());
    }

    @Test
    void failed_ship_or_store_does_not_change_version_or_create_record() {
        SugarBatch passed = createBatch("SB-TXN-01", "1号糖仓", "10000.00");
        SugarBatch pending = createBatch("SB-TXN-02", "1号糖仓", "11000.00");
        batchService.inspect(passed.getId(), inspection(0, "req-txn-pass", "通过", null));
        batchService.store(passed.getId());

        assertThrows(BizException.class, () -> batchService.store(passed.getId()));
        assertThrows(BizException.class, () -> batchService.ship(pending.getId()));

        SugarBatch reloaded = batchService.get(passed.getId());
        assertEquals(1, reloaded.getCurrentVersion());
        assertEquals(1, inspectionRepository.findByBatchIdOrderByVersionAsc(passed.getId()).size());
        assertEquals("已入库", reloaded.getStatus());
        assertEquals("待复检", batchService.get(pending.getId()).getStatus());
    }

    @Test
    void database_unique_version_index_blocks_duplicate_version() {
        SugarBatch batch = createBatch("SB-DB-01", "1号糖仓", "10000.00");
        batchService.inspect(batch.getId(), inspection(0, "req-db-v1", "通过", null));

        SugarQualityInspection duplicateVersion = new SugarQualityInspection();
        duplicateVersion.setBatchId(batch.getId());
        duplicateVersion.setBatchNo(batch.getBatchNo());
        duplicateVersion.setVersion(1);
        duplicateVersion.setRequestId("req-db-duplicate-version");
        duplicateVersion.setInspectedAt(LocalDateTime.now());
        duplicateVersion.setBrix(new BigDecimal("99.00"));
        duplicateVersion.setPurity(new BigDecimal("99.00"));
        duplicateVersion.setFiber(BigDecimal.ZERO);
        duplicateVersion.setConclusion("通过");
        duplicateVersion.setCreatedAt(LocalDateTime.now());
        assertThrows(DataIntegrityViolationException.class,
                () -> inspectionRepository.saveAndFlush(duplicateVersion));
    }

    @Test
    void database_unique_request_index_blocks_duplicate_request() {
        SugarBatch batch = createBatch("SB-DB-02", "1号糖仓", "10000.00");
        batchService.inspect(batch.getId(), inspection(0, "req-db-unique", "通过", null));

        SugarQualityInspection duplicateRequest = new SugarQualityInspection();
        duplicateRequest.setBatchId(batch.getId());
        duplicateRequest.setBatchNo(batch.getBatchNo());
        duplicateRequest.setVersion(2);
        duplicateRequest.setRequestId("req-db-unique");
        duplicateRequest.setInspectedAt(LocalDateTime.now());
        duplicateRequest.setBrix(new BigDecimal("99.00"));
        duplicateRequest.setPurity(new BigDecimal("99.00"));
        duplicateRequest.setFiber(BigDecimal.ZERO);
        duplicateRequest.setConclusion("通过");
        duplicateRequest.setCreatedAt(LocalDateTime.now());
        assertThrows(DataIntegrityViolationException.class,
                () -> inspectionRepository.saveAndFlush(duplicateRequest));
    }

    private SugarBatch createBatch(String no, String warehouse, String kg) {
        SugarBatch input = new SugarBatch();
        input.setBatchNo(no);
        input.setGrade("白砂糖");
        input.setQuantityKg(new BigDecimal(kg));
        input.setWarehouse(warehouse);
        input.setQuality(new JuiceQuality(new BigDecimal("99.80"), new BigDecimal("99.60"), BigDecimal.ZERO));
        return batchService.create(input);
    }

    private SugarInspectionRequest inspection(int baseVersion, String requestId, String conclusion, String reason) {
        SugarInspectionRequest request = new SugarInspectionRequest();
        request.setBaseVersion(baseVersion);
        request.setRequestId(requestId);
        request.setInspectedAt(LocalDateTime.now());
        request.setBrix(new BigDecimal("99.80"));
        request.setPurity(new BigDecimal("99.62"));
        request.setFiber(new BigDecimal("0.02"));
        request.setConclusion(conclusion);
        request.setRejectReason(reason);
        return request;
    }

    private Map<String, Object> stock(String warehouse) {
        return batchService.stockByWarehouse().stream()
                .filter(r -> warehouse.equals(r.get("warehouse")))
                .findFirst()
                .orElseThrow();
    }
}
