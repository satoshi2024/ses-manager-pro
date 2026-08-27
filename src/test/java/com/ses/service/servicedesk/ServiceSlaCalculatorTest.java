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
    @DisplayName("法人カレンダー(m_work_calendar 有効)および祝日(m_work_calendar_day)マッパー経由でのスキップ計算 (WIP-1)")
    void testCalculateDeadline_holidaySkip() {
        com.ses.entity.WorkCalendar legalCal = new com.ses.entity.WorkCalendar();
        legalCal.setId(100L);
        legalCal.setStatus("有効");

        com.ses.mapper.WorkCalendarMapper calMapper = mock(com.ses.mapper.WorkCalendarMapper.class);
        when(calMapper.selectList(any())).thenReturn(List.of(legalCal));

        WorkCalendarDay holidayDay = new WorkCalendarDay();
        holidayDay.setCalendarId(100L);
        holidayDay.setCalendarDate(LocalDate.of(2026, 8, 25)); // 火曜日を祝日
        holidayDay.setDayType("祝日");

        when(workCalendarDayMapper.selectList(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            if (arg instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
                wrapper.getCustomSqlSegment();
                if (wrapper.getParamNameValuePairs() != null) {
                    for (Object val : wrapper.getParamNameValuePairs().values()) {
                        if (LocalDate.of(2026, 8, 25).equals(val)) {
                            return List.of(holidayDay);
                        }
                    }
                }
            }
            return List.of();
        });

        ObjectProvider<com.ses.mapper.WorkCalendarMapper> calProvider = mock(ObjectProvider.class);
        when(calProvider.getIfAvailable()).thenReturn(calMapper);

        ObjectProvider<WorkCalendarDayMapper> dayProvider = mock(ObjectProvider.class);
        when(dayProvider.getIfAvailable()).thenReturn(workCalendarDayMapper);

        Clock fixedClock = Clock.fixed(LocalDateTime.of(2026, 8, 24, 9, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant(), ZoneId.of("Asia/Tokyo"));
        ServiceSlaCalculator realLookupCalculator = new ServiceSlaCalculator(fixedClock, dayProvider, calProvider);

        LocalDateTime start = LocalDateTime.of(2026, 8, 24, 16, 0); // 月曜 16:00 (当日残り2時間)
        LocalDateTime deadline = realLookupCalculator.calculateDeadline(start, 4, standardPolicy);

        // 火曜が祝日としてマッパーから取得されてスキップされ、水曜 2026-08-26 09:00 から2時間 -> 11:00
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
