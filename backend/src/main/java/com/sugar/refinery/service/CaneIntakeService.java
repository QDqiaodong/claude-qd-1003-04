package com.sugar.refinery.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.composite.JuiceQuality;
import com.sugar.refinery.entity.CaneIntake;
import com.sugar.refinery.repository.CaneIntakeRepository;

/**
 * 甘蔗进厂验收业务。
 *
 * <p>核心规则：① 净重 = 毛重 - 皮重 - 扣杂，毛重必须大于皮重；
 * ② 扣杂率不得超过 {@link Dict#MAX_DEDUCTION_RATE}；③ 结论按品质 record 自动判定
 * （糖度不足拒收、纤维超标扣杂）；④ 只有「待验」的单能验收。</p>
 */
@Service
public class CaneIntakeService {

    private final CaneIntakeRepository intakeRepository;

    public CaneIntakeService(CaneIntakeRepository intakeRepository) {
        this.intakeRepository = intakeRepository;
    }

    /**
     * 磅单筛选：验收状态与品质结论可单独或组合使用，组合按「同时满足」过滤；
     * 未选择（空）的条件不参与限制，合法空结果原样返回空列表，不回退为全部。
     */
    @Transactional(readOnly = true)
    public List<CaneIntake> list(String status, String result) {
        if (status != null && !status.isBlank() && !Dict.isValidIntakeStatus(status)) {
            throw new BizException("验收状态不合法：" + status);
        }
        if (result != null && !result.isBlank() && !Dict.isValidIntakeResult(result)) {
            throw new BizException("品质结论不合法：" + result);
        }
        return intakeRepository.findAll().stream()
                .filter(c -> status == null || status.isBlank() || status.equals(c.getStatus()))
                .filter(c -> result == null || result.isBlank() || result.equals(c.getResult()))
                .toList();
    }

    public CaneIntake get(Long id) {
        return intakeRepository.findById(id)
                .orElseThrow(() -> new BizException("磅单不存在：" + id));
    }

    /**
     * 过磅登记。
     */
    @Transactional
    public CaneIntake create(CaneIntake input) {
        if (input.getTicketNo() == null || input.getTicketNo().isBlank()) {
            throw new BizException("磅单号不能为空");
        }
        intakeRepository.findByTicketNo(input.getTicketNo().trim()).ifPresent(c -> {
            throw new BizException("磅单号已存在：" + input.getTicketNo());
        });
        if (input.getSupplier() == null || input.getSupplier().isBlank()) {
            throw new BizException("蔗农/合作社不能为空");
        }
        if (input.getGrossKg() == null || input.getTareKg() == null) {
            throw new BizException("毛重与皮重必填");
        }
        // 规则①：毛重必须大于皮重
        if (input.getGrossKg().compareTo(input.getTareKg()) <= 0) {
            throw new BizException("毛重必须大于皮重");
        }
        BigDecimal deduction = input.getDeductionKg() == null ? BigDecimal.ZERO : input.getDeductionKg();
        if (deduction.signum() < 0) {
            throw new BizException("扣杂不能为负数");
        }
        if (input.getQuality() == null) {
            throw new BizException("必须录入品质三指标（糖度 / 纯度 / 纤维分）");
        }

        CaneIntake intake = new CaneIntake();
        intake.setTicketNo(input.getTicketNo().trim());
        intake.setSupplier(input.getSupplier());
        intake.setTruckNo(input.getTruckNo());
        intake.setGrossKg(input.getGrossKg());
        intake.setTareKg(input.getTareKg());
        intake.setDeductionKg(deduction);
        // 净重 = 毛重 - 皮重 - 扣杂
        intake.setNetKg(input.getGrossKg().subtract(input.getTareKg()).subtract(deduction));
        intake.setAllocatedKg(BigDecimal.ZERO);
        if (intake.getNetKg().signum() <= 0) {
            throw new BizException("扣杂后净重不为正，请核对过磅数据");
        }
        // 规则②：扣杂率上限
        if (intake.getDeductionRate() > Dict.MAX_DEDUCTION_RATE.doubleValue()) {
            throw new BizException("扣杂率 " + intake.getDeductionRate() + "% 超过上限 "
                    + Dict.MAX_DEDUCTION_RATE + "%，需复检");
        }
        intake.setQuality(input.getQuality());
        // 规则③：按 record 里的指标自动判结论
        JuiceQuality q = input.getQuality();
        intake.setResult(Dict.decideIntake(q.getBrix(), q.getFiber()));
        intake.setStatus("待验");
        intake.setIntakeAt(LocalDateTime.now());
        return intakeRepository.save(intake);
    }

    /**
     * 验收：确认结论并落状态。
     */
    @Transactional
    public CaneIntake accept(Long id) {
        CaneIntake intake = get(id);
        if (!"待验".equals(intake.getStatus())) {
            throw new BizException("只有「待验」的磅单能验收，当前状态：" + intake.getStatus());
        }
        if ("拒收".equals(intake.getResult())) {
            intake.setStatus("已拒收");
        } else {
            intake.setStatus("已验收");
        }
        return intakeRepository.save(intake);
    }

    /** 拒收 */
    @Transactional
    public CaneIntake reject(Long id, String reason) {
        CaneIntake intake = get(id);
        if (!"待验".equals(intake.getStatus())) {
            throw new BizException("只有「待验」的磅单能拒收，当前状态：" + intake.getStatus());
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException("拒收必须填写原因");
        }
        intake.setResult("拒收");
        intake.setStatus("已拒收");
        return intakeRepository.save(intake);
    }

    /** 当日进厂总量（吨） */
    @Transactional(readOnly = true)
    public BigDecimal dailyIntakeTon() {
        BigDecimal sum = BigDecimal.ZERO;
        for (CaneIntake c : intakeRepository.findAll()) {
            if (c.getIntakeAt() != null && c.getIntakeAt().toLocalDate().equals(LocalDateTime.now().toLocalDate())
                    && !"已拒收".equals(c.getStatus())) {
                sum = sum.add(c.getNetTon());
            }
        }
        return sum;
    }
}
