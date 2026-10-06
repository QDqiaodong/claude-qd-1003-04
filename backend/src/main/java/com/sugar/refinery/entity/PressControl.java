package com.sugar.refinery.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 压榨机组操作流水（幂等记录）。
 *
 * <p>值班员每一次「调负荷 / 状态切换」意图都对应一条记录，request_id 唯一。
 * 重复点击、请求超时后重试带着同一个 request_id 进来：
 * 第一条请求正常执行并落流水；之后的重复请求不再改状态、不再改负荷、
 * 不再覆盖检修时间，而是直接把第一次落下的结果原样返回。</p>
 *
 * <p>流水与机组行的更新在同一个事务里提交；并发下唯一索引兜底，
 * 抢入失败的事务整体回滚后读取已落库的那一条返回，保证「一次意图只落一条一致结果」。</p>
 */
@Entity
@Table(name = "press_control")
@Getter
@Setter
public class PressControl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 幂等键：一次操作意图一个，重复点击 / 超时重试共用 */
    @Column(name = "request_id", nullable = false, unique = true, length = 36)
    private String requestId;

    /** 操作的机组 */
    @Column(name = "station_id", nullable = false)
    private Long stationId;

    /** 操作类型：SET_LOAD（调负荷）/ TRANSFER（状态切换） */
    @Column(name = "op_type", nullable = false, length = 16)
    private String opType;

    /** TRANSFER 的目标状态；SET_LOAD 为空 */
    @Column(name = "target_status", length = 16)
    private String targetStatus;

    /** SET_LOAD 的目标负荷；TRANSFER 为空 */
    @Column(name = "target_load_tph", precision = 8, scale = 2)
    private java.math.BigDecimal targetLoadTph;

    /** 操作落库时间（转检修时即本次检修时间的落库时刻） */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
