package com.ses.service.ai.copilot.result;

import java.time.YearMonth;
import java.util.Set;

/** adapter出力と外部送信検査で共有するmetric識別子の正本。 */
public final class CopilotMetricKeys {
    public static final int MAX_CLAIM_KEYS_LENGTH = 8192;
    public static final String CASHFLOW_RECONCILIATION_KPI_SALES = "cashflow.reconciliation.kpiSales";
    public static final String CASHFLOW_RECONCILIATION_INVOICED_SUBTOTAL = "cashflow.reconciliation.invoicedSubtotal";
    public static final String CASHFLOW_RECONCILIATION_DIFFERENCE = "cashflow.reconciliation.difference";
    public static final String PROFIT_ROW_COUNT = "profit.rowCount";
    public static final String PROFIT_TOTAL_GROSS = "profit.totalGross";
    public static final String KPI_UTILIZATION = "kpi.utilization";
    public static final String KPI_BENCH_COUNT = "kpi.benchCount";
    public static final String KPI_REVENUE = "kpi.revenue";
    public static final String KPI_PROFIT_MARGIN = "kpi.profitMargin";
    public static final String KPI_UNACCEPTED_SALES = "kpi.unacceptedSales";
    public static final String KPI_AVG_ACCEPTANCE_DAYS = "kpi.avgAcceptanceDays";
    public static final String FORECAST_ROLLOFF_COUNT = "forecast.rolloffCount";
    public static final String ACCOUNTING_TOTAL_REVENUE = "accounting.totalRevenue";
    public static final String ACCOUNTING_TOTAL_GROSS_PROFIT = "accounting.totalGrossProfit";
    public static final String ACCOUNTING_REVENUE_VARIANCE = "accounting.revenueVariance";
    public static final String ACCOUNTING_GROSS_PROFIT_VARIANCE = "accounting.grossProfitVariance";
    public static final String CASHFLOW_INFLOW = "cashflow.inflow";
    public static final String CASHFLOW_OUTFLOW = "cashflow.outflow";
    public static final String CASHFLOW_NET = "cashflow.net";
    public static final String CASHFLOW_BALANCE = "cashflow.balance";
    public static final String FORECAST_UTILIZATION = "forecast.utilization";
    public static final String FORECAST_BENCH_COUNT = "forecast.benchCount";
    public static final String FORECAST_WORKING_COUNT = "forecast.workingCount";
    private static final Set<String> FIXED = Set.of(CASHFLOW_RECONCILIATION_KPI_SALES, CASHFLOW_RECONCILIATION_INVOICED_SUBTOTAL, CASHFLOW_RECONCILIATION_DIFFERENCE, PROFIT_ROW_COUNT, PROFIT_TOTAL_GROSS, KPI_UTILIZATION, KPI_BENCH_COUNT, KPI_REVENUE, KPI_PROFIT_MARGIN, KPI_UNACCEPTED_SALES, KPI_AVG_ACCEPTANCE_DAYS, FORECAST_ROLLOFF_COUNT, ACCOUNTING_TOTAL_REVENUE, ACCOUNTING_TOTAL_GROSS_PROFIT, ACCOUNTING_REVENUE_VARIANCE, ACCOUNTING_GROSS_PROFIT_VARIANCE);
    private static final Set<String> MONTHLY = Set.of(CASHFLOW_INFLOW, CASHFLOW_OUTFLOW, CASHFLOW_NET, CASHFLOW_BALANCE, FORECAST_UTILIZATION, FORECAST_BENCH_COUNT, FORECAST_WORKING_COUNT);
    private CopilotMetricKeys() { }

    public static String monthly(String prefix, String period) {
        if (!MONTHLY.contains(prefix)) throw new IllegalArgumentException("未登録の月次metricです");
        return prefix + "." + YearMonth.parse(period);
    }

    public static boolean isApproved(String key) {
        if (FIXED.contains(key)) return true;
        int separator = key.lastIndexOf('.');
        if (separator < 0 || !MONTHLY.contains(key.substring(0, separator))) return false;
        String period = key.substring(separator + 1);
        if (!period.matches("[0-9]{4}-[0-9]{2}")) return false;
        try { YearMonth.parse(period); return true; }
        catch (java.time.DateTimeException ex) { return false; }
    }
}
