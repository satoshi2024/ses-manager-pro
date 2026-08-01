package com.ses.dto.crm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 商機の基本項目登録・更新リクエスト。stage変更は専用APIを利用する。 */
@Data
public class OpportunitySaveRequest {
    private Long id;
    @NotNull
    private Long customerId;
    @NotBlank
    @Size(max = 200)
    private String title;
    private String expectedStartMonth;
    private Integer durationMonths;
    private Integer requiredCount;
    private BigDecimal unitPrice;
    private BigDecimal expectedAmount;
    @Min(0)
    @Max(100)
    private Integer probability;
    private String probabilityOverrideReason;
    private Long ownerUserId;
    private LocalDate nextActionDate;
    private String competitor;
    private Integer version;
}
