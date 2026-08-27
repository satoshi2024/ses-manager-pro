package com.ses.service.servicedesk;

import com.ses.entity.ServiceSlaPolicy;
import com.ses.entity.WorkCalendarDay;
import com.ses.mapper.WorkCalendarDayMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceSlaCalculatorTest {

    private ServiceSlaCalculator calculator;
    private ServiceSlaPolicy standardPolicy;
    private WorkCalendarDayMapper workCalendarDayMapper;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(LocalDateTime.of(2026, 8, 24, 9, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant(), ZoneId.of("Asia/Tokyo"));
        workCalendarDayMapper = mock(WorkCalendarDayMapper.class);
        ObjectProvider<WorkCalendarDayMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(workCalendarDayMapper);

        calculator = new ServiceSlaCalculator(fixedClock, provider, null);
        standardPolicy = ServiceSlaPolicy.builder()
                .businessHoursStart(LocalTime.of(9, 0))
                .businessHoursEnd(LocalTime.of(18, 0))
                .includeHolidays(false)
                .build();
    }

    @Test
    @DisplayName("同日営業時間内での期限計算（例: 10:00起票、4時間後 -> 14:00）")
    void testCalculateDeadline_sameDay() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 24, 10, 0); // 2026-08-24 (月)
        LocalDateTime deadline = calculator.calculateDeadline(start, 4, standardPolicy);

        assertEquals(LocalDateTime.of(2026, 8, 24, 14, 0), deadline);
    }

    @Test
    @DisplayName("日跨ぎの期限計算（例: 16:00起票、4時間後 -> 翌営業日 11:00）")
    void testCalculateDeadline_acrossDay() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 24, 16, 0); // 月曜 16:00 (当日残り2時間)
        LocalDateTime deadline = calculator.calculateDeadline(start, 4, standardPolicy);

        // 翌火曜の 09:00 から残り2時間 -> 11:00
        assertEquals(LocalDateTime.of(2026, 8, 25, 11, 0), deadline);
    }

    @Test
    @DisplayName("金曜夕方起票時の週末スキップ計算（例: 金曜 16:00起票、4時間後 -> 翌月曜 11:00）")
    void testCalculateDeadline_weekendSkip() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 28, 16, 0); // 金曜 16:00 (当日残り2時間)
        LocalDateTime deadline = calculator.calculateDeadline(start, 4, standardPolicy);

        // 土日はスキップされ、翌月曜 2026-08-31 09:00 から2時間 -> 11:00
        assertEquals(LocalDateTime.of(2026, 8, 31, 11, 0), deadline);
    }

    @Test
    @DisplayName("祝日(m_work_calendar_day)スキップの計算 (WIP-1)")
    void testCalculateDeadline_holidaySkip() {
        LocalDate holidayDate = LocalDate.of(2026, 8, 25); // 火曜日を祝日と定義
        ServiceSlaCalculator holidayCalculator = new ServiceSlaCalculator(
                Clock.fixed(LocalDateTime.of(2026, 8, 24, 9, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant(), ZoneId.of("Asia/Tokyo")),
                null,
                null) {
            @Override
            public boolean isNonWorkingDay(LocalDate date) {
                if (date.equals(holidayDate)) {
                    return true;
                }
                return super.isWeekend(date);
            }
        };

        LocalDateTime start = LocalDateTime.of(2026, 8, 24, 16, 0); // 月曜 16:00
        LocalDateTime deadline = holidayCalculator.calculateDeadline(start, 4, standardPolicy);

        // 火曜が祝日としてスキップされ、水曜 2026-08-26 09:00 から2時間 -> 11:00
        assertEquals(LocalDateTime.of(2026, 8, 26, 11, 0), deadline);
    }

    @Test
    @DisplayName("営業時間外（深夜・早朝）起票時の始業時刻アライン計算")
    void testCalculateDeadline_outOfHours() {
        LocalDateTime midnight = LocalDateTime.of(2026, 8, 24, 2, 0); // 月曜 深夜2:00
        LocalDateTime deadline = calculator.calculateDeadline(midnight, 2, standardPolicy);

        // 始業 09:00 にアラインされ、2時間後 -> 11:00
        assertEquals(LocalDateTime.of(2026, 8, 24, 11, 0), deadline);
    }

    @Test
    @DisplayName("一時停止（Pause）による期限延長計算")
    void testCalculateExtendedDeadline() {
        LocalDateTime originalDeadline = LocalDateTime.of(2026, 8, 24, 14, 0); // 月曜 14:00
        // 120分停止
        LocalDateTime extended = calculator.calculateExtendedDeadline(originalDeadline, 120, standardPolicy);

        assertEquals(LocalDateTime.of(2026, 8, 24, 16, 0), extended);
    }
}
