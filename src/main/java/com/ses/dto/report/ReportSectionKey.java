package com.ses.dto.report;

import java.util.List;

/** 月次管理レポートで受入済みのsectionキー。ServiceDesk/SLAはNF-02受入まで含めない。 */
public final class ReportSectionKey {

    public static final String SALES = "sales";
    public static final String GROSS_PROFIT = "gross-profit";
    public static final String REVENUE_FORECAST = "revenue-forecast";
    public static final String UTILIZATION = "utilization";
    public static final String BENCH = "bench";
    public static final String MANAGEMENT_ACCOUNTING = "management-accounting";
    public static final String CASH_FLOW = "cash-flow";
    public static final String AR_AGING = "ar-aging";
    public static final String BP_PAYMENT_PLAN = "bp-payment-plan";
    public static final String CONTRACT_RENEWAL_OUTLOOK = "contract-renewal-outlook";

    public static final List<String> DEFAULT_ORDER = List.of(
            SALES, GROSS_PROFIT, REVENUE_FORECAST, UTILIZATION, BENCH,
            MANAGEMENT_ACCOUNTING, CASH_FLOW, AR_AGING, BP_PAYMENT_PLAN,
            CONTRACT_RENEWAL_OUTLOOK);

    private ReportSectionKey() {
    }
}
