package com.ses.service.ai.copilot;

import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * query 開始時に一度だけ {@link CopilotExecutionContext} を生成する。
 * タイムゾーン正本は {@link AccountingTimezoneResolver} を使用する。
 */
@Component
@RequiredArgsConstructor
public class CopilotExecutionContextFactory {

    private final Clock clock;
    private final AccountingTimezoneResolver timezoneResolver;

    public CopilotExecutionContext create() {
        String tenantId = AccountingTenantContextHolder.getTenantId();
        ZoneId zoneId = timezoneResolver.getTenantZoneId();
        Instant instant = clock.instant();
        LocalDate asOf = instant.atZone(zoneId).toLocalDate();
        YearMonth yearMonth = YearMonth.from(asOf);
        return new CopilotExecutionContext(
                clock,
                instant,
                zoneId,
                asOf,
                yearMonth,
                tenantId,
                "");
    }
}
