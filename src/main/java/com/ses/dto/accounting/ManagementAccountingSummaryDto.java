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

    /**
     * 予実比較の行。粒度は「組織 × 原価部門」に固定する。
     * 予算・待機原価はこの粒度でしか存在しないため、顧客/案件/営業まで含めたキーで集計すると
     * 実績行と予算行が永久に噛み合わず、行レベルの予算差が常に誤りになる。
     */
    private List<Row> rows;

    /** drilldown用の内訳。顧客/案件/営業まで分解した実績のみを持ち、予算列は持たない。 */
    private List<Detail> details;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private Long organizationId;
        private String organizationName;
        private Long costCenterId;
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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Detail {
        private Long organizationId;
        private String organizationName;
        private Long costCenterId;
        private Long customerId;
        private Long projectId;
        private Long salesUserId;
        private BigDecimal revenue;
        private BigDecimal cost;
        private BigDecimal grossProfit;
    }
}
