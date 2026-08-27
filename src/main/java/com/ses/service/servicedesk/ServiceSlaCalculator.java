package com.ses.service.servicedesk;

import com.ses.entity.ServiceSlaPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * 営業時間・休日・タイムゾーンを考慮したSLA期限計算エンジン
 */
@Slf4j
@Component
public class ServiceSlaCalculator {

    private static final LocalTime DEFAULT_START_TIME = LocalTime.of(9, 0);
    private static final LocalTime DEFAULT_END_TIME = LocalTime.of(18, 0);

    /**
     * 起点日時と目標時間（時間）からSLA期限を計算する。
     *
     * @param startAt 起点日時
     * @param targetHours 目標時間（時間）
     * @param policy SLAポリシー
     * @return 算出されたSLA期限日時
     */
    public LocalDateTime calculateDeadline(LocalDateTime startAt, int targetHours, ServiceSlaPolicy policy) {
        if (startAt == null) {
            startAt = LocalDateTime.now();
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
        if (!includeHolidays && isWeekend(date)) {
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
        while (!includeHolidays && isWeekend(current)) {
            current = current.plusDays(1);
        }
        return current;
    }

    /**
     * 土日判定（土日を休日とする）
     */
    public boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }
}
