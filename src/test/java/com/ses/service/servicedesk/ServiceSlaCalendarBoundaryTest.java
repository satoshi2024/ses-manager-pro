package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.entity.ServiceSlaPolicy;
import com.ses.entity.WorkCalendar;
import com.ses.entity.WorkCalendarDay;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.mapper.WorkCalendarMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** SLAの実装上の四段階カレンダー優先度と東京時間の境界を固定する。 */
class ServiceSlaCalendarBoundaryTest {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private final WorkCalendarMapper calendars = mock(WorkCalendarMapper.class);
    private final WorkCalendarDayMapper days = mock(WorkCalendarDayMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-31T08:00:00Z"), TOKYO);
    private final ServiceSlaCalculator calculator = new ServiceSlaCalculator(calendars, days, clock);
    private final ServiceSlaPolicy policy = ServiceSlaPolicy.builder()
            .businessHoursStart(LocalTime.of(9, 0)).businessHoursEnd(LocalTime.of(18, 0))
            .includeHolidays(false).build();

    @BeforeEach
    void setUp() {
        when(calendars.selectList(any())).thenReturn(List.of());
    }

    @ParameterizedTest
    @CsvSource({"1,11", "2,12", "3,13", "4,14"})
    void 組織法人から全社共通まで優先順に一つだけの日別定義を参照する(int firstLevel, long expectedId) {
        List<WorkCalendar> candidates = new ArrayList<>();
        if (firstLevel <= 1) candidates.add(calendar(11, 7L, 9L));
        if (firstLevel <= 2) candidates.add(calendar(12, null, 9L));
        if (firstLevel <= 3) candidates.add(calendar(13, 7L, null));
        candidates.add(calendar(14, null, null));
        candidates.add(calendar(99, 8L, 10L));
        java.util.Collections.reverse(candidates);
        when(calendars.selectList(any())).thenReturn(candidates);
        when(days.selectOne(any())).thenAnswer(invocation -> {
            QueryWrapper<?> query = invocation.getArgument(0);
            query.getCustomSqlSegment();
            assertThat(query.getParamNameValuePairs().values()).contains(expectedId);
            assertThat(query.getParamNameValuePairs().values()).doesNotContain(99L);
            return WorkCalendarDay.builder().dayType("HOLIDAY").scheduledMinutes(480).build();
        });

        assertThat(calculator.isNonWorkingDay(LocalDate.of(2026, 8, 31), 7L, 9L)).isTrue();
        verify(days, times(1)).selectOne(any());
    }

    @Test
    void 同じスコープでは有効開始日の新しいカレンダーを採用する() {
        WorkCalendar old = calendar(11, 7L, 9L);
        WorkCalendar recent = calendar(12, 7L, 9L);
        recent.setValidFrom(LocalDate.of(2026, 8, 1));
        when(calendars.selectList(any())).thenReturn(List.of(old, recent));
        when(days.selectOne(any())).thenAnswer(invocation -> {
            QueryWrapper<?> query = invocation.getArgument(0);
            query.getCustomSqlSegment();
            assertThat(query.getParamNameValuePairs().values()).contains(12L).doesNotContain(11L);
            return null;
        });
        assertThat(calculator.isNonWorkingDay(LocalDate.of(2026, 8, 31), 7L, 9L)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(value = {"HOLIDAY,480,true", "NON_WORKING,480,true", "祝日,480,true",
            "休日,480,true", "WORKING,0,false", "通常,0,false", "所定日,0,false",
            "特別営業日,480,false", "未定義,0,true", "未定義,480,false", "未定義,,false"})
    void 平日の日別区分は明示区分を優先し未知区分は所定分数で判定する(
            String dayType, Integer scheduledMinutes, boolean nonWorking) {
        when(calendars.selectList(any())).thenReturn(List.of(calendar(14, null, null)));
        when(days.selectOne(any())).thenReturn(WorkCalendarDay.builder()
                .dayType(dayType).scheduledMinutes(scheduledMinutes).build());
        assertThat(calculator.isNonWorkingDay(LocalDate.of(2026, 8, 31), null, null)).isEqualTo(nonWorking);
    }

    @Test
    void カレンダー未設定では平日を営業日とし土日を除外する() {
        assertThat(calculator.isNonWorkingDay(LocalDate.of(2026, 8, 28), null, null)).isFalse();
        assertThat(calculator.isNonWorkingDay(LocalDate.of(2026, 8, 29), null, null)).isTrue();
        assertThat(calculator.isNonWorkingDay(LocalDate.of(2026, 8, 30), null, null)).isTrue();
        verifyNoInteractions(days);
    }

    @ParameterizedTest
    @CsvSource({"2026-08-24T08:00:00Z,2026-08-25T01:00:00Z",
            "2026-08-31T08:00:00Z,2026-09-01T01:00:00Z",
            "2026-12-31T08:00:00Z,2027-01-01T01:00:00Z"})
    void 東京の終業一時間前から二営業時間を日月年の境界で加算する(String start, String expected) {
        Instant from = Instant.parse(start);
        Instant until = Instant.parse(expected);
        assertThat(calculator.calculateDeadline(from, 2, policy, null, null, null)).isEqualTo(until);
        assertThat(calculator.businessMinutesBetween(from, until, policy, null, null, TOKYO)).isEqualTo(120);
        assertThat(calculator.calculateExtendedDeadline(from, 120, policy, null, null, TOKYO)).isEqualTo(until);
    }

    @Test
    void 起点未指定なら注入Clockを東京時間へ変換して月跨ぎ期限を計算する() {
        assertThat(calculator.calculateDeadline((Instant) null, 2, policy))
                .isEqualTo(Instant.parse("2026-09-01T01:00:00Z"));
        assertThat(calculator.isBusinessTime(Instant.parse("2026-08-31T00:00:00Z"), policy, null, null, null)).isTrue();
        assertThat(calculator.isBusinessTime(Instant.parse("2026-08-31T09:00:00Z"), policy, null, null, null)).isFalse();
    }

    private WorkCalendar calendar(long id, Long organizationId, Long legalEntityId) {
        WorkCalendar row = new WorkCalendar();
        row.setId(id);
        row.setOrganizationId(organizationId);
        row.setLegalEntityId(legalEntityId);
        row.setStatus("有効");
        row.setValidFrom(LocalDate.of(2026, 1, 1));
        return row;
    }
}
