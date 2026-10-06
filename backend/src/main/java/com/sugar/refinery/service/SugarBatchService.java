package com.sugar.refinery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.composite.JuiceQuality;
import com.sugar.refinery.dto.SugarInspectionRequest;
import com.sugar.refinery.entity.SugarBatch;
import com.sugar.refinery.entity.SugarQualityInspection;
import com.sugar.refinery.repository.SugarBatchRepository;
import com.sugar.refinery.repository.SugarQualityInspectionRepository;

/**
 * 成品糖批次业务。
 *
 * <p>核心规则：① 品类按纯度自动判定（纯度 ≥ 99.5 才能判白砂糖）；
 * ② 单批数量不得超过 {@link Dict#MAX_BATCH_KG}；③ 新批次为「待复检」，只有当前复检版本
 * 「通过」后才能入库；入库后仍需当前版本通过才允许发运；④ 状态逐级推进：
 * 待复检 → 已入库 → 已发运；⑤ 复检按批号和版本追加，requestId 幂等，旧 baseVersion 不能写入，
 * 已入库 / 已发运后重新复检会生成新版本并保留历史。复检记录与批次版本在同一事务提交，
 * 任一步失败整体回滚，不留孤立记录。</p>
 */
@Service
public class SugarBatchService {

    private static final String SAFE_RETRY = "；本次未落下任何数据，可安全重试";

    private final SugarBatchRepository batchRepository;
    private final SugarQualityInspectionRepository inspectionRepository;

    public SugarBatchService(SugarBatchRepository batchRepository,
                             SugarQualityInspectionRepository inspectionRepository) {
        this.batchRepository = batchRepository;
        this.inspectionRepository = inspectionRepository;
    }

