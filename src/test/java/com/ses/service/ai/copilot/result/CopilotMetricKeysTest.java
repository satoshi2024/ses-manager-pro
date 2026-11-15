package com.ses.service.ai.copilot.result;

import org.junit.jupiter.api.Test;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** adapterと送信gateのmetric正本および最大期間の余裕を検証する。 */
class CopilotMetricKeysTest {
    @Test
    void 全登録metricと36か月のcashflowは同じ正本から作成して上限内に収まる() throws Exception {
        for (var field : CopilotMetricKeys.class.getFields()) {
            if (field.getType() != String.class) continue;
            String key = (String) field.get(null);
            if (CopilotMetricKeys.isApproved(key)) assertThat(key).doesNotContain(" ");
            else assertThat(CopilotMetricKeys.isApproved(CopilotMetricKeys.monthly(key, "2026-11"))).isTrue();
        }
        List<String> keys = new ArrayList<>();
        for (int month = 0; month < 36; month++) {
            String period = YearMonth.of(2026, 1).plusMonths(month).toString();
            for (String prefix : List.of(CopilotMetricKeys.CASHFLOW_INFLOW, CopilotMetricKeys.CASHFLOW_OUTFLOW,
                    CopilotMetricKeys.CASHFLOW_NET, CopilotMetricKeys.CASHFLOW_BALANCE)) {
                keys.add(CopilotMetricKeys.monthly(prefix, period));
            }
        }
        keys.addAll(List.of(CopilotMetricKeys.CASHFLOW_RECONCILIATION_KPI_SALES,
                CopilotMetricKeys.CASHFLOW_RECONCILIATION_INVOICED_SUBTOTAL,
                CopilotMetricKeys.CASHFLOW_RECONCILIATION_DIFFERENCE));
        assertThat(keys).allMatch(CopilotMetricKeys::isApproved);
        assertThat(String.join(",", keys).length()).isEqualTo(3528);
        assertThat(String.join(",", keys).length()).isLessThan(CopilotMetricKeys.MAX_CLAIM_KEYS_LENGTH);
    }

    @Test
    void 未登録名や存在しない年月は拒否する() {
        for (String invalid : List.of("cashflow.forecast", "cashflow.inflow.2026-13", "forecast.utilization.2026-00", "kpi.unknown")) {
            assertThat(CopilotMetricKeys.isApproved(invalid)).isFalse();
        }
    }
}
