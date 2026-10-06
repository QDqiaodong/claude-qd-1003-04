package com.sugar.refinery.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.entity.CaneIntake;
import com.sugar.refinery.entity.FeedBatch;
import com.sugar.refinery.entity.PressStation;
import com.sugar.refinery.repository.CaneIntakeRepository;
import com.sugar.refinery.repository.FeedBatchRepository;
import com.sugar.refinery.repository.PressStationRepository;

/**
 * 投料交接业务。
 *
 * <p>核心规则：① 只有「已验收」且可投余量 &gt; 0 的磅单能交接，只分配给「运行」的机组；
 * ② 计划量对磅单可投余量、机组剩余能力两边独立校验，任一不足整单拒绝，
 * 不按较大值截断保存；③ 提交交接 = 扣磅单余量 + 占机组能力 + 落投料批，三件事在
 * 同一事务里，靠行锁（SELECT ... FOR UPDATE，固定先磅单后机组）+ requestId 幂等键，
 * 保证并发分配、重复点击、超时重试下只落下一份有效占用；④ 撤回与少投完成的差额
 * 在同一事务里同步归还两侧，任一步失败整体回滚，不留半成品。</p>
 */
@Service
public class FeedBatchService {

    /** 所有写路径都是单事务，失败即整体回滚，因此任何失败都可安全重试 */
    private static final String SAFE_RETRY = "；本次未落下任何数据，可安全重试";

    private final FeedBatchRepository feedRepository;
    private final CaneIntakeRepository intakeRepository;
    private final PressStationRepository pressRepository;

    public FeedBatchService(FeedBatchRepository feedRepository,
                            CaneIntakeRepository intakeRepository,
                            PressStationRepository pressRepository) {
        this.feedRepository = feedRepository;
        this.intakeRepository = intakeRepository;
        this.pressRepository = pressRepository;
    }

    public List<FeedBatch> list(String status) {
        if (status != null && !status.isBlank()) {
            return feedRepository.findByStatusOrderByIdDesc(status);
        }
        return feedRepository.findAllByOrderByIdDesc();
    }

    public Optional<FeedBatch> findByRequestId(String requestId) {
        return feedRepository.findByRequestId(requestId);
    }

    /**
     * 提交交接：锁磅单 → 锁机组 → 双边校验 → 扣余量 + 占能力 + 落批，同一事务。
     */
    @Transactional
    public FeedBatch handover(Long intakeId, Long stationId, BigDecimal plannedKg, String requestId) {
        if (intakeId == null || stationId == null) {
            throw new BizException("请选择要交接的磅单与目标机组");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new BizException("缺少请求标识，请刷新页面后重试");
        }
        if (plannedKg == null || plannedKg.signum() <= 0) {
            throw new BizException("计划投料量必须大于 0");
        }
        // 幂等：同一 requestId 只落一笔，重复点击 / 超时重试直接返回已落下的批
        Optional<FeedBatch> dup = feedRepository.findByRequestId(requestId.trim());
        if (dup.isPresent()) {
            FeedBatch existed = dup.get();
            // 同一幂等键却换了内容重提：不静默合并，明确拒绝，让值班员刷新核对
            if (!existed.getIntakeId().equals(intakeId) || !existed.getStationId().equals(stationId)
                    || existed.getPlannedKg().compareTo(plannedKg) != 0) {
                throw new BizException("该请求已受理过（投料批 " + existed.getBatchNo()
                        + "），但本次内容不一致，请刷新页面核对后再操作");
            }
            return existed;
        }

        // 锁磅单行：同一磅单的并发分配在此串行，后到者读到的是已扣减后的余量
        CaneIntake intake = intakeRepository.lockById(intakeId)
                .orElseThrow(() -> new BizException("磅单不存在：" + intakeId + SAFE_RETRY));
        if (!"已验收".equals(intake.getStatus())) {
            throw new BizException("只有「已验收」的磅单能交接投料，" + intake.getTicketNo()
                    + " 当前状态：" + intake.getStatus() + SAFE_RETRY);
        }
        BigDecimal available = intake.getAvailableKg();
        if (available.signum() <= 0) {
            throw new BizException("磅单 " + intake.getTicketNo() + " 可投余量已为零，不能进入交接" + SAFE_RETRY);
        }
        if (plannedKg.compareTo(available) > 0) {
            throw new BizException("计划投料量 " + plannedKg + " kg 超过磅单可投余量 " + available
                    + " kg，请调低后重试" + SAFE_RETRY);
        }

        // 锁机组行（固定先磅单后机组的加锁顺序，避免死锁）
        PressStation station = pressRepository.lockById(stationId)
                .orElseThrow(() -> new BizException("压榨机组不存在：" + stationId + SAFE_RETRY));
        if (!"运行".equals(station.getStatus())) {
            throw new BizException("只有「运行」的机组能接收投料，" + station.getStationName()
                    + " 当前状态：" + station.getStatus() + SAFE_RETRY);
        }
        BigDecimal remaining = station.getRemainingKg();
        if (plannedKg.compareTo(remaining) > 0) {
            throw new BizException("计划投料量 " + plannedKg + " kg 超过机组剩余处理能力 " + remaining
                    + " kg（" + station.getRemainingTon() + " 吨），请调低后重试" + SAFE_RETRY);
        }

        // 三件事同一事务：扣磅单余量 + 占机组能力 + 落投料批，任一步失败整体回滚
        intake.setAllocatedKg(intake.getAllocatedKg().add(plannedKg));
        intakeRepository.save(intake);
        station.setOccupiedKg(station.getOccupiedKg().add(plannedKg));
        pressRepository.save(station);

        FeedBatch batch = new FeedBatch();
        batch.setBatchNo(nextBatchNo());
        batch.setRequestId(requestId.trim());
        batch.setIntakeId(intake.getId());
        batch.setTicketNo(intake.getTicketNo());
        batch.setStationId(station.getId());
        batch.setStationName(station.getStationName());
        batch.setPlannedKg(plannedKg);
        batch.setStatus("待投料");
        batch.setCreatedAt(LocalDateTime.now());
        // saveAndFlush：让 INSERT 立即执行，request_id 唯一冲突在事务内抛出并整体回滚，
        // 由控制器兜底返回已落下的那笔，保证同一请求只成功一份有效占用
        return feedRepository.saveAndFlush(batch);
    }

