package com.sugar.refinery.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.entity.PressControl;
import com.sugar.refinery.entity.PressStation;
import com.sugar.refinery.repository.PressControlRepository;
import com.sugar.refinery.repository.PressStationRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 压榨机组业务。
 *
 * <p>核心规则：① 调整负荷不得超过设计处理量（超载不允许再往上压）；
 * ② 只有「运行」的机组能调负荷；③ 状态流转：运行 ↔ 停机，运行 / 停机 → 检修，
 * 检修完成只能回停机；检修中的机组不能调负荷、不能开机、不能再次进入检修。</p>
 *
 * <p>并发与一致性保证（治状态错乱，而非只禁用按钮）：</p>
 * <ul>
 *   <li>所有写操作先对机组行上悲观写锁（SELECT ... FOR UPDATE），同一机组的
 *       调负荷与状态切换在数据库层串行化，杜绝「读后写」丢失更新；</li>
 *   <li>每次写操作携带 requestId 幂等键并落 {@link PressControl} 流水：
 *       重复点击 / 超时重试共用一键，只第一次生效，检修时间不会被重复点击覆盖；</li>
 *   <li>实际写入再各走一道带状态条件的原子 UPDATE（where status = 预期状态）：
 *       转检修一条语句同时置「检修 + 负荷归零 + 检修时间」，
 *       即便绕过服务层也写不出「状态已检修、负荷仍在 / 没有检修时间」的矛盾行；</li>
 * </ul>
 * 任一步失败整个事务回滚，机组保持原状态，可安全重试。
 */
@Service
public class PressStationService {

    /** 所有写路径都是单事务，失败即整体回滚，因此任何失败都可安全重试 */
    private static final String SAFE_RETRY = "；本次未落下任何数据，可安全重试";

    public static final String OP_SET_LOAD = "SET_LOAD";
    public static final String OP_TRANSFER = "TRANSFER";

    private final PressStationRepository pressRepository;
    private final PressControlRepository controlRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public PressStationService(PressStationRepository pressRepository,
                               PressControlRepository controlRepository) {
        this.pressRepository = pressRepository;
        this.controlRepository = controlRepository;
    }

    public List<PressStation> list(String status) {
        if (status != null && !status.isBlank()) {
            return pressRepository.findByStatus(status);
        }
        return pressRepository.findAll();
    }

    public PressStation get(Long id) {
        return pressRepository.findById(id)
                .orElseThrow(() -> new BizException("压榨机组不存在：" + id));
    }

    /** 供控制器在唯一冲突时判定：该 requestId 是否已有落库记录（并发重复请求合并用） */
    public java.util.Optional<PressControl> findControl(String requestId) {
        return requestId == null ? java.util.Optional.empty()
                : controlRepository.findByRequestId(requestId);
    }

    @Transactional
    public PressStation create(PressStation input) {
        if (input.getStationCode() == null || input.getStationCode().isBlank()) {
            throw new BizException("机组编号不能为空");
        }
        pressRepository.findByStationCode(input.getStationCode().trim()).ifPresent(p -> {
            throw new BizException("机组编号已存在：" + input.getStationCode());
        });
        if (input.getDesignCapacityTph() == null || input.getDesignCapacityTph().signum() <= 0) {
            throw new BizException("设计处理量必须大于 0");
        }
        PressStation p = new PressStation();
        p.setStationCode(input.getStationCode().trim());
        p.setStationName(input.getStationName() == null ? input.getStationCode() : input.getStationName());
        p.setDesignCapacityTph(input.getDesignCapacityTph());
        p.setCurrentLoadTph(input.getCurrentLoadTph() == null ? BigDecimal.ZERO : input.getCurrentLoadTph());
        p.setOccupiedKg(BigDecimal.ZERO);
        if (p.getCurrentLoadTph().compareTo(p.getDesignCapacityTph()) > 0) {
            throw new BizException("初始负荷不得超过设计处理量");
        }
        p.setStatus(input.getStatus() == null || input.getStatus().isBlank() ? "停机" : input.getStatus());
        if (!Dict.isValidPressStatus(p.getStatus())) {
            throw new BizException("机组状态不合法：" + p.getStatus());
        }
        p.setLastMaintainAt(LocalDateTime.now());
        return pressRepository.save(p);
    }

    /**
     * 调整负荷。
     *
     * @param requestId 幂等键，一次「设定负荷」意图一个；重复点击 / 超时重试共用
     */
    @Transactional
    public PressStation setLoad(Long id, BigDecimal loadTph, String requestId) {
        String rid = requireRequestId(requestId);

        // 幂等快路径：顺序重复请求直接返回第一次的结果
        PressControl existing = controlRepository.findByRequestId(rid).orElse(null);
        if (existing != null) {
            ensureSameIntent(existing, OP_SET_LOAD, id, null, loadTph);
            return get(existing.getStationId());
        }

        if (loadTph == null || loadTph.signum() < 0) {
            throw new BizException("负荷不能为负数" + SAFE_RETRY);
        }

        // 锁机组行：与并发的状态切换 / 其他调负荷串行
        PressStation p = lockStation(id);
        // 锁内再查一次幂等：并发同键请求在第一笔提交后拿到锁，直接合并到同一结果
        if ((existing = controlRepository.findByRequestId(rid).orElse(null)) != null) {
            ensureSameIntent(existing, OP_SET_LOAD, id, null, loadTph);
            return get(id);
        }
        if (!"运行".equals(p.getStatus())) {
            // 非法操作：保留原状态（本事务不改任何数据），明确说明原因
            throw new BizException("只有「运行」的机组能调负荷，" + p.getStationName()
                    + " 当前状态：" + p.getStatus() + SAFE_RETRY);
        }
        // 规则①：不得超过设计处理量
        if (loadTph.compareTo(p.getDesignCapacityTph()) > 0) {
            throw new BizException("负荷 " + loadTph + " 超过设计处理量 "
                    + p.getDesignCapacityTph() + " 吨/小时，请调低后重试" + SAFE_RETRY);
        }

        // 条件更新兜底：只有仍是「运行」才落负荷（并发下被转检修则影响 0 行）
        int rows = controlRepository.updateLoadIfStatus(id, loadTph, "运行");
        if (rows == 0) {
            throw new BizException("机组状态刚刚发生变化，本次负荷调整未生效，请刷新后核对" + SAFE_RETRY);
        }
        recordControl(rid, id, OP_SET_LOAD, null, loadTph);
        return reload(p);
    }

