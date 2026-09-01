package com.ses.service.ai.copilot.parameter;

import com.ses.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然言語質問から型付きパラメータへ束縛する。期間は bounded（utilization 1..12、cashflow 1..36）。
 */
@Component
public class TypedParameterBinder {

    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})");
    private static final Pattern MONTH_COUNT_PATTERN = Pattern.compile("(\\d{1,2})\\s*(ヶ月|か月|个月|months?)");
    private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile("(20\\d{2})[-/](0?[1-9]|1[0-2])");

    public CopilotQueryParameters bind(String queryId, String question) {
        if (queryId == null || queryId.isBlank()) {
            throw BusinessException.of(400, "INVALID_QUERY");
        }
        String text = question == null ? "" : question;
        return switch (queryId) {
            case "dashboard.summary" -> new CopilotQueryParameters(
                    queryId, resolveFiscalYear(text), null, null, null);
            case "dashboard.profit-analysis" -> CopilotQueryParameters.ofQuery(queryId);
            case "dashboard.utilization-forecast" -> new CopilotQueryParameters(
                    queryId, null, clampUtilizationMonths(resolveMonthCount(text, 3)), null, null);
            case "management-accounting.summary" -> new CopilotQueryParameters(
                    queryId, null, null, null, resolveAccountingMonth(text));
            case "cashflow.forecast" -> new CopilotQueryParameters(
                    queryId,
                    null,
                    clampCashflowMonths(resolveMonthCount(text, 6)),
                    resolveFromMonth(text),
                    null);
            default -> throw BusinessException.of(404, "CATALOG_NOT_FOUND");
        };
    }

    private Integer resolveFiscalYear(String text) {
        Matcher matcher = YEAR_PATTERN.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        YearMonth now = YearMonth.now();
        return now.getMonthValue() < 4 ? now.getYear() - 1 : now.getYear();
    }

    private YearMonth resolveAccountingMonth(String text) {
        Matcher matcher = YEAR_MONTH_PATTERN.matcher(text);
        if (matcher.find()) {
            return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }
        return YearMonth.now();
    }

    private YearMonth resolveFromMonth(String text) {
        Matcher matcher = YEAR_MONTH_PATTERN.matcher(text);
        if (matcher.find()) {
            return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }
        return YearMonth.now();
    }

    private int resolveMonthCount(String text, int defaultValue) {
        Matcher matcher = MONTH_COUNT_PATTERN.matcher(text.toLowerCase());
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return defaultValue;
    }

    private int clampUtilizationMonths(int months) {
        return Math.max(1, Math.min(months, 12));
    }

    private int clampCashflowMonths(int months) {
        return Math.max(1, Math.min(months, 36));
    }

    public String parameterHash(CopilotQueryParameters parameters) {
        StringBuilder sb = new StringBuilder();
        sb.append(parameters.queryId()).append('|');
        if (parameters.fiscalYear() != null) {
            sb.append("fy=").append(parameters.fiscalYear()).append('|');
        }
        if (parameters.forecastMonths() != null) {
            sb.append("months=").append(parameters.forecastMonths()).append('|');
        }
        if (parameters.fromMonth() != null) {
            sb.append("from=").append(parameters.fromMonth()).append('|');
        }
        if (parameters.accountingMonth() != null) {
            sb.append("acct=").append(parameters.accountingMonth()).append('|');
        }
        sb.append("asOf=").append(LocalDate.now());
        return sb.toString();
    }
}
