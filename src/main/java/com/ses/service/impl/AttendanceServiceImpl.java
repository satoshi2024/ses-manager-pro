package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.attendance.AttendanceDayDto;
import com.ses.dto.attendance.AttendanceDayRequest;
import com.ses.dto.attendance.AttendanceMonthDto;
import com.ses.dto.attendance.AttendanceOverviewDto;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.EmployeeAttendance;
import com.ses.entity.Engineer;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EmployeeAttendanceMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.AttendanceService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 雇用勤怠の本人/管理scopeと状態CASを一箇所へ閉じ込める。
 * 客先工数（t_work_record_daily）へ接続しない。
 */
@Service
@RequiredArgsConstructor
public class AttendanceServiceImpl implements AttendanceService {

    static final String INPUT = "入力中";
    static final String SUBMITTED = "提出済";
    static final String RETURNED = "差戻し";
    static final String APPROVED = "承認済";
    static final String CLOSED = "締め済";

    private final AttendanceMonthMapper attendanceMonthMapper;
    private final EmployeeAttendanceMapper employeeAttendanceMapper;
    private final EngineerMapper engineerMapper;
    private final EngineerAccountLinkService engineerAccountLinkService;
    private final OrganizationScopeService organizationScopeService;

    @Override
    @Transactional(readOnly = true)
    public AttendanceOverviewDto mine(String month) {
        YearMonth target = parseMonth(month);
        Long engineerId = currentEngineerId();
        return buildOverview(target, List.of(engineerId));
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceOverviewDto management(String month) {
        YearMonth target = parseMonth(month);
        requireManagementRole();
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role) || "HR".equals(role)) {
            return buildOverview(target, null);
        }
        // managerの空集合は「制限なし」ではなく、可視対象0件の意味。
        Set<Long> allowed = organizationScopeService.allowedEngineerIds(target.atEndOfMonth());
        return buildOverview(target, new ArrayList<>(allowed));
    }

    @Override
    @Transactional
    public void saveMyDay(AttendanceDayRequest request) {
        if (request == null || request.getWorkDate() == null) {
            throw BusinessException.of(400, "error.attendance.invalidDate");
        }
        Long engineerId = currentEngineerId();
        YearMonth target = YearMonth.from(request.getWorkDate());
        AttendanceMonth month = lockOrCreateMonth(engineerId, target);
        assertEditable(month);
        EmployeeAttendance values = toAttendance(request, engineerId);
        EmployeeAttendance existing = employeeAttendanceMapper.selectOne(
                new LambdaQueryWrapper<EmployeeAttendance>()
                        .eq(EmployeeAttendance::getEngineerId, engineerId)
                        .eq(EmployeeAttendance::getWorkDate, request.getWorkDate())
                        .eq(EmployeeAttendance::getSource, "manual")
                        .orderByDesc(EmployeeAttendance::getId)
                        .last("LIMIT 1 FOR UPDATE"));
        if (existing == null) {
            employeeAttendanceMapper.insert(values);
        } else {
            values.setId(existing.getId());
            values.setVersion(existing.getVersion());
            values.setCreatedAt(existing.getCreatedAt());
            employeeAttendanceMapper.updateById(values);
        }
        refreshAggregate(month);
    }

    @Override
    @Transactional
    public void deleteMyDay(String month, String workDate) {
        YearMonth target = parseMonth(month);
        LocalDate date = parseDate(workDate);
        if (!target.equals(YearMonth.from(date))) {
            throw BusinessException.of(400, "error.attendance.invalidDate");
        }
        Long engineerId = currentEngineerId();
        AttendanceMonth snapshot = lockExistingMonth(engineerId, target);
        assertEditable(snapshot);
        EmployeeAttendance existing = employeeAttendanceMapper.selectOne(
                new LambdaQueryWrapper<EmployeeAttendance>()
                        .eq(EmployeeAttendance::getEngineerId, engineerId)
                        .eq(EmployeeAttendance::getWorkDate, date)
                        .eq(EmployeeAttendance::getSource, "manual")
                        .orderByDesc(EmployeeAttendance::getId)
                        .last("LIMIT 1 FOR UPDATE"));
        if (existing != null) {
            employeeAttendanceMapper.deleteById(existing.getId());
            refreshAggregate(snapshot);
        }
    }

    @Override
    @Transactional
    public void submitMyMonth(String month) {
        YearMonth target = parseMonth(month);
        Long engineerId = currentEngineerId();
        AttendanceMonth current = lockExistingMonth(engineerId, target);
        if (!INPUT.equals(current.getStatus()) && !RETURNED.equals(current.getStatus())) {
            throw BusinessException.of(400, "error.attendance.invalidTransition", current.getStatus(), SUBMITTED);
        }
        casUpdate(current, SUBMITTED, update -> update
                .set(AttendanceMonth::getSubmittedAt, LocalDateTime.now())
                .set(AttendanceMonth::getSubmittedBy, SecurityUtils.currentUserId()));
    }

    @Override
    @Transactional
    public void reject(Long engineerId, String month) {
        requireManagerRole();
        AttendanceMonth current = lockExistingMonth(allowedEngineerId(engineerId, month), month);
        if (!SUBMITTED.equals(current.getStatus()) && !APPROVED.equals(current.getStatus())) {
            throw BusinessException.of(400, "error.attendance.invalidTransition", current.getStatus(), RETURNED);
        }
        casUpdate(current, RETURNED, update -> {
            if (APPROVED.equals(current.getStatus())) {
                update.set(AttendanceMonth::getApprovedAt, null)
                        .set(AttendanceMonth::getApprovedBy, null);
            }
            return update;
        });
    }

    @Override
    @Transactional
    public void approve(Long engineerId, String month) {
        requireManagerRole();
        transition(lockExistingMonth(allowedEngineerId(engineerId, month), month), SUBMITTED, APPROVED,
                SecurityUtils.currentUserId());
    }

    @Override
    @Transactional
    public void close(Long engineerId, String month) {
        requireHrOrAdminRole();
        transition(lockExistingMonth(allowedEngineerId(engineerId, month), month), APPROVED, CLOSED,
                SecurityUtils.currentUserId());
    }

    @Override
    @Transactional
    public void reopen(Long engineerId, String month) {
        if (!"管理者".equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "error.attendance.roleDenied");
        }
        AttendanceMonth current = lockExistingMonth(allowedEngineerId(engineerId, month), month);
        if (!CLOSED.equals(current.getStatus())) {
            throw BusinessException.of(400, "error.attendance.invalidTransition", current.getStatus(), APPROVED);
        }
        casUpdate(current, APPROVED, update -> update
                .set(AttendanceMonth::getClosedAt, null)
                .set(AttendanceMonth::getClosedBy, null));
    }

    private AttendanceOverviewDto buildOverview(YearMonth target, List<Long> engineerIds) {
        List<Engineer> engineers = engineerIds == null
                ? engineerMapper.selectList(null)
                : engineerIds.isEmpty() ? List.of() : engineerMapper.selectBatchIds(engineerIds);
        engineers = engineers.stream().sorted(Comparator.comparing(Engineer::getId)).toList();
        Set<Long> ids = engineers.stream().map(Engineer::getId).collect(Collectors.toSet());

        LambdaQueryWrapper<AttendanceMonth> monthQuery = new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getWorkMonth, target.atDay(1))
                .orderByAsc(AttendanceMonth::getEngineerId);
        if (engineerIds != null) {
            if (ids.isEmpty()) {
                return overview(target, List.of());
            }
            monthQuery.in(AttendanceMonth::getEngineerId, ids);
        }
        List<AttendanceMonth> months = attendanceMonthMapper.selectList(monthQuery);
        if (months.isEmpty()) {
            return overview(target, List.of());
        }

        Map<Long, String> names = engineers.stream().collect(Collectors.toMap(Engineer::getId,
                e -> e.getFullName() == null ? "" : e.getFullName()));
        LambdaQueryWrapper<EmployeeAttendance> dayQuery = new LambdaQueryWrapper<EmployeeAttendance>()
                .ge(EmployeeAttendance::getWorkDate, target.atDay(1))
                .le(EmployeeAttendance::getWorkDate, target.atEndOfMonth())
                .orderByAsc(EmployeeAttendance::getWorkDate);
        if (engineerIds != null) {
            dayQuery.in(EmployeeAttendance::getEngineerId, ids);
        }
        Map<Long, List<EmployeeAttendance>> daysByEngineer = employeeAttendanceMapper.selectList(dayQuery)
                .stream().collect(Collectors.groupingBy(EmployeeAttendance::getEngineerId));

        List<AttendanceMonthDto> result = months.stream().map(month -> toMonthDto(month, names.get(month.getEngineerId()),
                daysByEngineer.getOrDefault(month.getEngineerId(), List.of()))).toList();
        return overview(target, result);
    }

    private AttendanceOverviewDto overview(YearMonth target, List<AttendanceMonthDto> months) {
        AttendanceOverviewDto dto = new AttendanceOverviewDto();
        dto.setMonth(target.toString());
        dto.setMonths(months);
        return dto;
    }

    private AttendanceMonthDto toMonthDto(AttendanceMonth month, String engineerName,
                                          List<EmployeeAttendance> days) {
        AttendanceMonthDto dto = new AttendanceMonthDto();
        dto.setId(month.getId());
        dto.setEngineerId(month.getEngineerId());
        dto.setEngineerName(engineerName);
        dto.setWorkMonth(month.getWorkMonth());
        dto.setWorkedMinutes(month.getWorkedMinutes());
        dto.setRegularMinutes(month.getRegularMinutes());
        dto.setOvertimeMinutes(month.getOvertimeMinutes());
        dto.setHolidayMinutes(month.getHolidayMinutes());
        dto.setLateNightMinutes(month.getLateNightMinutes());
        dto.setLeaveMinutes(month.getLeaveMinutes());
        dto.setStatus(month.getStatus());
        dto.setVersion(month.getVersion());
        dto.setDays(days.stream().map(this::toDayDto).toList());
        return dto;
    }

    private AttendanceDayDto toDayDto(EmployeeAttendance day) {
        AttendanceDayDto dto = new AttendanceDayDto();
        dto.setId(day.getId());
        dto.setEngineerId(day.getEngineerId());
        dto.setWorkDate(day.getWorkDate());
        dto.setClockIn(day.getClockIn());
        dto.setClockOut(day.getClockOut());
        dto.setBreakMinutes(day.getBreakMinutes());
        dto.setWorkedMinutes(sum(day.getRegularMinutes(), day.getOvertimeMinutes(), day.getHolidayMinutes()));
        dto.setWorkType(day.getWorkType());
        dto.setWorkplaceType(day.getWorkplaceType());
        dto.setStatus(day.getStatus());
        dto.setRemarks(day.getRemarks());
        dto.setVersion(day.getVersion());
        return dto;
    }

    private EmployeeAttendance toAttendance(AttendanceDayRequest request, Long engineerId) {
        int worked = workedMinutes(request.getClockIn(), request.getClockOut(), request.getBreakMinutes());
        String workType = request.getWorkType();
        if (workType == null || !Set.of("通常", "所定休日", "法定休日").contains(workType)) {
            throw BusinessException.of(400, "error.attendance.unknownWorkType");
        }
        int regular = "通常".equals(workType) ? worked : 0;
        int overtime = "所定休日".equals(workType) ? worked : 0;
        int holiday = "法定休日".equals(workType) ? worked : 0;
        return EmployeeAttendance.builder()
                .engineerId(engineerId)
                .workDate(request.getWorkDate())
                .clockIn(request.getClockIn())
                .clockOut(request.getClockOut())
                .breakMinutes(request.getBreakMinutes() == null ? 0 : request.getBreakMinutes())
                .regularMinutes(regular)
                .overtimeMinutes(overtime)
                .holidayMinutes(holiday)
                .lateNightMinutes(0)
                .workType(workType)
                .workplaceType(request.getWorkplaceType())
                .source("manual")
                .status(INPUT)
                .remarks(request.getRemarks())
                .version(0)
                .build();
    }

    private void refreshAggregate(AttendanceMonth month) {
        List<EmployeeAttendance> days = employeeAttendanceMapper.selectList(
                new LambdaQueryWrapper<EmployeeAttendance>()
                        .eq(EmployeeAttendance::getEngineerId, month.getEngineerId())
                        .ge(EmployeeAttendance::getWorkDate, month.getWorkMonth())
                        .le(EmployeeAttendance::getWorkDate, YearMonth.from(month.getWorkMonth()).atEndOfMonth()));
        int regular = days.stream().mapToInt(d -> value(d.getRegularMinutes())).sum();
        int overtime = days.stream().mapToInt(d -> value(d.getOvertimeMinutes())).sum();
        int holiday = days.stream().mapToInt(d -> value(d.getHolidayMinutes())).sum();
        int lateNight = days.stream().mapToInt(d -> value(d.getLateNightMinutes())).sum();
        int worked = regular + overtime + holiday;
        int version = value(month.getVersion());
        int updated = attendanceMonthMapper.update(null, new LambdaUpdateWrapper<AttendanceMonth>()
                .set(AttendanceMonth::getWorkedMinutes, worked)
                .set(AttendanceMonth::getRegularMinutes, regular)
                .set(AttendanceMonth::getOvertimeMinutes, overtime)
                .set(AttendanceMonth::getHolidayMinutes, holiday)
                .set(AttendanceMonth::getLateNightMinutes, lateNight)
                .set(AttendanceMonth::getVersion, version + 1)
                .set(AttendanceMonth::getUpdatedAt, LocalDateTime.now())
                .eq(AttendanceMonth::getId, month.getId())
                .eq(AttendanceMonth::getVersion, version)
                .eq(AttendanceMonth::getStatus, month.getStatus()));
        if (updated != 1) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    private AttendanceMonth lockOrCreateMonth(Long engineerId, YearMonth target) {
        AttendanceMonth existing = findMonthForUpdate(engineerId, target);
        if (existing != null) {
            return existing;
        }
        Engineer engineer = engineerMapper.selectById(engineerId);
        if (engineer == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        AttendanceMonth created = AttendanceMonth.builder()
                .engineerId(engineerId)
                .organizationId(engineer.getOrganizationId())
                .workMonth(target.atDay(1))
                .scheduledMinutes(0)
                .workedMinutes(0)
                .regularMinutes(0)
                .overtimeMinutes(0)
                .holidayMinutes(0)
                .lateNightMinutes(0)
                .leaveMinutes(0)
                .status(INPUT)
                .version(0)
                .build();
        try {
            attendanceMonthMapper.insert(created);
        } catch (DuplicateKeyException duplicate) {
            // UNIQUE(engineer_id, work_month)競合は再読込してCASへ進める。
        }
        AttendanceMonth locked = findMonthForUpdate(engineerId, target);
        if (locked == null) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
        return locked;
    }

    private AttendanceMonth lockExistingMonth(Long engineerId, YearMonth target) {
        AttendanceMonth month = findMonthForUpdate(engineerId, target);
        if (month == null) {
            throw BusinessException.of(404, "error.attendance.monthNotFound");
        }
        return month;
    }

    private AttendanceMonth lockExistingMonth(Long engineerId, String month) {
        return lockExistingMonth(engineerId, parseMonth(month));
    }

    private AttendanceMonth findMonthForUpdate(Long engineerId, YearMonth target) {
        return attendanceMonthMapper.selectOne(new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getEngineerId, engineerId)
                .eq(AttendanceMonth::getWorkMonth, target.atDay(1))
                .last("FOR UPDATE"));
    }

    private void transition(AttendanceMonth month, String expected, String target, Long actorId) {
        if (!expected.equals(month.getStatus())) {
            throw BusinessException.of(400, "error.attendance.invalidTransition", month.getStatus(), target);
        }
        casUpdate(month, target, update -> {
            if (SUBMITTED.equals(target)) {
                update.set(AttendanceMonth::getSubmittedAt, LocalDateTime.now())
                        .set(AttendanceMonth::getSubmittedBy, SecurityUtils.currentUserId());
            } else if (APPROVED.equals(target)) {
                update.set(AttendanceMonth::getApprovedAt, LocalDateTime.now())
                        .set(AttendanceMonth::getApprovedBy, actorId);
            } else if (CLOSED.equals(target)) {
                update.set(AttendanceMonth::getClosedAt, LocalDateTime.now())
                        .set(AttendanceMonth::getClosedBy, actorId);
            }
            return update;
        });
    }

    private void casUpdate(AttendanceMonth month, String target,
                           java.util.function.UnaryOperator<LambdaUpdateWrapper<AttendanceMonth>> customizer) {
        int version = value(month.getVersion());
        LambdaUpdateWrapper<AttendanceMonth> update = new LambdaUpdateWrapper<AttendanceMonth>()
                .set(AttendanceMonth::getStatus, target)
                .set(AttendanceMonth::getVersion, version + 1)
                .set(AttendanceMonth::getUpdatedAt, LocalDateTime.now())
                .eq(AttendanceMonth::getId, month.getId())
                .eq(AttendanceMonth::getStatus, month.getStatus())
                .eq(AttendanceMonth::getVersion, version);
        customizer.apply(update);
        if (attendanceMonthMapper.update(null, update) != 1) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    private Long allowedEngineerId(Long engineerId, String month) {
        YearMonth target = parseMonth(month);
        if (engineerId == null) {
            throw BusinessException.of(400, "error.attendance.engineerRequired");
        }
        if ("管理者".equals(SecurityUtils.currentRole()) || "HR".equals(SecurityUtils.currentRole())) {
            return engineerId;
        }
        // managerのasOf判定は、対象月末の組織所属へ固定する。
        if (!organizationScopeService.allowedEngineerIds(target.atEndOfMonth()).contains(engineerId)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return engineerId;
    }

    private void assertEditable(AttendanceMonth month) {
        if (!INPUT.equals(month.getStatus()) && !RETURNED.equals(month.getStatus())) {
            throw BusinessException.of(400, "error.attendance.closedEdit");
        }
    }

    private Long currentEngineerId() {
        Long userId = SecurityUtils.currentUserId();
        Long engineerId = userId == null ? null : engineerAccountLinkService.findEngineerIdByUserId(userId);
        if (engineerId == null) {
            throw BusinessException.of(403, "error.attendance.notLinked");
        }
        return engineerId;
    }

    private void requireManagementRole() {
        String role = SecurityUtils.currentRole();
        if (!Set.of("管理者", "HR", "マネージャー").contains(role)) {
            throw BusinessException.of(403, "error.attendance.roleDenied");
        }
    }

    private void requireManagerRole() {
        String role = SecurityUtils.currentRole();
        if (!Set.of("管理者", "マネージャー").contains(role)) {
            throw BusinessException.of(403, "error.attendance.roleDenied");
        }
    }

    private void requireHrOrAdminRole() {
        String role = SecurityUtils.currentRole();
        if (!Set.of("管理者", "HR").contains(role)) {
            throw BusinessException.of(403, "error.attendance.roleDenied");
        }
    }

    private YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException e) {
            throw BusinessException.of(400, "error.attendance.invalidMonth");
        }
    }

    private LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException | NullPointerException e) {
            throw BusinessException.of(400, "error.attendance.invalidDate");
        }
    }

    private int workedMinutes(LocalTime in, LocalTime out, Integer breakMinutes) {
        if (in == null && out == null) {
            return 0;
        }
        if (in == null || out == null) {
            throw BusinessException.of(400, "error.attendance.invalidTime");
        }
        long minutes = Duration.between(in, out).toMinutes();
        if (minutes < 0) {
            minutes += 24 * 60;
        }
        int breaks = breakMinutes == null ? 0 : breakMinutes;
        if (breaks < 0 || minutes > 24 * 60 || breaks > minutes) {
            throw BusinessException.of(400, "error.attendance.invalidTime");
        }
        return (int) minutes - breaks;
    }

    private int sum(Integer... values) {
        int total = 0;
        for (Integer value : values) {
            total += value(value);
        }
        return total;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
