package com.ses.service.ai.copilot;

import com.ses.service.accounting.AccountingTimezoneResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotExecutionContextFactoryTest {

    @Mock
    private AccountingTimezoneResolver timezoneResolver;

    @InjectMocks
    private CopilotExecutionContextFactory factory;

    @Test
    void JVMがUTCでもテナントAsiaTokyoの年末境界を解決する() {
        Clock utcClock = Clock.fixed(Instant.parse("2025-12-31T15:30:00Z"), ZoneOffset.UTC);
        when(timezoneResolver.getTenantZoneId()).thenReturn(ZoneId.of("Asia/Tokyo"));

        CopilotExecutionContext context = new CopilotExecutionContextFactory(utcClock, timezoneResolver).create();

        assertEquals(LocalDate.of(2026, 1, 1), context.asOf());
        assertEquals(YearMonth.of(2026, 1), context.yearMonth());
        assertEquals(ZoneId.of("Asia/Tokyo"), context.zoneId());
    }

    @Test
    void JVMがUTCでもテナントAsiaTokyoの月末直前を解決する() {
        Clock utcClock = Clock.fixed(Instant.parse("2025-12-31T14:59:59Z"), ZoneOffset.UTC);
        when(timezoneResolver.getTenantZoneId()).thenReturn(ZoneId.of("Asia/Tokyo"));

        CopilotExecutionContext context = new CopilotExecutionContextFactory(utcClock, timezoneResolver).create();

        assertEquals(LocalDate.of(2025, 12, 31), context.asOf());
        assertEquals(YearMonth.of(2025, 12), context.yearMonth());
    }

    @Test
    void 同一ClockからinstantとasOfが一貫する() {
        Instant fixed = Instant.parse("2026-09-07T10:15:30Z");
        Clock clock = Clock.fixed(fixed, ZoneId.of("UTC"));
        when(timezoneResolver.getTenantZoneId()).thenReturn(ZoneId.of("Asia/Tokyo"));

        CopilotExecutionContext context = new CopilotExecutionContextFactory(clock, timezoneResolver).create();

        assertEquals(fixed, context.instant());
        assertEquals(fixed.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate(), context.asOf());
    }
}
