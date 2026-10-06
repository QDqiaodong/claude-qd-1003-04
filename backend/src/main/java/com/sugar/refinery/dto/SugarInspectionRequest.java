package com.sugar.refinery.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 质量复检提交请求。 */
@Getter
@Setter
public class SugarInspectionRequest {

    /** 页面读取到的当前版本；旧版本提交必须拒绝 */
    @NotNull(message = "缺少复检基准版本，请刷新页面后重试")
    private Integer baseVersion;

    /** 重复点击 / 请求重试共用的幂等键 */
    @NotBlank(message = "缺少请求标识，请刷新页面后重试")
    @Size(max = 36, message = "请求标识长度不能超过 36 位")
    private String requestId;

    @NotNull(message = "请录入复检时间")
    private LocalDateTime inspectedAt;

    @NotNull(message = "请录入复检糖度")
    private BigDecimal brix;

    @NotNull(message = "请录入复检纯度")
    private BigDecimal purity;

    @NotNull(message = "请录入复检纤维分")
    private BigDecimal fiber;

    @NotBlank(message = "请选择复检结论")
    private String conclusion;

    /** 结论为不通过时必填 */
    private String rejectReason;
}