    @Transactional(readOnly = true)
    public List<SugarBatch> list(String status, String grade, String warehouse, String inspectionStatus) {
        List<SugarBatch> rows = batchRepository.findAll().stream()
                .filter(b -> status == null || status.isBlank() || status.equals(b.getStatus()))
                .filter(b -> grade == null || grade.isBlank() || grade.equals(b.getGrade()))
                .filter(b -> warehouse == null || warehouse.isBlank() || warehouse.equals(b.getWarehouse()))
                .toList();
        attachCurrentInspections(rows);
        if (inspectionStatus != null && !inspectionStatus.isBlank()) {
            if (!Dict.isValidInspectionStatus(inspectionStatus)) {
                throw new BizException("复检状态不合法：" + inspectionStatus);
            }
            rows = rows.stream()
                    .filter(b -> inspectionStatus.equals(b.getInspectionStatus()))
                    .toList();
        }
        return rows;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public SugarBatch get(Long id) {
        SugarBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new BizException("成品糖批次不存在：" + id));
        return attachCurrentInspection(batch);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public SugarQualityInspection getInspectionByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        return inspectionRepository.findByRequestId(requestId.trim()).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<SugarQualityInspection> inspections(Long batchId) {
        get(batchId);
        return inspectionRepository.findByBatchIdOrderByVersionAsc(batchId);
    }

    @Transactional
    public SugarBatch create(SugarBatch input) {
        if (input.getBatchNo() == null || input.getBatchNo().isBlank()) {
            throw new BizException("批号不能为空");
        }
        batchRepository.findByBatchNo(input.getBatchNo().trim()).ifPresent(b -> {
            throw new BizException("批号已存在：" + input.getBatchNo());
        });
        if (input.getQuantityKg() == null || input.getQuantityKg().signum() <= 0) {
            throw new BizException("数量必须大于 0");
        }
        // 规则②：单批上限
        if (input.getQuantityKg().compareTo(Dict.MAX_BATCH_KG) > 0) {
            throw new BizException("单批 " + input.getQuantityKg() + " 千克超过上限 "
                    + Dict.MAX_BATCH_KG + " 千克，需拆分");
        }
        if (!Dict.isValidWarehouse(input.getWarehouse())) {
            throw new BizException("糖仓不合法：" + input.getWarehouse());
        }
        if (input.getQuality() == null) {
            throw new BizException("必须录入品质三指标（糖度 / 纯度 / 纤维分）");
        }

        SugarBatch b = new SugarBatch();
        b.setBatchNo(input.getBatchNo().trim());
        b.setQuantityKg(input.getQuantityKg());
        b.setQuality(input.getQuality());
        b.setWarehouse(input.getWarehouse());
        // 规则①：按纯度自动判品类
        JuiceQuality q = input.getQuality();
        String auto = Dict.decideGrade(q.getPurity());
        if (input.getGrade() != null && !input.getGrade().isBlank()
                && !Dict.isValidSugarGrade(input.getGrade())) {
            throw new BizException("品类不合法：" + input.getGrade());
        }
        // 纯度不够却硬判白砂糖，直接拦下
        if ("白砂糖".equals(input.getGrade()) && !"白砂糖".equals(auto)) {
            throw new BizException("纯度 " + q.getPurity() + "% 未达 " + Dict.MIN_WHITE_PURITY
                    + "%，不能判为白砂糖，应为 " + auto);
        }
        b.setGrade(input.getGrade() == null || input.getGrade().isBlank() ? auto : input.getGrade());
        b.setStatus("待复检");
        b.setCurrentVersion(0);
        b.setProducedAt(LocalDateTime.now());
        return batchRepository.save(b);
    }

    /**
     * 提交质量复检：锁批次 → 校验幂等键和页面基准版本 → 追加新版本 → 更新当前版本指针。
     */
    @Transactional
    public SugarBatch inspect(Long id, SugarInspectionRequest input) {
        if (input.getRequestId() == null || input.getRequestId().isBlank()) {
            throw new BizException("缺少请求标识，请刷新页面后重试");
        }
        String requestId = input.getRequestId().trim();
        if (requestId.length() > 36) {
            throw new BizException("请求标识长度不能超过 36 位");
        }

        SugarBatch batch = lockBatch(id);
        validateInspectionInput(input);

        // 同请求并发时后到者在批次行锁内复查，唯一索引再兜底；重试返回同一笔和同一版本
        Optional<SugarQualityInspection> repeated = inspectionRepository.findByRequestId(requestId);
        if (repeated.isPresent()) {
            SugarQualityInspection existed = repeated.get();
            if (!existed.getBatchId().equals(id) || !samePayload(existed, input)) {
                throw new BizException("该复检请求已受理过，但本次内容不一致，请刷新页面核对后再操作");
            }
            return attachCurrentInspection(batch);
        }

        if (input.getBaseVersion() == null || !input.getBaseVersion().equals(batch.getCurrentVersion())) {
            throw new BizException("复检基准版本已过期（当前版本 V" + batch.getCurrentVersion()
                    + "），请刷新后按最新版本重新复检；旧结果不会回写" + SAFE_RETRY);
        }

        int nextVersion = batch.getCurrentVersion() + 1;
        LocalDateTime now = LocalDateTime.now();
        SugarQualityInspection inspection = new SugarQualityInspection();
        inspection.setBatchId(batch.getId());
        inspection.setBatchNo(batch.getBatchNo());
        inspection.setVersion(nextVersion);
        inspection.setRequestId(requestId);
        inspection.setInspectedAt(input.getInspectedAt());
        inspection.setBrix(input.getBrix());
        inspection.setPurity(input.getPurity());
        inspection.setFiber(input.getFiber());
        inspection.setConclusion(input.getConclusion());
        inspection.setRejectReason("通过".equals(input.getConclusion()) ? null : input.getRejectReason().trim());
        inspection.setCreatedAt(now);

        // 先落记录再更新指针；同一事务内任一 SQL 失败都会整体回滚
        SugarQualityInspection saved = inspectionRepository.saveAndFlush(inspection);
        batch.setCurrentVersion(nextVersion);
        batchRepository.saveAndFlush(batch);
        batch.setCurrentInspection(saved);
        return batch;
    }

    /** 入库：待复检且当前复检通过 → 已入库 */
    @Transactional
    public SugarBatch store(Long id) {
        SugarBatch b = lockBatch(id);
        if (!"待复检".equals(b.getStatus())) {
            throw new BizException("只有「待复检」的批次能入库，当前状态：" + b.getStatus() + SAFE_RETRY);
        }
        SugarQualityInspection current = currentInspection(b);
        if (current == null) {
            throw new BizException("批次 " + b.getBatchNo() + " 尚未复检，不能入库" + SAFE_RETRY);
        }
        if (!"通过".equals(current.getConclusion())) {
            throw new BizException("批次 " + b.getBatchNo() + " 最近复检不通过，不能入库；原因："
                    + current.getRejectReason() + SAFE_RETRY);
        }
        b.setStatus("已入库");
        b = batchRepository.save(b);
        return attachCurrentInspection(b);
    }

    /** 发运：已入库且当前复检版本通过 → 已发运 */
    @Transactional
    public SugarBatch ship(Long id) {
        SugarBatch b = lockBatch(id);
        if (!"已入库".equals(b.getStatus())) {
            throw new BizException("只有「已入库」的批次能发运，当前状态：" + b.getStatus() + SAFE_RETRY);
        }
        SugarQualityInspection current = currentInspection(b);
        if (current == null || !"通过".equals(current.getConclusion())) {
            throw new BizException("批次 " + b.getBatchNo() + " 当前复检版本未通过，发运资格已锁定" + SAFE_RETRY);
        }
        b.setStatus("已发运");
        b = batchRepository.save(b);
        return attachCurrentInspection(b);
    }

    /** 按糖仓汇总库存（吨），区分待复检、可发运、已发运 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> stockByWarehouse() {
        List<SugarBatch> all = batchRepository.findAll();
        attachCurrentInspections(all);

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String w : Dict.WAREHOUSES) {
            List<SugarBatch> rows = all.stream().filter(b -> w.equals(b.getWarehouse())).toList();
            List<SugarBatch> pending = rows.stream()
                    .filter(b -> "待复检".equals(b.getStatus()) && !b.isStoreEligible())
                    .toList();
            List<SugarBatch> awaitingStore = rows.stream()
                    .filter(SugarBatch::isStoreEligible)
                    .toList();
            List<SugarBatch> shippable = rows.stream()
                    .filter(b -> b.isShipmentEligible())
                    .toList();
            List<SugarBatch> shipped = rows.stream().filter(b -> "已发运".equals(b.getStatus())).toList();
            // 正常状态机不会出现该桶；保留口径用于兜住历史/手工异常数据，避免库存被悄悄吞掉
            List<SugarBatch> locked = rows.stream()
                    .filter(b -> "已入库".equals(b.getStatus()) && !b.isShipmentEligible())
                    .toList();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("warehouse", w);
            row.put("pendingInspectionBatches", pending.size());
            row.put("pendingInspectionTon", sumTon(pending));
            row.put("awaitingStoreBatches", awaitingStore.size());
            row.put("awaitingStoreTon", sumTon(awaitingStore));
            row.put("shippableBatches", shippable.size());
            row.put("shippableTon", sumTon(shippable));
            row.put("shippedBatches", shipped.size());
            row.put("shippedTon", sumTon(shipped));
            row.put("lockedBatches", locked.size());
            row.put("lockedTon", sumTon(locked));
            // 兼容原库存口径：仍在糖仓、未发运的数量（含待复检、通过待入库和被复检锁定的异常在仓批次）
            BigDecimal currentTon = sumTon(pending).add(sumTon(awaitingStore))
                    .add(sumTon(shippable)).add(sumTon(locked));
            row.put("batches", pending.size() + awaitingStore.size() + shippable.size() + locked.size());
            row.put("quantityTon", currentTon);
            out.add(row);
        }
        return out;
    }

    private void validateInspectionInput(SugarInspectionRequest input) {
        if (input.getInspectedAt() == null) {
            throw new BizException("请录入复检时间");
        }
        if (!Dict.isPercent(input.getBrix()) || !Dict.isPercent(input.getPurity())
                || !Dict.isPercent(input.getFiber())) {
            throw new BizException("糖度、纯度、纤维分必须在 0～100 之间");
        }
        if (input.getBrix().stripTrailingZeros().scale() > 2
                || input.getPurity().stripTrailingZeros().scale() > 2
                || input.getFiber().stripTrailingZeros().scale() > 2) {
            throw new BizException("复检指标最多保留两位小数");
        }
        if (!Dict.isValidInspectionResult(input.getConclusion())) {
            throw new BizException("复检结论只能是「通过」或「不通过」");
        }
        if ("不通过".equals(input.getConclusion())) {
            if (input.getRejectReason() == null || input.getRejectReason().isBlank()) {
                throw new BizException("复检不通过时必须填写原因");
            }
            if (input.getRejectReason().trim().length() > 255) {
                throw new BizException("不通过原因不能超过 255 字");
            }
        }
    }

    private boolean samePayload(SugarQualityInspection existed, SugarInspectionRequest input) {
        return Objects.equals(existed.getVersion(), input.getBaseVersion() == null ? null : input.getBaseVersion() + 1)
                && Objects.equals(existed.getInspectedAt(), input.getInspectedAt())
                && existed.getBrix().compareTo(input.getBrix()) == 0
                && existed.getPurity().compareTo(input.getPurity()) == 0
                && existed.getFiber().compareTo(input.getFiber()) == 0
                && Objects.equals(existed.getConclusion(), input.getConclusion())
                && Objects.equals(existed.getRejectReason(),
                        "通过".equals(input.getConclusion()) ? null : input.getRejectReason().trim());
    }

    private SugarBatch lockBatch(Long id) {
        return batchRepository.lockById(id)
                .orElseThrow(() -> new BizException("成品糖批次不存在：" + id + SAFE_RETRY));
    }

    private SugarQualityInspection currentInspection(SugarBatch batch) {
        if (batch.getCurrentVersion() == null || batch.getCurrentVersion() <= 0) {
            return null;
        }
        return inspectionRepository.findByBatchIdAndVersion(batch.getId(), batch.getCurrentVersion())
                .orElseThrow(() -> new BizException("当前复检版本缺失，批次已锁定，请联系管理员修复" + SAFE_RETRY));
    }

    private SugarBatch attachCurrentInspection(SugarBatch batch) {
        batch.setCurrentInspection(currentInspection(batch));
        return batch;
    }

    private void attachCurrentInspections(List<SugarBatch> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(SugarBatch::getId).toList();
        Map<Long, List<SugarQualityInspection>> map = inspectionRepository
                .findByBatchIdInOrderByVersionAsc(ids)
                .stream()
                .collect(Collectors.groupingBy(SugarQualityInspection::getBatchId));
        for (SugarBatch batch : rows) {
            List<SugarQualityInspection> history = map.getOrDefault(batch.getId(), List.of());
            int version = batch.getCurrentVersion() == null ? 0 : batch.getCurrentVersion();
            SugarQualityInspection current = version <= 0 ? null : history.stream()
                    .filter(i -> i.getVersion() == version)
                    .findFirst()
                    .orElse(null);
            batch.setCurrentInspection(current);
        }
    }

    private BigDecimal sumTon(List<SugarBatch> rows) {
        BigDecimal kg = rows.stream()
                .map(SugarBatch::getQuantityKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return kg.divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
    }
}
