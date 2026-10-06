package com.sugar.refinery.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.dto.WashQueueItem;
import com.sugar.refinery.entity.EvapPan;
import com.sugar.refinery.entity.PanWash;
import com.sugar.refinery.repository.EvapPanRepository;
import com.sugar.refinery.repository.PanWashRepository;

/**
 * 洗罐排程闭环业务。
 *
 * <p>核心规则：
 * <ol>
 *   <li>待洗清单按罐型 / 当前状态 / 上次洗罐时间查看，距上次洗罐超过
 *       {@link Dict#PAN_WASH_DUE_HOURS} 小时的罐必须标为「需先洗罐」；</li>
 *   <li>停用中的罐不能进入洗罐（落单与开始都拦）；同一罐不能同时存在两笔未完成安排
 *       （行锁内校验 + 数据库生成列唯一索引兜底）；</li>
 *   <li>状态机 待执行 → 清洗中 → 已完成：待执行可取消；清洗中不能重复开始、不能改换罐型；
 *       完成时在同一事务写完成时间、回写罐体上次洗罐时间并恢复运行资格（转回运行）；</li>
 *   <li>所有写路径都是单事务，任一步失败整体回滚，罐仍停在可安全重试的状态，
 *       绝无「只改了状态却没写洗罐时间」的半成品。失败原因落库，刷新后仍可判断可否重试。</li>
 * </ol>
 */
@Service
public class PanWashService {

    /** 所有写路径都是单事务，失败即整体回滚，因此任何失败都可安全重试 */
    private static final String SAFE_RETRY = "；本次未落下任何数据，可安全重试";

    private final PanWashRepository washRepository;
    private final EvapPanRepository panRepository;

    public PanWashService(PanWashRepository washRepository, EvapPanRepository panRepository) {
        this.washRepository = washRepository;
        this.panRepository = panRepository;
    }

    // ------------------------------------------------------------------
    // 待洗清单
    // ------------------------------------------------------------------

    /**
     * 待洗清单：罐体（罐型 / 状态 / 距上次洗罐时长 / 运行资格）与其未完成洗罐安排
     * （洗罐窗口 / 排程状态 / 失败可否重试）逐行对齐。
     *
     * @param panType   罐型过滤：蒸发罐 / 结晶罐，空为全部
     * @param panStatus 罐体当前状态过滤：运行 / 洗罐 / 停用，空为全部
     * @param dueOnly   只看「需先洗罐」（超 72 小时）的罐
     */
    @Transactional(readOnly = true)
    public List<WashQueueItem> queue(String panType, String panStatus, boolean dueOnly) {
        List<EvapPan> pans;
        if (panType != null && !panType.isBlank() && panStatus != null && !panStatus.isBlank()) {
            pans = panRepository.findByPanType(panType).stream()
                    .filter(p -> panStatus.equals(p.getStatus())).collect(Collectors.toList());
        } else if (panType != null && !panType.isBlank()) {
            pans = panRepository.findByPanType(panType);
        } else if (panStatus != null && !panStatus.isBlank()) {
            pans = panRepository.findByStatus(panStatus);
        } else {
            pans = panRepository.findAll();
        }

        // 一笔未完成安排对应一个罐：聚成 map 直接对齐，避免每罐查一次
        Map<Long, PanWash> activeMap = new HashMap<>();
        for (PanWash w : washRepository.findAll()) {
            if (w.isActive() && !activeMap.containsKey(w.getPanId())) {
                activeMap.put(w.getPanId(), w);
            }
        }

        List<WashQueueItem> rows = new ArrayList<>();
        for (EvapPan p : pans) {
            if (dueOnly && !p.isWashDue()) {
                continue;
            }
            rows.add(WashQueueItem.from(p, activeMap.get(p.getId())));
        }
        // 需先洗罐的排最前（未洗过 / 逾期更久的更靠前），其余按罐号
        rows.sort(Comparator
                .comparing((WashQueueItem r) -> !r.washDue())
                .thenComparing(r -> r.hoursSinceWash() == null ? Long.MAX_VALUE : -r.hoursSinceWash())
                .thenComparing(WashQueueItem::panCode));
        return rows;
    }

    // ------------------------------------------------------------------
    // 洗罐安排 CRUD 与状态机
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PanWash> list(String status) {
        if (status != null && !status.isBlank()) {
            return washRepository.findByStatusOrderByIdDesc(status);
        }
        return washRepository.findAllByOrderByIdDesc();
    }

