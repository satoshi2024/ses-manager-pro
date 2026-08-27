package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.ServiceSlaPolicy;
import com.ses.entity.WorkCalendar;
import com.ses.entity.WorkCalendarDay;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.mapper.WorkCalendarMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 営業時間・法人既定カレンダー祝日・タイムゾーン・Clockを考慮したSLA期限計算エンジン (WIP-1)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceSlaCalculator {

    private static final LocalTime DEFAULT_START_TIME = LocalTime.of(9, 0);
    private static final LocalTime DEFAULT_END_TIME = LocalTime.of(18, 0);

    private final Clock clock;
    private final ObjectProvider<WorkCalendarDayMapper> workCalendarDayMapperProvider;
    private final ObjectProvider<WorkCalendarMapper> workCalendarMapperProvider;

    /**
     * 起点日時と目標時間（時間）からSLA期限を計算する。
     *
     * @param startAt 起点日時（null時はClockの現在日時）
     * @param targetHours 目標時間（時間）
     * @param policy SLAポリシー
     * @return 算出されたSLA期限日時
     */
    public LocalDateTime calculateDeadline(LocalDateTime startAt, int targetHours, ServiceSlaPolicy policy) {
        if (startAt == null) {
            startAt = LocalDateTime.now(clock);
        }
        if (targetHours <= 0) {
            return startAt;
        }

        LocalTime businessStart = policy != null && policy.getBusinessHoursStart() != null
                ? policy.getBusinessHoursStart() : DEFAULT_START_TIME;
        LocalTime businessEnd = policy != null && policy.getBusinessHoursEnd() != null
                ? policy.getBusinessHoursEnd() : DEFAULT_END_TIME;
        boolean includeHolidays = policy != null && Boolean.TRUE.equals(policy.getIncludeHolidays());

        LocalDateTime cursor = alignToBusinessTime(startAt, businessStart, businessEnd, includeHolidays);
        long remainingMinutes = (long) targetHours * 60;

        while (remainingMinutes > 0) {
            LocalDateTime endOfCurrentDay = LocalDateTime.of(cursor.toLocalDate(), businessEnd);
            long minutesAvailableToday = ChronoUnit.MINUTES.between(cursor, endOfCurrentDay);

            if (remainingMinutes <= minutesAvailableToday) {
                cursor = cursor.plusMinutes(remainingMinutes);
                remainingMinutes = 0;
            } else {
                remainingMinutes -= minutesAvailableToday;
                LocalDate nextWorkDate = getNextWorkDay(cursor.toLocalDate().plusDays(1), includeHolidays);
                cursor = LocalDateTime.of(nextWorkDate, businessStart);
            }
        }

        return cursor;
    }

    /**
     * 一時停止（Pause）期間（分）を考慮してSLA期限を延長・繰り延べる。
     *
     * @param currentDeadline 現在の期限
     * @param pauseMinutes 停止期間（分）
     * @param policy SLAポリシー
     * @return 延長後のSLA期限日時
     */
    public LocalDateTime calculateExtendedDeadline(LocalDateTime currentDeadline, int pauseMinutes, ServiceSlaPolicy policy) {
        if (currentDeadline == null || pauseMinutes <= 0) {
            return currentDeadline;
        }

        LocalTime businessStart = policy != null && policy.getBusinessHoursStart() != null
                ? policy.getBusinessHoursStart() : DEFAULT_START_TIME;
        LocalTime businessEnd = policy != null && policy.getBusinessHoursEnd() != null
                ? policy.getBusinessHoursEnd() : DEFAULT_END_TIME;
        boolean includeHolidays = policy != null && Boolean.TRUE.equals(policy.getIncludeHolidays());

        LocalDateTime cursor = alignToBusinessTime(currentDeadline, businessStart, businessEnd, includeHolidays);
        long remainingMinutes = pauseMinutes;

        while (remainingMinutes > 0) {
            LocalDateTime endOfCurrentDay = LocalDateTime.of(cursor.toLocalDate(), businessEnd);
            long minutesAvailableToday = ChronoUnit.MINUTES.between(cursor, endOfCurrentDay);

            if (remainingMinutes <= minutesAvailableToday) {
                cursor = cursor.plusMinutes(remainingMinutes);
                remainingMinutes = 0;
            } else {
                remainingMinutes -= minutesAvailableToday;
                LocalDate nextWorkDate = getNextWorkDay(cursor.toLocalDate().plusDays(1), includeHolidays);
                cursor = LocalDateTime.of(nextWorkDate, businessStart);
            }
        }

        return cursor;
    }

    /**
     * 日時を直近の営業日・営業時間内にアライン（補正）する。
     */
    public LocalDateTime alignToBusinessTime(LocalDateTime dt, LocalTime start, LocalTime end, boolean includeHolidays) {
        LocalDate date = dt.toLocalDate();
        LocalTime time = dt.toLocalTime();

        // 休日なら次の営業日の始業時刻
        if (!includeHolidays && isNonWorkingDay(date)) {
            LocalDate nextWorkDate = getNextWorkDay(date, includeHolidays);
            return LocalDateTime.of(nextWorkDate, start);
        }

        // 始業前なら当日の始業時刻
        if (time.isBefore(start)) {
            return LocalDateTime.of(date, start);
        }

        // 終業後なら翌営業日の始業時刻
        if (!time.isBefore(end)) {
            LocalDate nextWorkDate = getNextWorkDay(date.plusDays(1), includeHolidays);
            return LocalDateTime.of(nextWorkDate, start);
        }

        return dt;
    }

    /**
     * 指定日以降の次の営業日を取得する。
     */
    public LocalDate getNextWorkDay(LocalDate date, boolean includeHolidays) {
        LocalDate current = date;
        int maxDays = 365;
        while (!includeHolidays && isNonWorkingDay(current) && maxDays-- > 0) {
            current = current.plusDays(1);
        }
        return current;
    }

    /**
     * 土日・祝日・法人休業日判定 (m_work_calendar 法人既定 & m_work_calendar_day 連携: WIP-1)
     */
    public boolean isNonWorkingDay(LocalDate date) {
        if (isWeekend(date)) {
            return true;
        }

        WorkCalendarDayMapper dayMapper = workCalendarDayMapperProvider != null ? workCalendarDayMapperProvider.getIfAvailable() : null;
        WorkCalendarMapper calMapper = workCalendarMapperProvider != null ? workCalendarMapperProvider.getIfAvailable() : null;

        if (dayMapper != null) {
            LambdaQueryWrapper<WorkCalendarDay> wrapper = new LambdaQueryWrapper<WorkCalendarDay>()
                    .eq(WorkCalendarDay::getCalendarDate, date);

            // 法人既定カレンダー（organization_id IS NULL かつ engineer_id IS NULL、status IN ('有効', 'ACTIVE')）に限定
            if (calMapper != null) {
                List<WorkCalendar> legalCalendars = calMapper.selectList(
                        new LambdaQueryWrapper<WorkCalendar>()
                                .isNull(WorkCalendar::getOrganizationId)
                                .isNull(WorkCalendar::getEngineerId)
                                .in(WorkCalendar::getStatus, List.of("有効", "ACTIVE")));
                if (!legalCalendars.isEmpty()) {
                    List<Long> calIds = legalCalendars.stream().map(WorkCalendar::getId).toList();
                    wrapper.in(WorkCalendarDay::getCalendarId, calIds);
                } else {
                    log.debug("法人既定カレンダー未定義 (missing_calendar): date={}", date);
                    wrapper.eq(WorkCalendarDay::getCalendarId, -1L); // 個人カレンダー混入を完全遮断
                }
            }

            List<WorkCalendarDay> days = dayMapper.selectList(wrapper);
            for (WorkCalendarDay day : days) {
                String type = day.getDayType();
                if ("所定休日".equals(type) || "法定休日".equals(type)
                        || "HOLIDAY".equalsIgnoreCase(type) || "NON_WORKING".equalsIgnoreCase(type)
                        || "祝日".equals(type)
                        || (day.getScheduledMinutes() != null && day.getScheduledMinutes() == 0)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 土日判定
     */
    public boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }
}
