package com.sugar.refinery.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.entity.EvapPan;
import com.sugar.refinery.entity.PanWash;
import com.sugar.refinery.repository.EvapPanRepository;
import com.sugar.refinery.repository.PanWashRepository;

/**
 * 蒸发 / 结晶罐业务。
 *
 * <p>核心规则：① 罐型只能是蒸发罐或结晶罐，容积必须大于 0；
 * ② 洗罐中的罐不允许进新料（不允许直接转运行，必须先洗完）；
 * ③ 距上次洗罐超过 72 小时不允许继续运行，必须先洗罐。</p>
 *
 * <p>洗罐排程闭环期间，罐体「运行 ↔ 洗罐」由 {@link PanWashService} 在同一事务联动维护，
 * 手工状态流转不能绕过它：清洗中不许手动转任何状态，有待执行安排时不许手动转入洗罐。</p>
 */
@Service
public class EvapPanService {

    private final EvapPanRepository panRepository;
    private final PanWashRepository washRepository;

    public EvapPanService(EvapPanRepository panRepository, PanWashRepository washRepository) {
        this.panRepository = panRepository;
        this.washRepository = washRepository;
    }

    public List<EvapPan> list(String panType, String status) {
        if (panType != null && !panType.isBlank()) {
            return panRepository.findByPanType(panType);
        }
        if (status != null && !status.isBlank()) {
            return panRepository.findByStatus(status);
        }
        return panRepository.findAll();
    }

    public EvapPan get(Long id) {
        return panRepository.findById(id)
                .orElseThrow(() -> new BizException("罐体不存在：" + id));
    }

    @Transactional
    public EvapPan create(EvapPan input) {
        if (input.getPanCode() == null || input.getPanCode().isBlank()) {
            throw new BizException("罐号不能为空");
        }
        panRepository.findByPanCode(input.getPanCode().trim()).ifPresent(p -> {
            throw new BizException("罐号已存在：" + input.getPanCode());
        });
        if (!Dict.isValidPanType(input.getPanType())) {
            throw new BizException("罐型不合法：" + input.getPanType());
        }
        if (input.getCapacityM3() == null || input.getCapacityM3().signum() <= 0) {
            throw new BizException("容积必须大于 0");
        }
        EvapPan p = new EvapPan();
        p.setPanCode(input.getPanCode().trim());
        p.setPanName(input.getPanName() == null ? input.getPanCode() : input.getPanName());
        p.setPanType(input.getPanType());
        p.setCapacityM3(input.getCapacityM3());
        p.setStatus(input.getStatus() == null || input.getStatus().isBlank() ? "停用" : input.getStatus());
        if (!Dict.isValidPanStatus(p.getStatus())) {
            throw new BizException("罐体状态不合法：" + p.getStatus());
        }
        p.setLastWashAt(LocalDateTime.now());
        return panRepository.save(p);
    }

    /**
     * 直接记录一次洗罐（老入口）。排程闭环上线后，正常洗罐应走洗罐安排
     * （待执行 → 清洗中 → 已完成）；此入口仍保留，但同样受互锁保护：
     * 停用中的罐不能洗，已有未完成安排的罐不能绕过安排直接洗。
     */
    @Transactional
    public EvapPan wash(Long id) {
        EvapPan p = get(id);
        if ("停用".equals(p.getStatus())) {
            throw new BizException("停用中的罐不能直接洗罐，请先转为洗罐状态");
        }
        PanWash active = findActiveWash(id);
        if (active != null) {
            throw new BizException("该罐存在「" + active.getStatus() + "」的洗罐安排 "
                    + active.getWashNo() + "，请在洗罐排程里推进或取消，不能绕过安排直接洗罐");
        }
        p.setLastWashAt(LocalDateTime.now());
        return panRepository.save(p);
    }

    @Transactional
    public EvapPan transfer(Long id, String target) {
        EvapPan p = get(id);
        if (!Dict.isValidPanStatus(target)) {
            throw new BizException("罐体状态不合法：" + target);
        }
        if (!Dict.canTransferPan(p.getStatus(), target)) {
            throw new BizException("不允许从「" + p.getStatus() + "」转为「" + target + "」");
        }
        PanWash active = findActiveWash(id);
        if (active != null) {
            if ("清洗中".equals(active.getStatus())) {
                throw new BizException("洗罐安排 " + active.getWashNo()
                        + " 清洗中，请完成该安排（完成时自动恢复运行），不能手动改罐体状态");
            }
            if ("洗罐".equals(target)) {
                throw new BizException("该罐已有待执行的洗罐安排 " + active.getWashNo()
                        + "，请在排程里开始清洗，不能绕过安排手动转入洗罐");
            }
        }
        // 规则③：超 72 小时未洗的罐不允许继续运行
        if ("运行".equals(target) && p.isWashDue()) {
            throw new BizException("距上次洗罐已 " + p.getHoursSinceWash() + " 小时，超过 "
                    + Dict.PAN_WASH_DUE_HOURS + " 小时必须先洗罐");
        }
        p.setStatus(target);
        return panRepository.save(p);
    }

    /** 该罐当前未完成的洗罐安排（待执行 / 清洗中），无则 null；正常至多一笔 */
    private PanWash findActiveWash(Long panId) {
        List<PanWash> actives = washRepository.findActiveByPanId(panId);
        return actives.isEmpty() ? null : actives.get(0);
    }

    /** 在运罐体总容积（立方米） */
    public BigDecimal runningCapacity() {
        BigDecimal sum = BigDecimal.ZERO;
        for (EvapPan p : panRepository.findByStatus("运行")) {
            sum = sum.add(p.getCapacityM3());
        }
        return sum;
    }
}
