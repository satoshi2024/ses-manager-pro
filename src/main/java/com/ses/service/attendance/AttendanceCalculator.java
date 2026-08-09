package com.ses.service.attendance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.WorkCalendar;
import com.ses.entity.WorkCalendarDay;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.mapper.WorkCalendarMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 雇用勤怠の日次計算を一箇所へ閉じ込める。休日区分はclient inputではなくcalendarを正とする。
 * 日8時間・週40時間・深夜・跨夜・休憩の口径はここから外へ出さない。
 * 休憩は方式A（design §5.1.1）により、勤務区間から休憩区間のintersectionを除いた
 * 実労働区間で算定する。休憩区間の検証（重複・勤務区間外・開始≧終了・全体超過）も
 * ここでfail-closedに行う。
 */
@Service
@RequiredArgsConstructor
public class AttendanceCalculator {

    /** 勤務開始を0とする休憩区間（分）。 */
    public record BreakInterval(int startOffsetMinutes, int endOffsetMinutes) {
    }

    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int LEGAL_DAILY_MINUTES = 8 * 60;
    private static final int LEGAL_WEEKLY_MINUTES = 40 * 60;

    private final WorkCalendarMapper workCalendarMapper;
    private final WorkCalendarDayMapper workCalendarDayMapper;

    public AttendanceCalculation calculate(LocalDate workDate, Long engineerId, Long legalEntityId,
                                           Long organizationId, LocalTime clockIn, LocalTime clockOut,
                                           List<BreakInterval> breaks, int priorWeekMinutes) {
        WorkCalendar calendar = resolveCalendar(workDate, engineerId, legalEntityId, organizationId);
        WorkCalendarDay day = workCalendarDayMapper.selectOne(new LambdaQueryWrapper<WorkCalendarDay>()
                .eq(WorkCalendarDay::getCalendarId, calendar.getId())
                .eq(WorkCalendarDay::getCalendarDate, workDate)
                .last("LIMIT 1"));
        if (day == null || day.getDayType() == null || day.getDayType().isBlank()) {
            throw BusinessException.of(400, "error.attendance.calendarDayUnknown");
        }
        if (!List.of("通常", "所定日", "所定休日", "法定休日").contains(day.getDayType())) {
            throw BusinessException.of(400, "error.attendance.calendarDayUnknown");
        }
        int start = toMinute(clockIn);
        int end = toEndMinute(clockIn, clockOut);
        int gross = end - start;
        if (gross < 0 || gross > MINUTES_PER_DAY) {
            throw BusinessException.of(400, "error.attendance.invalidTime");
        }
        List<BreakInterval> validated = validateBreaks(breaks, start, end, gross);

        int worked = 0;
        int lateNight = 0;
        int cursor = start;
        for (BreakInterval interval : validated) {
            int breakStart = start + interval.startOffsetMinutes();
            int breakEnd = start + interval.endOffsetMinutes();
            worked += breakStart - cursor;
            lateNight += nightOverlap(cursor, breakStart);
            cursor = breakEnd;
        }
        worked += end - cursor;
        lateNight += nightOverlap(cursor, end);

        int regular = 0;
        int overtime = 0;
        int holiday = 0;
        if ("法定休日".equals(day.getDayType())) {
            holiday = worked;
        } else if ("所定休日".equals(day.getDayType())) {
            overtime = worked;
        } else {
            int dailyLegal = Math.min(worked, LEGAL_DAILY_MINUTES);
            int dailyOvertime = Math.max(0, worked - LEGAL_DAILY_MINUTES);
            int weeklyOvertime = incrementalWeeklyOvertime(priorWeekMinutes, dailyLegal);
            regular = dailyLegal - weeklyOvertime;
            overtime = dailyOvertime + weeklyOvertime;
        }
        return new AttendanceCalculation(calendar.getId(), day.getScheduledMinutes(), day.getDayType(),
                worked, regular, overtime, holiday, lateNight);
    }

    /**
     * 休憩区間をfail-closedで検証し、開始offset昇順（隣接は許可、重複は拒否）で返す。
     * 区間が無ければ空リスト。検証はこの1箇所に閉じ、配賦規則や補間は行わない。
     */
    private List<BreakInterval> validateBreaks(List<BreakInterval> breaks, int start, int end, int gross) {
        if (breaks == null || breaks.isEmpty()) {
            return List.of();
        }
        List<BreakInterval> sorted = new ArrayList<>(breaks);
        int total = 0;
        for (BreakInterval interval : sorted) {
            if (interval == null || interval.startOffsetMinutes() < 0 || interval.endOffsetMinutes() <= 0) {
                throw BusinessException.of(400, "error.attendance.breakInvalid");
            }
            if (interval.endOffsetMinutes() <= interval.startOffsetMinutes()) {
                throw BusinessException.of(400, "error.attendance.breakInvalid");
            }
            if (interval.endOffsetMinutes() > gross) {
                throw BusinessException.of(400, "error.attendance.breakOutOfRange");
            }
            total += interval.endOffsetMinutes() - interval.startOffsetMinutes();
            if (total > gross) {
                throw BusinessException.of(400, "error.attendance.breakTotalExceeds");
            }
        }
        sorted.sort(Comparator.comparingInt(BreakInterval::startOffsetMinutes)
                .thenComparingInt(BreakInterval::endOffsetMinutes));
        for (int i = 1; i < sorted.size(); i++) {
            BreakInterval previous = sorted.get(i - 1);
            BreakInterval current = sorted.get(i);
            if (current.startOffsetMinutes() < previous.endOffsetMinutes()) {
                throw BusinessException.of(400, "error.attendance.breakOverlap");
            }
        }
        // 勤務区間内の整合を絶対座標でも再確認する（offsetの加算誤りを許さない）。
        for (BreakInterval interval : sorted) {
            if (start + interval.startOffsetMinutes() < start
                    || start + interval.endOffsetMinutes() > end) {
                throw BusinessException.of(400, "error.attendance.breakOutOfRange");
            }
        }
        return sorted;
    }

