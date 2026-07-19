package com.ses.dto.quotation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class QuotationSaveRequest {
    @NotNull(message = "顧客は必須です")
    private Long customerId;

    private Long projectId;
    private Long engineerId;
    private Long proposalId;

    @NotBlank(message = "件名は必須です")
    @Size(max = 200, message = "件名は200文字以内で入力してください")
    private String title;

    @NotNull(message = "単価は必須です")
    @PositiveOrZero(message = "単価は0以上で入力してください")
    private BigDecimal unitPrice;

    private BigDecimal settlementHoursMin;
    private BigDecimal settlementHoursMax;
    private LocalDate validUntil;
    private String remarks;
}
