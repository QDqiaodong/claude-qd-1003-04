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
 * ② 只有「运行」的机组能调负荷；③ 状态流转：运行 ↔ 停机，检修完回停机。</p>
 */
@Service
public class PressStationService {

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
        p.setLastMaintainAt(LocalDateTime.now());
        return pressRepository.save(p);
    }

    /** 调整负荷 */
    @Transactional
    public PressStation setLoad(Long id, BigDecimal loadTph) {
        PressStation p = get(id);
        if (!"运行".equals(p.getStatus())) {
            throw new BizException("只有「运行」的机组能调负荷，当前状态：" + p.getStatus());
        }
        if (loadTph == null || loadTph.signum() < 0) {
            throw new BizException("负荷不能为负数");
        }
        // 规则①：不得超过设计处理量
        if (loadTph.compareTo(p.getDesignCapacityTph()) > 0) {
            throw new BizException("负荷 " + loadTph + " 超过设计处理量 "
                    + p.getDesignCapacityTph() + " 吨/小时");
        }
        p.setCurrentLoadTph(loadTph);
        return pressRepository.save(p);
    }

    @Transactional
    public PressStation transfer(Long id, String target) {
        PressStation p = get(id);
        if (!Dict.isValidPressStatus(target)) {
            throw new BizException("机组状态不合法：" + target);
        }
        if (!Dict.canTransferPress(p.getStatus(), target)) {
            throw new BizException("不允许从「" + p.getStatus() + "」转为「" + target + "」");
        }
        if ("检修".equals(target)) {
            p.setLastMaintainAt(LocalDateTime.now());
            p.setCurrentLoadTph(BigDecimal.ZERO);
        }
        p.setStatus(target);
        return pressRepository.save(p);
    }
}