    /**
     * 状态切换。转入检修时同一事务把负荷归零并记录本次检修时间；
     * 检修完成只能回停机；非法流转保留原状态并说明原因。
     *
     * @param requestId 幂等键，一次状态切换意图一个；重复点击 / 超时重试共用
     */
    @Transactional
    public PressStation transfer(Long id, String target, String requestId) {
        String rid = requireRequestId(requestId);

        // 幂等：重复点击（含超时重试）直接返回第一次落下的状态，绝不重复推进、
        // 绝不把检修时间再覆盖一遍
        PressControl existing = controlRepository.findByRequestId(rid).orElse(null);
        if (existing != null) {
            ensureSameIntent(existing, OP_TRANSFER, id, target, null);
            return get(existing.getStationId());
        }

        if (!Dict.isValidPressStatus(target)) {
            throw new BizException("机组状态不合法：" + target);
        }

        // 锁机组行：与并发的调负荷 / 其他切换串行，先到先得
        PressStation p = lockStation(id);
        // 锁内再查一次幂等：并发同键请求在第一笔提交后拿到锁，直接合并到同一结果
        if ((existing = controlRepository.findByRequestId(rid).orElse(null)) != null) {
            ensureSameIntent(existing, OP_TRANSFER, id, target, null);
            return get(id);
        }
        String from = p.getStatus();
        if (!Dict.canTransferPress(from, target)) {
            // 非法转换：状态原样保留，给出值班员看得懂的原因
            throw new BizException(rejectReason(from, target) + SAFE_RETRY);
        }

        int rows;
        if ("检修".equals(target)) {
            // 转检修：状态置检修 + 负荷归零 + 记录本次检修时间，一条原子语句同生共死
            rows = controlRepository.enterMaintenance(id, from, LocalDateTime.now());
        } else {
            rows = controlRepository.updateStatus(id, from, target);
        }
        if (rows == 0) {
            throw new BizException("机组状态刚刚发生变化，本次状态切换未生效，请刷新后核对" + SAFE_RETRY);
        }

        recordControl(rid, id, OP_TRANSFER, target, null);
        return reload(p);
    }

    /**
     * 条件 UPDATE / 幂等流水落库后丢弃一级缓存里的旧快照，重新读入最新机组行返回，
     * 保证接口响应与数据库最终状态完全一致（刷新前后口径相同）。
     */
    private PressStation reload(PressStation p) {
        entityManager.refresh(p);
        return p;
    }

    /** 幂等流水只追加；唯一冲突由控制器兜底合并，保证一次意图一条结果 */
    private void recordControl(String requestId, Long stationId, String opType,
                               String targetStatus, BigDecimal targetLoad) {
        PressControl c = new PressControl();
        c.setRequestId(requestId);
        c.setStationId(stationId);
        c.setOpType(opType);
        c.setTargetStatus(targetStatus);
        c.setTargetLoadTph(targetLoad);
        c.setCreatedAt(LocalDateTime.now());
        controlRepository.saveAndFlush(c);
    }

    /**
     * 同一幂等键却带着不同内容重提：不静默合并成第二次的意图，
     * 明确拒绝，让值班员刷新核对，避免「以为改成了 B 其实落的是 A」。
     */
    private void ensureSameIntent(PressControl existing, String opType, Long stationId,
                                  String targetStatus, BigDecimal targetLoad) {
        boolean same = existing.getOpType().equals(opType)
                && existing.getStationId().equals(stationId)
                && (targetStatus == null
                        ? existing.getTargetStatus() == null
                        : targetStatus.equals(existing.getTargetStatus()))
                && (targetLoad == null
                        ? existing.getTargetLoadTph() == null
                        : targetLoad.compareTo(existing.getTargetLoadTph()) == 0);
        if (!same) {
            throw new BizException("该请求已受理过，但本次操作内容不一致，请刷新页面核对当前状态后再操作");
        }
    }

    private PressStation lockStation(Long id) {
        return pressRepository.lockById(id)
                .orElseThrow(() -> new BizException("压榨机组不存在：" + id + SAFE_RETRY));
    }

    private String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException("缺少请求标识，请刷新页面后重试");
        }
        return requestId.trim();
    }

    /** 非法流转的人话原因：明确点出当前状态下哪些动作被禁止 */
    private String rejectReason(String from, String target) {
        if ("检修".equals(from)) {
            return switch (target) {
                case "运行" -> "检修中的机组不能直接开机，需先「检修完成」回到停机";
                case "检修" -> "机组已在检修中，不能重复进入检修";
                default -> "检修中的机组只允许「检修完成」回到停机，不能转为「" + target + "」";
            };
        }
        return "不允许从「" + from + "」转为「" + target + "」";
    }
}