    /**
     * 安排洗罐窗口：锁罐 → 校验（停用不可、同一罐不得有未完成安排）→ 快照罐号 / 罐型落单。
     */
    @Transactional
    public PanWash schedule(Long panId, LocalDateTime windowAt) {
        if (panId == null) {
            throw new BizException("请选择要安排洗罐的罐");
        }
        if (windowAt == null) {
            throw new BizException("请选择洗罐窗口（计划开始时间）");
        }
        EvapPan pan = lockPan(panId);
        if ("停用".equals(pan.getStatus())) {
            throw new BizException(pan.getPanName() + " 停用中，不能直接进入洗罐，请先恢复罐体状态"
                    + SAFE_RETRY);
        }
        if (!washRepository.findActiveByPanId(panId).isEmpty()) {
            throw new BizException(pan.getPanName() + " 已存在未完成的洗罐安排，同一罐不能重复安排"
                    + SAFE_RETRY);
        }
        PanWash w = new PanWash();
        w.setWashNo(nextWashNo());
        w.setPanId(pan.getId());
        w.setPanCode(pan.getPanCode());
        w.setPanName(pan.getPanName());
        // 罐型快照：进入清洗中后不能改换罐型（实体本身不提供任何改罐型入口）
        w.setPanType(pan.getPanType());
        w.setWindowAt(windowAt);
        w.setStatus("待执行");
        w.setCreatedAt(LocalDateTime.now());
        // saveAndFlush：让生成列唯一索引冲突在本事务内立即暴露并整体回滚
        return washRepository.saveAndFlush(w);
    }

    /** 开始：待执行 → 清洗中，同时把罐转为「洗罐」。清洗中不能重复开始 */
    @Transactional
    public PanWash start(Long id) {
        PanWash w = lockWash(id);
        if ("清洗中".equals(w.getStatus())) {
            throw new BizException(w.getWashNo() + " 已在清洗中，不能重复开始");
        }
        if (!Dict.canTransferWash(w.getStatus(), "清洗中")) {
            throw new BizException("只有「待执行」的安排能开始清洗，当前状态：" + w.getStatus() + SAFE_RETRY);
        }
        EvapPan pan = lockPan(w.getPanId());
        assertSamePanType(w, pan);
        if ("停用".equals(pan.getStatus())) {
            throw new BizException(pan.getPanName() + " 停用中，不能直接进入洗罐，请先恢复罐体状态"
                    + SAFE_RETRY);
        }
        if (!"运行".equals(pan.getStatus())) {
            throw new BizException(pan.getPanName() + " 当前为「" + pan.getStatus()
                    + "」，无法开始清洗" + SAFE_RETRY);
        }
        pan.setStatus("洗罐");
        panRepository.save(pan);

        w.setStatus("清洗中");
        w.setStartedAt(LocalDateTime.now());
        w.setFailedReason(null);
        w.setFailedAt(null);
        return washRepository.save(w);
    }

    /**
     * 完成：清洗中 → 已完成。同一事务里写完成时间 + 回写罐体上次洗罐时间 + 转回「运行」
     * 恢复运行资格，三件事同生共死，不存在只改状态没有洗罐时间的情况。
     */
    @Transactional
    public PanWash complete(Long id) {
        PanWash w = lockWash(id);
        if (!Dict.canTransferWash(w.getStatus(), "已完成")) {
            throw new BizException("只有「清洗中」的安排能完成洗罐，当前状态：" + w.getStatus() + SAFE_RETRY);
        }
        EvapPan pan = lockPan(w.getPanId());
        assertSamePanType(w, pan);
        if (!"洗罐".equals(pan.getStatus())) {
            // 正常路径被排程联动锁死不会出现；出现说明数据被外部改动，整体回滚停在清洗中可重试
            throw new BizException(pan.getPanName() + " 当前为「" + pan.getStatus()
                    + "」而非清洗中，无法完成洗罐" + SAFE_RETRY);
        }
        LocalDateTime now = LocalDateTime.now();
        // 完成时间与罐体上次洗罐时间必须同一事务、同一时刻一起写
        w.setCompletedAt(now);
        w.setStatus("已完成");
        w.setFailedReason(null);
        w.setFailedAt(null);
        pan.setLastWashAt(now);
        // 恢复运行资格：洗完转回运行，距上次洗罐归零，「需先洗罐」标记随之消除
        pan.setStatus("运行");
        panRepository.save(pan);
        return washRepository.save(w);
    }

