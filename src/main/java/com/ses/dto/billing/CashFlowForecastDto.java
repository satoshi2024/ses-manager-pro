package com.ses.dto.billing;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CashFlowForecastDto {
    private List<CashFlowMonthDto> months;
    private BigDecimal alertThreshold;
    /** 起点月の売上口径突合（全社KPIとの照合用）。 */
    private ReconciliationDto reconciliation;

    /**
     * 起点月について、全社KPI（{@code MonthlyRevenueCalcService} 口径の発生ベース売上）と
     * 当月請求分の税抜合計を並べ、差分を提示する（FR-05 要件1.4）。
     *
     * <p>両者は本来一致する（請求書は確定実績の billing_amount から生成されるため）。
     * 差分が出るのは「実績未確定で契約単価にフォールバックした契約」「未請求の確定実績」が
     * ある場合で、その分だけ入金予定に現れていない売上があることを意味する。
     */
    @Data
    public static class ReconciliationDto {
        /** 突合の基準月（予測の起点月）。 */
        private String month;
        /** 全社KPI口径の当月売上（税抜・発生ベース）。 */
        private BigDecimal kpiSales;
        /** 当月請求分の税抜合計。 */
        private BigDecimal invoicedSubtotal;
        /** invoicedSubtotal − kpiSales。0 なら口径一致。 */
        private BigDecimal difference;
    }

    @Data
    public static class CashFlowMonthDto {
        private String month;
        private BigDecimal inflow;
        private BigDecimal outflow;
        private BigDecimal net;
        private BigDecimal balance;
        
        // 内訳（フロントエンドのツールチップ等で使用）
        private BigDecimal unpaidInvoiceTotal;
        private BigDecimal bpPaymentTotal;
        private BigDecimal payrollTotal;
        private BigDecimal fixedCost;
    }
}
