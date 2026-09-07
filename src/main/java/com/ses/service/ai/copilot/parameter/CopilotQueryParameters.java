package com.ses.service.ai.copilot.parameter;

import java.time.YearMonth;

/**
 * catalog queryごとの型付きパラメータ。SQL・raw filter・任意bean名は含めない。
 */
public record CopilotQueryParameters(
        String queryId,
        Integer fiscalYear,
        Integer forecastMonths,
        YearMonth fromMonth,
        YearMonth accountingMonth
) {
    public static CopilotQueryParameters ofQuery(String queryId) {
        return new CopilotQueryParameters(queryId, null, null, null, null);
    }
}
