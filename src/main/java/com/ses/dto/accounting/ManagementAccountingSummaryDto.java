package com.ses.dto.accounting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** 管理会計の予実サマリー。金額はMonthlyRevenueCalcServiceの共通口径で算出する。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagementAccountingSummaryDto {
    private String month;
    private BigDecimal totalRevenue;
    private BigDecimal totalCost;
    private BigDecimal totalGrossProfit;
    private BigDecimal totalBudgetRevenue;
    private BigDecimal totalBudgetGrossProfit;
    private BigDecimal revenueVariance;
    private BigDecimal grossProfitVariance;
    private List<Row> rows;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private Long organizationId;
        private String organizationName;
        private Long costCenterId;
        private Long customerId;
        private Long projectId;
        private Long salesUserId;
        private BigDecimal revenue;
        private BigDecimal cost;
        private BigDecimal grossProfit;
        private BigDecimal budgetRevenue;
        private BigDecimal budgetGrossProfit;
        private BigDecimal revenueVariance;
        private BigDecimal grossProfitVariance;
        private Integer utilizationCount;
        private Integer hireCount;
        private BigDecimal waitCost;
    }
}
