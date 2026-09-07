package com.ses.service.ai.copilot.parameter;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypedParameterBinderTest {

    private final TypedParameterBinder binder = new TypedParameterBinder();

    @Test
    void utilizationは月数を1から12に制限する() {
        CopilotQueryParameters params = binder.bind("dashboard.utilization-forecast", "稼働率 24ヶ月");
        assertEquals(12, params.forecastMonths());
    }

    @Test
    void cashflowは月数を1から36に制限する() {
        CopilotQueryParameters params = binder.bind("cashflow.forecast", "資金繰り 48ヶ月");
        assertEquals(36, params.forecastMonths());
    }

    @Test
    void 管理会計は年月を抽出する() {
        CopilotQueryParameters params = binder.bind("management-accounting.summary", "管理会計 2026-05");
        assertEquals(YearMonth.of(2026, 5), params.accountingMonth());
    }

    @Test
    void dashboardSummaryは年度を抽出する() {
        CopilotQueryParameters params = binder.bind("dashboard.summary", "2025年のKPI");
        assertEquals(2025, params.fiscalYear());
    }
}
