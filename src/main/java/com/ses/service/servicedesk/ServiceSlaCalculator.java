package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.ServiceSlaPolicy;
import com.ses.entity.WorkCalendar;
import com.ses.entity.WorkCalendarDay;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.mapper.WorkCalendarMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * サービスデスクSLA計算機
 * - Instant / ZoneIdによる明示的時間計算（DST・跨日・タイムゾーン完全対応）
 * - 法人・組織ごとの営業日カレンダー完全分離（法人跨ぎ休日結合の禁止）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceSlaCalculator {

    private final WorkCalendarMapper workCalendarMapper;
    private final WorkCalendarDayMapper workCalendarDayMapper;
    private final Clock clock;

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tokyo");

    /**
     * SLA期限日時の計算 (Instant)
     */
    public Instant calculateDeadline(Instant startAt, int targetHours, ServiceSlaPolicy policy,
                                    Long organizationId, Long legalEntityId, ZoneId zoneId) {
        ZoneId effectiveZone = zoneId != null ? zoneId : DEFAULT_ZONE;
        Instant start = startAt != null ? startAt : clock.instant();

        if (policy == null || targetHours <= 0) {
            return start.plusSeconds((long) Math.max(0, targetHours) * 3600);
        }

        LocalTime businessStart = policy.getBusinessHoursStart() != null ? policy.getBusinessHoursStart() : LocalTime.of(9, 0);
        LocalTime businessEnd = policy.getBusinessHoursEnd() != null ? policy.getBusinessHoursEnd() : LocalTime.of(18, 0);
        boolean includeHolidays = Boolean.TRUE.equals(policy.getIncludeHolidays());

        long dailyBusinessMinutes = ChronoUnit.MINUTES.between(businessStart, businessEnd);
        if (dailyBusinessMinutes <= 0) {
            // 24時間稼働または設定不正時は単純時間加算
            return start.plusSeconds((long) targetHours * 3600);
        }

        ZonedDateTime cursor = start.atZone(effectiveZone);

        // 始点を営業時間内にアライメント
        cursor = alignToBusinessHours(cursor, businessStart, businessEnd, includeHolidays, organizationId, legalEntityId, effectiveZone);

        long remainingMinutes = (long) targetHours * 60;
        while (remainingMinutes > 0) {
            ZonedDateTime endOfToday = cursor.toLocalDate().atTime(businessEnd).atZone(effectiveZone);
            long availableMinutesToday = ChronoUnit.MINUTES.between(cursor, endOfToday);

            if (remainingMinutes <= availableMinutesToday) {
                cursor = cursor.plusMinutes(remainingMinutes);
                remainingMinutes = 0;
            } else {
                remainingMinutes -= availableMinutesToday;
                LocalDate nextWorkDate = getNextWorkDay(cursor.toLocalDate().plusDays(1), includeHolidays, organizationId, legalEntityId);
                cursor = nextWorkDate.atTime(businessStart).atZone(effectiveZone);
            }
        }

        return cursor.toInstant();
    }

    /**
     * SLA期限日時の計算 (LocalDateTime 互換)
     */
    public LocalDateTime calculateDeadline(LocalDateTime startAt, int targetHours, ServiceSlaPolicy policy) {
        return calculateDeadline(startAt, targetHours, policy, null, null, DEFAULT_ZONE);
    }

    public LocalDateTime calculateDeadline(LocalDateTime startAt, int targetHours, ServiceSlaPolicy policy,
                                          Long organizationId, Long legalEntityId) {
        return calculateDeadline(startAt, targetHours, policy, organizationId, legalEntityId, DEFAULT_ZONE);
    }

    public Instant calculateDeadline(Instant startAt, int targetHours, ServiceSlaPolicy policy) {
        return calculateDeadline(startAt, targetHours, policy, null, null, DEFAULT_ZONE);
    }

    public LocalDateTime calculateDeadline(LocalDateTime startAt, int targetHours, ServiceSlaPolicy policy,
                                          Long organizationId, Long legalEntityId, ZoneId zoneId) {
        ZoneId effectiveZone = zoneId != null ? zoneId : DEFAULT_ZONE;
        Instant startInstant = startAt != null ? startAt.atZone(effectiveZone).toInstant() : clock.instant();
        Instant deadlineInstant = calculateDeadline(startInstant, targetHours, policy, organizationId, legalEntityId, effectiveZone);
        return deadlineInstant.atZone(effectiveZone).toLocalDateTime();
    }

    /**
     * 停止時間(分)を加味した延長期限の計算 (Instant)
     */
    public Instant calculateExtendedDeadline(Instant currentDeadline, int pauseMinutes, ServiceSlaPolicy policy,
                                            Long organizationId, Long legalEntityId, ZoneId zoneId) {
        if (currentDeadline == null || pauseMinutes <= 0) {
            return currentDeadline;
        }

        ZoneId effectiveZone = zoneId != null ? zoneId : DEFAULT_ZONE;
        if (policy == null) {
            return currentDeadline.plusSeconds((long) pauseMinutes * 60);
        }

        LocalTime businessStart = policy.getBusinessHoursStart() != null ? policy.getBusinessHoursStart() : LocalTime.of(9, 0);
        LocalTime businessEnd = policy.getBusinessHoursEnd() != null ? policy.getBusinessHoursEnd() : LocalTime.of(18, 0);
        boolean includeHolidays = Boolean.TRUE.equals(policy.getIncludeHolidays());

        long dailyBusinessMinutes = ChronoUnit.MINUTES.between(businessStart, businessEnd);
        if (dailyBusinessMinutes <= 0) {
            return currentDeadline.plusSeconds((long) pauseMinutes * 60);
        }

        ZonedDateTime cursor = currentDeadline.atZone(effectiveZone);
        cursor = alignToBusinessHours(cursor, businessStart, businessEnd, includeHolidays, organizationId, legalEntityId, effectiveZone);

        long remainingMinutes = pauseMinutes;
        while (remainingMinutes > 0) {
            ZonedDateTime endOfToday = cursor.toLocalDate().atTime(businessEnd).atZone(effectiveZone);
            long availableMinutesToday = ChronoUnit.MINUTES.between(cursor, endOfToday);

            if (remainingMinutes <= availableMinutesToday) {
                cursor = cursor.plusMinutes(remainingMinutes);
                remainingMinutes = 0;
            } else {
                remainingMinutes -= availableMinutesToday;
                LocalDate nextWorkDate = getNextWorkDay(cursor.toLocalDate().plusDays(1), includeHolidays, organizationId, legalEntityId);
                cursor = nextWorkDate.atTime(businessStart).atZone(effectiveZone);
            }
        }

        return cursor.toInstant();
    }

    public LocalDateTime calculateExtendedDeadline(LocalDateTime currentDeadline, int pauseMinutes, ServiceSlaPolicy policy) {
        return calculateExtendedDeadline(currentDeadline, pauseMinutes, policy, null, null, DEFAULT_ZONE);
    }

    public LocalDateTime calculateExtendedDeadline(LocalDateTime currentDeadline, int pauseMinutes, ServiceSlaPolicy policy,
                                                  Long organizationId, Long legalEntityId, ZoneId zoneId) {
        if (currentDeadline == null) {
            return null;
        }
        ZoneId effectiveZone = zoneId != null ? zoneId : DEFAULT_ZONE;
        Instant instant = currentDeadline.atZone(effectiveZone).toInstant();
        Instant extended = calculateExtendedDeadline(instant, pauseMinutes, policy, organizationId, legalEntityId, effectiveZone);
        return extended.atZone(effectiveZone).toLocalDateTime();
    }

    /**
     * 現在時刻がSLA営業時間内であるか判定
     */
    public boolean isBusinessTime(Instant instant, ServiceSlaPolicy policy,
                                 Long organizationId, Long legalEntityId, ZoneId zoneId) {
        if (instant == null || policy == null) {
            return true;
        }
        ZoneId effectiveZone = zoneId != null ? zoneId : DEFAULT_ZONE;
        ZonedDateTime zdt = instant.atZone(effectiveZone);

        if (!Boolean.TRUE.equals(policy.getIncludeHolidays()) && isNonWorkingDay(zdt.toLocalDate(), organizationId, legalEntityId)) {
            return false;
        }

        LocalTime businessStart = policy.getBusinessHoursStart() != null ? policy.getBusinessHoursStart() : LocalTime.of(9, 0);
        LocalTime businessEnd = policy.getBusinessHoursEnd() != null ? policy.getBusinessHoursEnd() : LocalTime.of(18, 0);
        LocalTime time = zdt.toLocalTime();

        return !time.isBefore(businessStart) && time.isBefore(businessEnd);
    }

    private ZonedDateTime alignToBusinessHours(ZonedDateTime dt, LocalTime start, LocalTime end,
                                               boolean includeHolidays, Long organizationId, Long legalEntityId, ZoneId zoneId) {
        LocalDate date = dt.toLocalDate();
        LocalTime time = dt.toLocalTime();

        if (!includeHolidays && isNonWorkingDay(date, organizationId, legalEntityId)) {
            LocalDate nextWorkDate = getNextWorkDay(date, includeHolidays, organizationId, legalEntityId);
            return nextWorkDate.atTime(start).atZone(zoneId);
        }

        if (time.isBefore(start)) {
            return date.atTime(start).atZone(zoneId);
        } else if (!time.isBefore(end)) {
            LocalDate nextWorkDate = getNextWorkDay(date.plusDays(1), includeHolidays, organizationId, legalEntityId);
            return nextWorkDate.atTime(start).atZone(zoneId);
        }

        return dt;
    }

    private LocalDate getNextWorkDay(LocalDate startDate, boolean includeHolidays, Long organizationId, Long legalEntityId) {
        LocalDate current = startDate;
        int safetyLimit = 365;
        while (!includeHolidays && isNonWorkingDay(current, organizationId, legalEntityId)) {
            current = current.plusDays(1);
            if (--safetyLimit <= 0) {
                break;
            }
        }
        return current;
    }

    public boolean isNonWorkingDay(LocalDate date, Long organizationId, Long legalEntityId) {
        // 1. 土日の標準判定
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return true;
        }

        // 2. 所属法人・組織に限定した勤務カレンダーの探索（他法人の休日とはUNIONしない）
        WorkCalendar calendar = findCalendarForScope(date, organizationId, legalEntityId);
        if (calendar == null) {
            return false;
        }

        WorkCalendarDay day = workCalendarDayMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WorkCalendarDay>()
                        .eq("calendar_id", calendar.getId())
                        .eq("calendar_date", date)
        );

        if (day == null) {
            return false;
        }

        String type = day.getDayType();
        if (type != null) {
            if (type.contains("休") || type.contains("祝") || "HOLIDAY".equalsIgnoreCase(type) || "NON_WORKING".equalsIgnoreCase(type)) {
                return true;
            }
            if ("通常".equals(type) || "所定日".equals(type) || "WORKING".equalsIgnoreCase(type)) {
                return false;
            }
        }

        return day.getScheduledMinutes() != null && day.getScheduledMinutes() == 0;
    }

    private WorkCalendar findCalendarForScope(LocalDate date, Long organizationId, Long legalEntityId) {
        LambdaQueryWrapper<WorkCalendar> wrapper = new LambdaQueryWrapper<WorkCalendar>()
                .isNull(WorkCalendar::getEngineerId)
                .and(w -> w.eq(WorkCalendar::getStatus, "有効").or().eq(WorkCalendar::getStatus, "ACTIVE"))
                .le(WorkCalendar::getValidFrom, date)
                .and(w -> w.isNull(WorkCalendar::getValidTo).or().ge(WorkCalendar::getValidTo, date));

        List<WorkCalendar> candidates = workCalendarMapper.selectList(wrapper);
        if (candidates.isEmpty()) {
            return null;
        }

        // 優先度1: 組織ID & 法人ID 完全一致
        if (organizationId != null && legalEntityId != null) {
            WorkCalendar matched = candidates.stream()
                    .filter(c -> organizationId.equals(c.getOrganizationId()) && legalEntityId.equals(c.getLegalEntityId()))
                    .max(Comparator.comparing(WorkCalendar::getValidFrom, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            if (matched != null) return matched;
        }

        // 優先度2: 法人ID 一致 (組織IDはnull)
        if (legalEntityId != null) {
            WorkCalendar matched = candidates.stream()
                    .filter(c -> legalEntityId.equals(c.getLegalEntityId()) && c.getOrganizationId() == null)
                    .max(Comparator.comparing(WorkCalendar::getValidFrom, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            if (matched != null) return matched;
        }

        // 優先度3: 組織ID 一致 (法人IDはnull)
        if (organizationId != null) {
            WorkCalendar matched = candidates.stream()
                    .filter(c -> organizationId.equals(c.getOrganizationId()) && c.getLegalEntityId() == null)
                    .max(Comparator.comparing(WorkCalendar::getValidFrom, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            if (matched != null) return matched;
        }

        // 優先度4: 全社共通カレンダー (組織ID・法人IDともにnull)
        return candidates.stream()
                .filter(c -> c.getOrganizationId() == null && c.getLegalEntityId() == null)
                .max(Comparator.comparing(WorkCalendar::getValidFrom, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }
}
