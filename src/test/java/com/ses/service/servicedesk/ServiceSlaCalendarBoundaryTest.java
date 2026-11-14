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
import java.time.ZoneOffset;
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
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-31T08:00:00Z"), ZoneOffset.UTC);
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void 同じスコープでは候補順によらず有効開始日の新しいカレンダーを採用する(boolean reversed) {
        WorkCalendar old = calendar(11, 7L, 9L);
        WorkCalendar recent = calendar(12, 7L, 9L);
        recent.setValidFrom(LocalDate.of(2026, 8, 1));
        when(calendars.selectList(any())).thenReturn(reversed ? List.of(recent, old) : List.of(old, recent));
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
            "休日,480,true", "所定休日,480,true", "法定休日,480,true",
            "WORKING,0,false", "通常,0,false", "所定日,0,false",
            "特別営業日,480,false", "未定義,0,true", "未定義,480,false", "未定義,,true"})
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
        // 日別行が無い場合、元日も平日なら営業日となる（祝日マスタへの暗黙依存なし）。
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

    @ParameterizedTest
    @CsvSource({"false,12", "true,14"})
    void 本番の組織NULL経路は法人既定から全社共通だけへfallbackし他法人を混ぜない(boolean missingLegal, long expected) {
        List<WorkCalendar> rows = new ArrayList<>(List.of(calendar(99, null, 10L), calendar(14, null, null),
                calendar(11, 7L, 9L), calendar(13, 7L, null)));
        if (!missingLegal) rows.add(calendar(12, null, 9L));
        when(calendars.selectList(any())).thenReturn(rows);
        when(days.selectOne(any())).thenAnswer(invocation -> {
            QueryWrapper<?> query = invocation.getArgument(0);
            query.getCustomSqlSegment();
            assertThat(query.getParamNameValuePairs().values()).contains(expected).doesNotContain(11L, 13L, 99L);
            return WorkCalendarDay.builder().dayType("WORKING").build();
        });
        assertThat(calculator.isNonWorkingDay(LocalDate.of(2026, 8, 29), null, 9L)).isFalse();
        verify(days, times(1)).selectOne(any());
    }

    @ParameterizedTest
    @CsvSource({"WORKING,0,false", "HOLIDAY,480,true", "NO_ROW,480,true",
            "未定義,,true", "未定義,0,true", "未定義,480,false"})
    void 土曜も日別行を優先し行が無い時だけ土日を除外する(String type, Integer minutes, boolean holiday) {
        when(calendars.selectList(any())).thenReturn(List.of(calendar(14, null, null)));
        when(days.selectOne(any())).thenReturn("NO_ROW".equals(type) ? null
                : WorkCalendarDay.builder().dayType(type).scheduledMinutes(minutes).build());
        assertThat(calculator.isNonWorkingDay(LocalDate.of(2026, 8, 29), null, null)).isEqualTo(holiday);
    }

    @Test
    void 年跨ぎでは元日祝日と週末を飛ばし同じ日のカレンダーを再読込しない() {
        when(calendars.selectList(any())).thenReturn(List.of(calendar(14, null, null)));
        java.util.Map<LocalDate, Integer> reads = new java.util.HashMap<>();
        when(days.selectOne(any())).thenAnswer(invocation -> {
            QueryWrapper<?> query = invocation.getArgument(0);
            query.getCustomSqlSegment();
            LocalDate date = query.getParamNameValuePairs().values().stream().filter(LocalDate.class::isInstance)
                    .map(LocalDate.class::cast).findFirst().orElseThrow();
            reads.merge(date, 1, Integer::sum);
            return date.equals(LocalDate.of(2027, 1, 1)) ? WorkCalendarDay.builder().dayType("HOLIDAY").build() : null;
        });
        assertThat(calculator.calculateDeadline(Instant.parse("2026-12-31T08:00:00Z"), 2, policy))
                .isEqualTo(Instant.parse("2027-01-04T01:00:00Z"));
        verify(calendars, times(1)).selectList(any());
        assertThat(reads).hasSize(5).allSatisfy((date, count) -> assertThat(count).isEqualTo(1));
    }

    @Test
    void 日別行が無い土曜の再判定もNULL結果をキャッシュする() {
        when(calendars.selectList(any())).thenReturn(List.of(calendar(14, null, null)));
        assertThat(calculator.calculateDeadline(Instant.parse("2026-08-29T00:00:00Z"), 1, policy))
                .isEqualTo(Instant.parse("2026-08-31T01:00:00Z"));
        verify(calendars, times(1)).selectList(any());
        verify(days, times(3)).selectOne(any());
    }

    @Test
    void 年次カレンダーの有効期間は候補キャッシュ後も日付毎に再評価する() {
        WorkCalendar old = calendar(11, null, null);
        old.setValidTo(LocalDate.of(2026, 12, 31));
        WorkCalendar nextYear = calendar(12, null, null);
        nextYear.setValidFrom(LocalDate.of(2027, 1, 1));
        when(calendars.selectList(any())).thenReturn(List.of(nextYear, old));
        when(days.selectOne(any())).thenAnswer(invocation -> {
            QueryWrapper<?> query = invocation.getArgument(0);
            query.getCustomSqlSegment();
            LocalDate date = query.getParamNameValuePairs().values().stream().filter(LocalDate.class::isInstance)
                    .map(LocalDate.class::cast).findFirst().orElseThrow();
            assertThat(query.getParamNameValuePairs().values()).contains(date.getYear() == 2026 ? 11L : 12L);
            return date.equals(LocalDate.of(2027, 1, 1)) ? WorkCalendarDay.builder().dayType("HOLIDAY").build() : null;
        });
        assertThat(calculator.calculateDeadline(Instant.parse("2026-12-31T08:00:00Z"), 2, policy))
                .isEqualTo(Instant.parse("2027-01-04T01:00:00Z"));
        verify(calendars, times(1)).selectList(any());
    }

    @Test
    void カレンダー欠損は計算毎に一回だけtenant法人問い合わせIDと相関IDを警告する() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ServiceSlaCalculator.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        com.ses.service.accounting.AccountingTenantContextHolder.setTenantId("sla-test");
        com.ses.common.util.CorrelationContext.begin("sla-correlation");
        try {
            calculator.calculateDeadline(Instant.parse("2026-08-28T08:00:00Z"), 30, policy, null, 9L, TOKYO, 777L);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(event.getFormattedMessage()).contains("missing_calendar", "tenant=sla-test", "legalEntityId=9",
                        "requestId=777", "correlationId=sla-correlation");
            });
            verify(calendars, times(1)).selectList(any());
            verifyNoInteractions(days);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            com.ses.common.util.CorrelationContext.clear();
            com.ses.service.accounting.AccountingTenantContextHolder.clear();
        }
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
