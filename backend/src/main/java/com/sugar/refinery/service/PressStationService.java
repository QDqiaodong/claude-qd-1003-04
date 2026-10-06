package com.sugar.refinery.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.entity.PressStation;
import com.sugar.refinery.repository.PressStationRepository;

/**
 * 压榨机组业务。
 *
 * <p>核心规则：① 调整负荷不得超过设计处理量（超载不允许再往上压）；
 * ② 只有「运行」的机组能调负荷；③ 状态流转：运行 ↔ 停机，检修只能从
 * 运行/停机进，检修完只能回停机；检修中的机组不能调负荷、开机或再次进入检修。</p>
 *
 * <p>一致性保证：调负荷与状态流转都走行锁（SELECT ... FOR UPDATE）串行化，
 * 并发下只落下一条一致结果——绝不出现「状态已是检修但负荷又被写回」；
 * 转检修在同一事务里把负荷归零并记录本次检修时间；流转带 requestId 幂等键，
 * 重复点击 / 超时重试只生效一次，不会重复记录或覆盖检修时间；
 * 非法转换整体回滚，机组保持原状态并在提示里说明原因。</p>
 */
@Service
public class PressStationService {

    /** 所有写路径都是单事务，失败即整体回滚，机组保持原状态，可安全重试 */
    private static final String STATE_KEPT = "；机组保持原状态，本次未落下任何数据";

    private final PressStationRepository pressRepository;

    public PressStationService(PressStationRepository pressRepository) {
        this.pressRepository = pressRepository;
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
        // 与转检修同一口径：检修中的机组负荷必须为 0，并留下本次检修时间
        if ("检修".equals(p.getStatus())) {
            p.setCurrentLoadTph(BigDecimal.ZERO);
        }
        p.setLastMaintainAt(LocalDateTime.now());
        return pressRepository.save(p);
    }

    /**
     * 调整负荷。行锁与状态流转串行：若转检修先落地，本请求读到的是「检修」并被拦下，
     * 绝不会把负荷写回一台已检修的机组。
     */
    @Transactional
    public PressStation setLoad(Long id, BigDecimal loadTph) {
        PressStation p = pressRepository.lockById(id)
                .orElseThrow(() -> new BizException("压榨机组不存在：" + id + STATE_KEPT));
        if (!"运行".equals(p.getStatus())) {
            throw new BizException("只有「运行」的机组能调负荷，" + p.getStationName()
                    + " 当前状态：" + p.getStatus() + STATE_KEPT);
        }
        if (loadTph == null || loadTph.signum() < 0) {
            throw new BizException("负荷不能为负数" + STATE_KEPT);
        }
        // 规则①：不得超过设计处理量
        if (loadTph.compareTo(p.getDesignCapacityTph()) > 0) {
            throw new BizException("负荷 " + loadTph + " 超过设计处理量 "
                    + p.getDesignCapacityTph() + " 吨/小时" + STATE_KEPT);
        }
        p.setCurrentLoadTph(loadTph);
        return pressRepository.save(p);
    }

    /**
     * 状态流转：运行 ↔ 停机；检修只能从运行/停机进，检修完只能回停机。
     *
     * <p>requestId 是幂等键：同一流转意图重复提交（重复点击 / 超时重试）直接回放
     * 当前状态，不重复记录检修时间；同一键却换了目标状态说明键被复用，拒绝并提示刷新核对。</p>
     */
    @Transactional
    public PressStation transfer(Long id, String target, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException("缺少请求标识，请刷新页面后重试");
        }
        if (!Dict.isValidPressStatus(target)) {
            throw new BizException("机组状态不合法：" + target + STATE_KEPT);
        }
        // 行锁：与调负荷、其他流转串行，并发下只落下一条一致结果
        PressStation p = pressRepository.lockById(id)
                .orElseThrow(() -> new BizException("压榨机组不存在：" + id + STATE_KEPT));
        String reqId = requestId.trim();
        // 幂等回放：该请求已受理过——目标与现状一致，直接返回现状（检修时间保持第一次落下的值）；
        // 目标与现状不一致说明同一键被复用到别的意图上，不静默合并，明确拒绝
        if (reqId.equals(p.getLastRequestId())) {
            if (target.equals(p.getStatus())) {
                return p;
            }
            throw new BizException("该请求已受理过（当前状态「" + p.getStatus() + "」），但本次目标「"
                    + target + "」与已受理结果不一致，请刷新页面核对后再操作");
        }
        if (!Dict.canTransferPress(p.getStatus(), target)) {
            throw illegalTransfer(p, target);
        }
        if ("检修".equals(target)) {
            // 转检修：负荷归零 + 记录本次检修时间，与状态同一事务落库，不存在只改状态的半成品
            p.setCurrentLoadTph(BigDecimal.ZERO);
            p.setLastMaintainAt(LocalDateTime.now());
        }
        p.setStatus(target);
        p.setLastRequestId(reqId);
        return pressRepository.save(p);
    }

    /** 非法转换：说明原因与当前可执行动作，事务回滚后机组保持原状态 */
    private BizException illegalTransfer(PressStation p, String target) {
        String reason;
        if ("检修".equals(p.getStatus()) && "运行".equals(target)) {
            reason = "检修完成只能回到「停机」，不能直接开机";
        } else if ("检修".equals(p.getStatus()) && "检修".equals(target)) {
            reason = "机组已在检修中，不能重复进入检修";
        } else {
            List<String> next = Dict.pressNextTargets(p.getStatus());
            reason = next.isEmpty() ? "当前状态没有可执行的流转"
                    : "当前状态可转为：" + String.join("、", next);
        }
        return new BizException("不允许从「" + p.getStatus() + "」转为「" + target + "」：" + reason
                + "；机组保持「" + p.getStatus() + "」不变");
    }
}
