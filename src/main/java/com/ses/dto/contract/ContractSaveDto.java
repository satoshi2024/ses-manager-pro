package com.ses.dto.contract;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ContractSaveDto {
    private Long id;
    private String contractNo;
    private Long proposalId;
    
    @NotNull(message = "要員は必須です")
    private Long engineerId;
    
    @NotNull(message = "案件は必須です")
    private Long projectId;
    
    @NotNull(message = "顧客は必須です")
    private Long customerId;
    
    private String contractType;
    
    @NotNull(message = "契約開始日は必須です")
    private LocalDate startDate;
    
    private LocalDate endDate;
    
    @NotNull(message = "売上単価は必須です")
    @PositiveOrZero(message = "売上単価は0以上で入力してください")
    private BigDecimal sellingPrice;
    
    @NotNull(message = "原価は必須です")
    @PositiveOrZero(message = "原価は0以上で入力してください")
    private BigDecimal costPrice;
    
    private BigDecimal settlementHoursMin;
    private BigDecimal settlementHoursMax;
    private String fractionRule;
    private Integer autoRenew;
    private String status;
    private String remarks;
    private Boolean directCommandFlag;
    private Long salesUserId;
    private String commissionBaseType;
    
    @PositiveOrZero(message = "インセンティブ率は0以上で入力してください")
    private BigDecimal commissionRate;
    
    private Long renewedFromContractId;
    private Long quotationId;

    @AssertTrue(message = "契約終了日は開始日以降を指定してください")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }

    @AssertTrue(message = "精算基準時間の上限は下限以上を指定してください")
    public boolean isSettlementHoursRangeValid() {
        return settlementHoursMin == null || settlementHoursMax == null
                || settlementHoursMin.compareTo(settlementHoursMax) <= 0;
    }
}
