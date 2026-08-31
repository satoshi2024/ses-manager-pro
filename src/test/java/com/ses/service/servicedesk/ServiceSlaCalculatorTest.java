package com.ses.service.servicedesk;

import com.ses.entity.ServiceSlaPolicy;
import com.ses.entity.WorkCalendar;
import com.ses.entity.WorkCalendarDay;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.mapper.WorkCalendarMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceSlaCalculatorTest {

    private ServiceSlaCalculator calculator;
    private ServiceSlaPolicy standardPolicy;
    private WorkCalendarMapper workCalendarMapper;
    private WorkCalendarDayMapper workCalendarDayMapper;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(LocalDateTime.of(2026, 8, 24, 9, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant(), ZoneId.of("Asia/Tokyo"));
        workCalendarMapper = mock(WorkCalendarMapper.class);
        workCalendarDayMapper = mock(WorkCalendarDayMapper.class);

        calculator = new ServiceSlaCalculator(workCalendarMapper, workCalendarDayMapper, fixedClock);
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
    @DisplayName("法人カレンダーおよび祝日マッパー経由でのスキップ計算")
    void testCalculateDeadline_holidaySkip() {
        WorkCalendar legalCal = new WorkCalendar();
        legalCal.setId(100L);
        legalCal.setStatus("有効");

        WorkCalendarMapper calMapper = mock(WorkCalendarMapper.class);
        when(calMapper.selectList(any())).thenReturn(List.of(legalCal));

        WorkCalendarDay holidayDay = new WorkCalendarDay();
        holidayDay.setCalendarId(100L);
        holidayDay.setCalendarDate(LocalDate.of(2026, 8, 25)); // 火曜日を祝日
        holidayDay.setDayType("祝日");

        WorkCalendarDayMapper dayMapper = mock(WorkCalendarDayMapper.class);
        when(dayMapper.selectOne(any())).thenAnswer(inv -> {
            // QueryWrapper — params are plain values in the SQL segment
            Object wrapper = inv.getArgument(0);
            if (wrapper instanceof com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> qw) {
                String sql = qw.getCustomSqlSegment();
                if (sql != null && sql.contains("calendar_date")) {
                    // Check if the queried date is 2026-08-25 by inspecting param values
                    for (Object val : qw.getParamNameValuePairs().values()) {
                        if (LocalDate.of(2026, 8, 25).equals(val)) {
                            return holidayDay;
                        }
                    }
                }
            }
            return null;
        });

        Clock fixedClock = Clock.fixed(LocalDateTime.of(2026, 8, 24, 9, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant(), ZoneId.of("Asia/Tokyo"));
        ServiceSlaCalculator realLookupCalculator = new ServiceSlaCalculator(calMapper, dayMapper, fixedClock);

        LocalDateTime start = LocalDateTime.of(2026, 8, 24, 16, 0); // 月曜 16:00 (当日残り2時間)
        LocalDateTime deadline = realLookupCalculator.calculateDeadline(start, 4, standardPolicy);

        // 火曜が祝日としてスキップされ、水曜 2026-08-26 09:00 から2時間 -> 11:00
        assertEquals(LocalDateTime.of(2026, 8, 26, 11, 0), deadline);
    }

    @Test
    @DisplayName("多法人カレンダー隔離テスト：法人Aの休日は法人Bに混入しないこと")
    void testCalendarIsolation_noUnionAcrossLegalEntities() {
        WorkCalendar calA = new WorkCalendar();
        calA.setId(10L);
        calA.setLegalEntityId(1L);
        calA.setStatus("有効");

        WorkCalendar calB = new WorkCalendar();
        calB.setId(20L);
        calB.setLegalEntityId(2L);
        calB.setStatus("有効");

        WorkCalendarMapper calMapper = mock(WorkCalendarMapper.class);
        when(calMapper.selectList(any())).thenAnswer(inv -> List.of(calB));

        // 法人Bのカレンダー(20L)には8/25の祝日は存在しない
        WorkCalendarDayMapper dayMapper = mock(WorkCalendarDayMapper.class);
        when(dayMapper.selectOne(any())).thenReturn(null);

        Clock fixedClock = Clock.fixed(LocalDateTime.of(2026, 8, 24, 9, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant(), ZoneId.of("Asia/Tokyo"));
        ServiceSlaCalculator isoCalculator = new ServiceSlaCalculator(calMapper, dayMapper, fixedClock);

        // 法人Bのスコープで計算（火曜8/25は祝日にならない）
        LocalDateTime start = LocalDateTime.of(2026, 8, 24, 16, 0);
        LocalDateTime deadlineB = isoCalculator.calculateDeadline(start, 4, standardPolicy, null, 2L);

        // 法人Bでは火曜 8/25 11:00 に完了する
        assertEquals(LocalDateTime.of(2026, 8, 25, 11, 0), deadlineB);
    }

    @Test
    @DisplayName("夏時間（DST）および米国東部時間ゾーンでのInstantベースSLA計算")
    void testCalculateDeadline_withDstTransition() {
        ZoneId nyZone = ZoneId.of("America/New_York");
        // 2026年3月8日（日）は米国DST開始（2:00 -> 3:00へスキップ）
        // 2026年3月6日（金） 16:00 EST 起票、4時間SLA
        ZonedDateTime startNy = ZonedDateTime.of(2026, 3, 6, 16, 0, 0, 0, nyZone);
        Instant startInstant = startNy.toInstant();

        Instant deadlineInstant = calculator.calculateDeadline(startInstant, 4, standardPolicy, null, null, nyZone);
        ZonedDateTime deadlineNy = deadlineInstant.atZone(nyZone);

        // 金曜 16:00〜18:00 (2時間消化) + 月曜 3/9 09:00〜11:00 EDT (2時間消化) -> 月曜 11:00 EDT
        assertEquals(2026, deadlineNy.getYear());
        assertEquals(3, deadlineNy.getMonthValue());
        assertEquals(9, deadlineNy.getDayOfMonth());
        assertEquals(11, deadlineNy.getHour());
        assertEquals(0, deadlineNy.getMinute());
        assertEquals(ZoneId.of("America/New_York"), deadlineNy.getZone());
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
