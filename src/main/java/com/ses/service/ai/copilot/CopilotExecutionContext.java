package com.ses.service.ai.copilot;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 1 回の copilot query で共有する不変の実行コンテキスト。期間・asOf・freshness の一貫性を保つ。
 */
public record CopilotExecutionContext(
        Clock clock,
        Instant instant,
        ZoneId zoneId,
        LocalDate asOf,
        YearMonth yearMonth,
        String tenantId,
        String legalEntityId
) {

    public CopilotExecutionContext {
        Objects.requireNonNull(clock, "clock is required");
        Objects.requireNonNull(instant, "instant is required");
        Objects.requireNonNull(zoneId, "zoneId is required");
        Objects.requireNonNull(asOf, "asOf is required");
        Objects.requireNonNull(yearMonth, "yearMonth is required");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        legalEntityId = legalEntityId == null ? "" : legalEntityId;
    }
}
