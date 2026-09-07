package com.ses.service.ai.copilot.parameter;

import com.ses.service.ai.copilot.CopilotExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedParameterBinderTest {

    private static final CopilotExecutionContext CONTEXT = new CopilotExecutionContext(
            Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneId.of("Asia/Tokyo")),
            Instant.parse("2026-09-07T00:00:00Z"),
            ZoneId.of("Asia/Tokyo"),
            LocalDate.of(2026, 9, 7),
            YearMonth.of(2026, 9),
            "default",
            "");

    private final TypedParameterBinder binder = new TypedParameterBinder();

    @Test
    void utilizationは月数を1から12に制限する() {
        CopilotQueryParameters params = binder.bind("dashboard.utilization-forecast", "稼働率 24ヶ月", CONTEXT);
        assertEquals(12, params.forecastMonths());
    }

    @Test
    void cashflowは月数を1から36に制限する() {
        CopilotQueryParameters params = binder.bind("cashflow.forecast", "資金繰り 48ヶ月", CONTEXT);
        assertEquals(36, params.forecastMonths());
    }

    @Test
    void 管理会計は年月を抽出する() {
        CopilotQueryParameters params = binder.bind("management-accounting.summary", "管理会計 2026-05", CONTEXT);
        assertEquals(YearMonth.of(2026, 5), params.accountingMonth());
    }

    @Test
    void dashboardSummaryは年度を抽出する() {
        CopilotQueryParameters params = binder.bind("dashboard.summary", "2025年のKPI", CONTEXT);
        assertEquals(2025, params.fiscalYear());
    }

    @Test
    void parameterHashはcontextのasOfとzoneを含む() {
        CopilotQueryParameters params = binder.bind("dashboard.summary", "KPI", CONTEXT);
        String canonical = binder.parameterHash(params, CONTEXT);
        assertTrue(canonical.contains("asOf=2026-09-07"));
        assertTrue(canonical.contains("zone=Asia/Tokyo"));
    }
}