    private WorkCalendar resolveCalendar(LocalDate workDate, Long engineerId, Long legalEntityId,
                                         Long organizationId) {
        if (workDate == null || engineerId == null || legalEntityId == null) {
            throw BusinessException.of(400, "error.attendance.calendarUnknown");
        }
        List<WorkCalendar> engineerCalendars = activeCalendars(workDate, query ->
                query.eq(WorkCalendar::getEngineerId, engineerId)).stream()
                .filter(calendar -> engineerId.equals(calendar.getEngineerId()))
                .toList();
        if (!engineerCalendars.isEmpty()) {
            return selectLatest(engineerCalendars);
        }

        List<WorkCalendar> organizationCalendars = activeCalendars(workDate, query ->
                query.eq(WorkCalendar::getOrganizationId, organizationId)
                        .isNull(WorkCalendar::getEngineerId)).stream()
                .filter(calendar -> organizationId.equals(calendar.getOrganizationId())
                        && calendar.getEngineerId() == null)
                .toList();
        if (!organizationCalendars.isEmpty()) {
            return selectLatest(organizationCalendars);
        }

        List<WorkCalendar> legalEntityCalendars = activeCalendars(workDate, query ->
                query.eq(WorkCalendar::getLegalEntityId, legalEntityId)
                        .isNull(WorkCalendar::getEngineerId)
                        .isNull(WorkCalendar::getOrganizationId)).stream()
                .filter(calendar -> legalEntityId.equals(calendar.getLegalEntityId())
                        && calendar.getEngineerId() == null && calendar.getOrganizationId() == null)
                .toList();
        if (!legalEntityCalendars.isEmpty()) {
            return selectLatest(legalEntityCalendars);
        }
        throw BusinessException.of(400, "error.attendance.calendarUnknown");
    }

    private List<WorkCalendar> activeCalendars(LocalDate workDate,
                                               java.util.function.Consumer<LambdaQueryWrapper<WorkCalendar>> scope) {
        LambdaQueryWrapper<WorkCalendar> query = new LambdaQueryWrapper<WorkCalendar>()
                .eq(WorkCalendar::getStatus, "有効")
                .le(WorkCalendar::getValidFrom, workDate)
                .and(w -> w.isNull(WorkCalendar::getValidTo).or().ge(WorkCalendar::getValidTo, workDate));
        scope.accept(query);
        return workCalendarMapper.selectList(query).stream()
                .filter(calendar -> calendar.getValidFrom() != null
                        && !calendar.getValidFrom().isAfter(workDate)
                        && (calendar.getValidTo() == null || !calendar.getValidTo().isBefore(workDate)))
                .toList();
    }

    private WorkCalendar selectLatest(List<WorkCalendar> calendars) {
        return calendars.stream()
                .sorted(Comparator
                        .comparing(WorkCalendar::getValidFrom, Comparator.reverseOrder())
                        .thenComparing(WorkCalendar::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElseThrow(() -> BusinessException.of(400, "error.attendance.calendarUnknown"));
    }

    private int incrementalWeeklyOvertime(int priorWeekMinutes, int dailyLegalMinutes) {
        int prior = Math.max(0, priorWeekMinutes);
        return Math.max(0, prior + dailyLegalMinutes - LEGAL_WEEKLY_MINUTES)
                - Math.max(0, prior - LEGAL_WEEKLY_MINUTES);
    }

    private int nightOverlap(int start, int end) {
        if (end <= start) return 0;
        return overlap(start, end, 0, 5 * 60) + overlap(start, end, 22 * 60, 29 * 60);
    }

    private int overlap(int start, int end, int rangeStart, int rangeEnd) {
        return Math.max(0, Math.min(end, rangeEnd) - Math.max(start, rangeStart));
    }

    private int toMinute(LocalTime value) {
        return value == null ? 0 : value.getHour() * 60 + value.getMinute();
    }

    private int toEndMinute(LocalTime clockIn, LocalTime clockOut) {
        if (clockIn == null && clockOut == null) return 0;
        if (clockIn == null || clockOut == null) {
            throw BusinessException.of(400, "error.attendance.invalidTime");
        }
        int start = toMinute(clockIn);
        int end = toMinute(clockOut);
        if (end < start) end += MINUTES_PER_DAY;
        return end;
    }
}
