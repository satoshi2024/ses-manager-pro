package com.ses.dto.quotation;

import jakarta.validation.constraints.Digits;
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
    @Digits(integer = 10, fraction = 0, message = "単価は10桁以内の整数で入力してください")
    private BigDecimal unitPrice;

    @PositiveOrZero(message = "精算下限は0以上で入力してください")
    @Digits(integer = 4, fraction = 1, message = "精算下限は小数1桁までで入力してください")
    private BigDecimal settlementHoursMin;

    @PositiveOrZero(message = "精算上限は0以上で入力してください")
    @Digits(integer = 4, fraction = 1, message = "精算上限は小数1桁までで入力してください")
    private BigDecimal settlementHoursMax;

    private LocalDate validUntil;

    @Size(max = 500, message = "備考は500文字以内で入力してください")
    private String remarks;
}