    /** 开始投料：待投料 → 投料中（此后不可撤回、不可改机组） */
    @Transactional
    public FeedBatch start(Long id) {
        FeedBatch batch = lockBatch(id);
        if (!Dict.canTransferFeed(batch.getStatus(), "投料中")) {
            throw new BizException("只有「待投料」的投料批能开始投料，当前状态：" + batch.getStatus() + SAFE_RETRY);
        }
        batch.setStatus("投料中");
        batch.setStartedAt(LocalDateTime.now());
        return feedRepository.save(batch);
    }

    /** 撤回：待投料 → 已撤回，同一事务归还磅单余量与机组能力 */
    @Transactional
    public FeedBatch cancel(Long id) {
        FeedBatch batch = lockBatch(id);
        if (!Dict.canTransferFeed(batch.getStatus(), "已撤回")) {
            throw new BizException("只有「待投料」的投料批能撤回，当前状态：" + batch.getStatus() + SAFE_RETRY);
        }
        CaneIntake intake = lockIntake(batch.getIntakeId());
        PressStation station = lockStation(batch.getStationId());
        intake.setAllocatedKg(intake.getAllocatedKg().subtract(batch.getPlannedKg()));
        intakeRepository.save(intake);
        station.setOccupiedKg(station.getOccupiedKg().subtract(batch.getPlannedKg()));
        pressRepository.save(station);
        batch.setStatus("已撤回");
        batch.setCancelledAt(LocalDateTime.now());
        return feedRepository.save(batch);
    }

    /** 完成：投料中 → 已完成，记录实际投料量，少投差额同一事务释放回两侧余量 */
    @Transactional
    public FeedBatch complete(Long id, BigDecimal actualKg) {
        FeedBatch batch = lockBatch(id);
        if (!Dict.canTransferFeed(batch.getStatus(), "已完成")) {
            throw new BizException("只有「投料中」的投料批能完成，当前状态：" + batch.getStatus() + SAFE_RETRY);
        }
        if (actualKg == null || actualKg.signum() <= 0) {
            throw new BizException("实际投料量必须大于 0");
        }
        if (actualKg.compareTo(batch.getPlannedKg()) > 0) {
            throw new BizException("实际投料量 " + actualKg + " kg 超过计划量 " + batch.getPlannedKg()
                    + " kg，请核对后重试" + SAFE_RETRY);
        }
        BigDecimal diff = batch.getPlannedKg().subtract(actualKg);
        if (diff.signum() > 0) {
            CaneIntake intake = lockIntake(batch.getIntakeId());
            PressStation station = lockStation(batch.getStationId());
            intake.setAllocatedKg(intake.getAllocatedKg().subtract(diff));
            intakeRepository.save(intake);
            station.setOccupiedKg(station.getOccupiedKg().subtract(diff));
            pressRepository.save(station);
        }
        batch.setActualKg(actualKg);
        batch.setStatus("已完成");
        batch.setCompletedAt(LocalDateTime.now());
        return feedRepository.save(batch);
    }

    private FeedBatch lockBatch(Long id) {
        return feedRepository.lockById(id)
                .orElseThrow(() -> new BizException("投料批不存在：" + id + SAFE_RETRY));
    }

    private CaneIntake lockIntake(Long id) {
        return intakeRepository.lockById(id)
                .orElseThrow(() -> new BizException("来源磅单不存在：" + id + SAFE_RETRY));
    }

    private PressStation lockStation(Long id) {
        return pressRepository.lockById(id)
                .orElseThrow(() -> new BizException("目标机组不存在：" + id + SAFE_RETRY));
    }

    private String nextBatchNo() {
        return "FB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