    /** 取消：待执行 → 已取消（清洗中不能取消）。取消的安排不再占罐 */
    @Transactional
    public PanWash cancel(Long id) {
        PanWash w = lockWash(id);
        if (!Dict.canTransferWash(w.getStatus(), "已取消")) {
            throw new BizException("只有「待执行」的安排能取消，当前状态：" + w.getStatus() + SAFE_RETRY);
        }
        w.setStatus("已取消");
        w.setCancelledAt(LocalDateTime.now());
        return washRepository.save(w);
    }

    /**
     * 一键清洗：待执行或清洗中 → 已完成，一笔事务走完。
     * 待执行则先联动转「洗罐」；随后与 {@link #complete} 同口径写完成时间、
     * 回写洗罐时间并恢复运行资格。任一步失败整体回滚，停在原状态可安全重试。
     */
    @Transactional
    public PanWash execute(Long id) {
        PanWash w = lockWash(id);
        boolean fromPending = "待执行".equals(w.getStatus());
        if (!fromPending && !"清洗中".equals(w.getStatus())) {
            throw new BizException("只有「待执行」或「清洗中」的安排能执行清洗，当前状态："
                    + w.getStatus() + SAFE_RETRY);
        }
        EvapPan pan = lockPan(w.getPanId());
        assertSamePanType(w, pan);
        LocalDateTime now = LocalDateTime.now();

        if (fromPending) {
            if ("停用".equals(pan.getStatus())) {
                throw new BizException(pan.getPanName() + " 停用中，不能直接进入洗罐，请先恢复罐体状态"
                        + SAFE_RETRY);
            }
            if (!"运行".equals(pan.getStatus())) {
                throw new BizException(pan.getPanName() + " 当前为「" + pan.getStatus()
                        + "」，无法执行清洗" + SAFE_RETRY);
            }
            w.setStartedAt(now);
            pan.setStatus("洗罐");
            panRepository.save(pan);
        } else if (!"洗罐".equals(pan.getStatus())) {
            throw new BizException(pan.getPanName() + " 当前为「" + pan.getStatus()
                    + "」而非清洗中，无法完成洗罐" + SAFE_RETRY);
        }

        // 完成时间与罐体上次洗罐时间同一事务一起写，并恢复运行资格
        w.setCompletedAt(now);
        w.setStatus("已完成");
        w.setFailedReason(null);
        w.setFailedAt(null);
        pan.setLastWashAt(now);
        pan.setStatus("运行");
        panRepository.save(pan);
        return washRepository.save(w);
    }

    /**
     * 记录最近一次执行失败：主事务已整体回滚（不留半成品），这里用独立事务只落失败原因，
     * 供值班员刷新后看到「是否可以重试」。绝不在这里改排程状态——失败罐仍停在可安全重试的状态。
     */
    @Transactional
    public void recordFailure(Long id, String reason) {
        washRepository.findById(id).ifPresent(w -> {
            if (!w.isActive()) {
                return;
            }
            String r = reason == null || reason.isBlank() ? "洗罐执行失败" : reason.trim();
            if (r.length() > 250) {
                r = r.substring(0, 250);
            }
            w.setFailedReason(r);
            w.setFailedAt(LocalDateTime.now());
            washRepository.save(w);
        });
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private PanWash lockWash(Long id) {
        return washRepository.lockById(id)
                .orElseThrow(() -> new BizException("洗罐安排不存在：" + id + SAFE_RETRY));
    }

    private EvapPan lockPan(Long id) {
        return panRepository.lockById(id)
                .orElseThrow(() -> new BizException("罐体不存在：" + id + SAFE_RETRY));
    }

    /** 罐型快照与罐体当前罐型必须一致；罐型不可改换，正常路径恒成立 */
    private void assertSamePanType(PanWash w, EvapPan pan) {
        if (!w.getPanType().equals(pan.getPanType())) {
            throw new BizException(w.getWashNo() + " 罐型快照（" + w.getPanType()
                    + "）与罐体罐型（" + pan.getPanType() + "）不一致，洗罐安排不能改换罐型" + SAFE_RETRY);
        }
    }

    private String nextWashNo() {
        return "PW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
