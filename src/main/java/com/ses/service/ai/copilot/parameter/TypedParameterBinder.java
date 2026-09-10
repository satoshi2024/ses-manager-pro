package com.ses.service.ai.copilot.parameter;

import com.ses.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然言語質問から型付きパラメータへ束縛する。期間は bounded（utilization 1..12、cashflow 1..36）。
 */
@Component
public class TypedParameterBinder {
    private static final ZoneId TENANT_ZONE = ZoneId.of("Asia/Tokyo");
    private final Clock clock;

    public TypedParameterBinder() {
        this(Clock.system(TENANT_ZONE));
    }

    public TypedParameterBinder(Clock clock) {
        this.clock = clock == null ? Clock.system(TENANT_ZONE) : clock;
    }

    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})");
    private static final Pattern MONTH_COUNT_PATTERN = Pattern.compile("(\\d{1,2})\\s*(ヶ月|か月|个月|months?)");
    private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile("(20\\d{2})[-/](0?[1-9]|1[0-2])");

    public CopilotQueryParameters bind(String queryId, String question) {
        throw BusinessException.of(403, "TIME_CONTEXT_REQUIRED");
    }

    public CopilotQueryParameters bind(String queryId, String question, com.ses.service.ai.copilot.CopilotExecutionContext context) {
        if (context == null) {
            throw BusinessException.of(403, "TIME_CONTEXT_REQUIRED");
        }
        return bind(queryId, question, context.asOfMonth());
    }

    private CopilotQueryParameters bind(String queryId, String question, YearMonth referenceMonth) {
        if (queryId == null || queryId.isBlank()) {
            throw BusinessException.of(400, "INVALID_QUERY");
        }
        String text = question == null ? "" : question;
        return switch (queryId) {
            case "dashboard.summary" -> new CopilotQueryParameters(
                    queryId, resolveFiscalYear(text, referenceMonth), null, null, null);
            case "dashboard.profit-analysis" -> CopilotQueryParameters.ofQuery(queryId);
            case "dashboard.utilization-forecast" -> new CopilotQueryParameters(
                    queryId, null, clampUtilizationMonths(resolveMonthCount(text, 3)), null, null);
            case "management-accounting.summary" -> new CopilotQueryParameters(
                    queryId, null, null, null, resolveAccountingMonth(text, referenceMonth));
            case "cashflow.forecast" -> new CopilotQueryParameters(
                    queryId,
                    null,
                    clampCashflowMonths(resolveMonthCount(text, 6)),
                    resolveFromMonth(text, referenceMonth),
                    null);
            default -> throw BusinessException.of(404, "CATALOG_NOT_FOUND");
        };
    }

    private Integer resolveFiscalYear(String text, YearMonth referenceMonth) {
        Matcher matcher = YEAR_PATTERN.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return referenceMonth.getMonthValue() < 4 ? referenceMonth.getYear() - 1 : referenceMonth.getYear();
    }

    private YearMonth resolveAccountingMonth(String text, YearMonth referenceMonth) {
        Matcher matcher = YEAR_MONTH_PATTERN.matcher(text);
        if (matcher.find()) {
            return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }
        return referenceMonth;
    }

    private YearMonth resolveFromMonth(String text, YearMonth referenceMonth) {
        Matcher matcher = YEAR_MONTH_PATTERN.matcher(text);
        if (matcher.find()) {
            return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }
        return referenceMonth;
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
        throw BusinessException.of(403, "TIME_CONTEXT_REQUIRED");
    }

    public String parameterHash(CopilotQueryParameters parameters,
                                com.ses.service.ai.copilot.CopilotExecutionContext context) {
        if (context == null) {
            throw BusinessException.of(403, "TIME_CONTEXT_REQUIRED");
        }
        return parameterHash(parameters, context.asOfDate());
    }

    private String parameterHash(CopilotQueryParameters parameters, LocalDate asOf) {
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
        sb.append("asOf=").append(asOf);
        return sb.toString();
    }
}
