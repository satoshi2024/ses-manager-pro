package com.ses.service.ai.copilot.parameter;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypedParameterBinderTest {

    private final TypedParameterBinder binder = new TypedParameterBinder();

    @Test
    void utilizationは月数を1から12に制限する() {
        CopilotQueryParameters params = binder.bind("dashboard.utilization-forecast", "稼働率 24ヶ月", context());
        assertEquals(12, params.forecastMonths());
    }

    @Test
    void cashflowは月数を1から36に制限する() {
        CopilotQueryParameters params = binder.bind("cashflow.forecast", "資金繰り 48ヶ月", context());
        assertEquals(36, params.forecastMonths());
    }

    @Test
    void 管理会計は年月を抽出する() {
        CopilotQueryParameters params = binder.bind("management-accounting.summary", "管理会計 2026-05", context());
        assertEquals(YearMonth.of(2026, 5), params.accountingMonth());
    }

    @Test
    void dashboardSummaryは年度を抽出する() {
        CopilotQueryParameters params = binder.bind("dashboard.summary", "2025年のKPI", context());
        assertEquals(2025, params.fiscalYear());
    }

    private com.ses.service.ai.copilot.CopilotExecutionContext context() {
        return new com.ses.service.ai.copilot.CopilotExecutionContext(
                "tenant-a", 1L, Instant.parse("2026-09-08T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
    }
}
