package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.attendance.AttendanceBreakDto;
import com.ses.dto.attendance.AttendanceBreakRequest;
import com.ses.dto.attendance.AttendanceDayDto;
import com.ses.dto.attendance.AttendanceDayRequest;
import com.ses.dto.attendance.AttendanceMonthDto;
import com.ses.dto.attendance.AttendanceOverviewDto;
import com.ses.entity.EmployeeAttendance;
import com.ses.entity.EmployeeAttendanceBreak;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.Engineer;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EmployeeAttendanceBreakMapper;
import com.ses.mapper.EmployeeAttendanceMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalRequestCommand;
import com.ses.service.attendance.AttendanceCalculation;
import com.ses.service.attendance.AttendanceCalculator;
import com.ses.service.attendance.AttendanceScopeResolver;
import com.ses.service.attendance.AttendanceScopeSnapshot;
import com.ses.service.attendance.overtime.OvertimeComplianceService;
import com.ses.service.AttendanceService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final EmployeeAttendanceBreakMapper employeeAttendanceBreakMapper;
    private final EngineerMapper engineerMapper;
    private final EngineerAccountLinkService engineerAccountLinkService;
    private final OrganizationScopeService organizationScopeService;
    private final AttendanceCalculator attendanceCalculator;
    private final AttendanceScopeResolver attendanceScopeResolver;
    private final ApprovalEngineService approvalEngineService;
    private final WorkCalendarDayMapper workCalendarDayMapper;
    private final OvertimeComplianceService overtimeComplianceService;

    @Override
    @Transactional(readOnly = true)
    public AttendanceOverviewDto mine(String month) {
        YearMonth target = parseMonth(month);
        Long engineerId = currentEngineerId();
        // 本人は1名分のため日次を同梱する（my.html が days を直接描画する）。
        return buildOverview(target, List.of(engineerId), null, 1L, 1L, true);
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceOverviewDto management(String month) {
        return management(month, 1L, 50L);
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceOverviewDto management(String month, Long current, Long size) {
        YearMonth target = parseMonth(month);
        requireManagementRole();
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return buildOverview(target, null, null, current, size, false);
        }
        if ("HR".equals(role)) {
            Set<Long> allowedLegalEntities = attendanceScopeResolver.allowedHrLegalEntityIds(
                    SecurityUtils.currentUserId(), target.atEndOfMonth());
            return buildOverview(target, null, allowedLegalEntities, current, size, false);
        }
        // organization scope無効時の空集合は「制限なし」。有効時の空集合だけが可視0件。
        Set<Long> allowed = organizationScopeService.hasFullAccess()
                ? null : organizationScopeService.allowedEngineerIds(target.atEndOfMonth());
        return buildOverview(target, allowed == null ? null : new ArrayList<>(allowed), null,
                current, size, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceDayDto> managementDays(Long engineerId, String month) {
        YearMonth target = parseMonth(month);
        requireManagementRole();
        Long allowed = allowedEngineerId(engineerId, month);
        if ("HR".equals(SecurityUtils.currentRole())) {
            AttendanceMonth monthRow = attendanceMonthMapper.selectOne(new LambdaQueryWrapper<AttendanceMonth>()
                    .eq(AttendanceMonth::getEngineerId, allowed)
                    .eq(AttendanceMonth::getWorkMonth, target.atDay(1))
                    .last("LIMIT 1"));
            if (monthRow == null) {
                return List.of();
            }
            assertHrMonthSnapshotAllowed(monthRow, target);
        }
        List<EmployeeAttendance> days = employeeAttendanceMapper.selectList(
                new LambdaQueryWrapper<EmployeeAttendance>()
                        .eq(EmployeeAttendance::getEngineerId, allowed)
                        .ge(EmployeeAttendance::getWorkDate, target.atDay(1))
                        .le(EmployeeAttendance::getWorkDate, target.atEndOfMonth())
                        .orderByAsc(EmployeeAttendance::getWorkDate));
        return days.stream().map(this::toDayDto).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveMyDay(AttendanceDayRequest request) {
        if (request == null || request.getWorkDate() == null) {
            throw BusinessException.of(400, "error.attendance.invalidDate");
        }
        Long engineerId = currentEngineerId();
        YearMonth target = YearMonth.from(request.getWorkDate());
        Long userId = SecurityUtils.currentUserId();
        AttendanceScopeSnapshot scope = attendanceScopeResolver.requireSnapshot(
                engineerId, userId, request.getWorkDate());
        AttendanceMonth month = lockOrCreateMonth(engineerId, target, scope);
        assertEditable(month);
        List<AttendanceCalculator.BreakInterval> intervals = toBreakIntervals(request);
        assertBreakMinutesMatch(request.getBreakMinutes(), intervals);
        EmployeeAttendance values = toAttendance(request, engineerId, scope, intervals);
        EmployeeAttendance existing = employeeAttendanceMapper.selectOne(
                new LambdaQueryWrapper<EmployeeAttendance>()
                        .eq(EmployeeAttendance::getEngineerId, engineerId)
                        .eq(EmployeeAttendance::getWorkDate, request.getWorkDate())
                        .eq(EmployeeAttendance::getSource, "manual")
                        .orderByDesc(EmployeeAttendance::getId)
                        .last("LIMIT 1 FOR UPDATE"));
        if (existing == null) {
            employeeAttendanceMapper.insert(values);
            replaceBreaks(values.getId(), intervals);
        } else {
            // 既存のbreakMinutes > 0で区間を持たない行（区間不明）は、区間なしの再保存を拒否する。
            if (value(existing.getBreakMinutes()) > 0 && loadBreakOffsets(existing.getId()).isEmpty()
                    && intervals.isEmpty()) {
                throw BusinessException.of(400, "error.attendance.breakUnknown");
            }
            values.setId(existing.getId());
            values.setVersion(existing.getVersion());
            values.setCreatedAt(existing.getCreatedAt());
            employeeAttendanceMapper.updateById(values);
            replaceBreaks(existing.getId(), intervals);
        }
        refreshAggregate(month);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
            employeeAttendanceBreakMapper.delete(new LambdaQueryWrapper<EmployeeAttendanceBreak>()
                    .eq(EmployeeAttendanceBreak::getAttendanceId, existing.getId()));
            employeeAttendanceMapper.deleteById(existing.getId());
            refreshAggregate(snapshot);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long engineerId, String month) {
        requireManagerRole();
        transition(lockExistingMonth(allowedEngineerId(engineerId, month), month), SUBMITTED, APPROVED,
                SecurityUtils.currentUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(Long engineerId, String month) {
        requireHrOrAdminRole();
        YearMonth target = parseMonth(month);
        AttendanceMonth current = lockExistingMonth(engineerId, target);
        assertHrMonthSnapshotAllowed(current, target);
        transition(current, APPROVED, CLOSED,
                SecurityUtils.currentUserId());
        // 月次締め後に36協定判定→followup UPSERT＋段階通知（S11-P0-01）
        overtimeComplianceService.evaluateAndPersist(engineerId, target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reopen(Long engineerId, String month, String reason) {
        if (!"管理者".equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "error.attendance.roleDenied");
        }
        AttendanceMonth current = lockExistingMonth(allowedEngineerId(engineerId, month), month);
        if (!CLOSED.equals(current.getStatus())) {
            throw BusinessException.of(400, "error.attendance.invalidTransition", current.getStatus(), APPROVED);
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw BusinessException.of(400, "error.attendance.reopenReasonRequired");
        }
        approvalEngineService.request(new ApprovalRequestCommand(
                com.ses.service.attendance.AttendanceReopenApprovalAdapter.REQUEST_TYPE,
                "ATTENDANCE_MONTH", current.getId(), (long) value(current.getVersion()),
                SecurityUtils.currentUserId(), current.getOrganizationId(), null,
                Map.of("reason", reason.trim(), "engineerId", current.getEngineerId(),
                        "workMonth", current.getWorkMonth().toString()),
                Map.of("beforeStatus", CLOSED, "afterStatus", APPROVED, "reason", reason.trim()),
                "attendance-reopen:" + current.getId() + ":" + value(current.getVersion())));
    }

    private AttendanceOverviewDto buildOverview(YearMonth target, List<Long> engineerIds,
                                                Set<Long> legalEntityIds, Long current, Long size,
                                                boolean includeDays) {
        if (engineerIds != null && engineerIds.isEmpty()) {
            return overview(target, List.of(), 0, current, size);
        }
        if (legalEntityIds != null && legalEntityIds.isEmpty()) {
            return overview(target, List.of(), 0, current, size);
        }
        LambdaQueryWrapper<AttendanceMonth> monthQuery = new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getWorkMonth, target.atDay(1))
                .orderByAsc(AttendanceMonth::getEngineerId);
        if (engineerIds != null) {
            monthQuery.in(AttendanceMonth::getEngineerId, engineerIds);
        }
        if (legalEntityIds != null) {
            monthQuery.in(AttendanceMonth::getLegalEntityId, legalEntityIds);
        }
        if (engineerIds != null || legalEntityIds != null) {
            monthQuery.isNotNull(AttendanceMonth::getLegalEntityId)
                    .isNotNull(AttendanceMonth::getOrganizationId);
        }

        // 摘要は SQL 段階でページング。日次行は一覧のために物化しない。
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AttendanceMonth> pageReq =
                com.ses.common.util.PageUtils.safePage(
                        current == null ? 1L : current,
                        size == null ? 50L : size,
                        50L,
                        100L);
        com.baomidou.mybatisplus.core.metadata.IPage<AttendanceMonth> pageResult =
                attendanceMonthMapper.selectPage(pageReq, monthQuery);
        if (pageResult == null || pageResult.getRecords() == null || pageResult.getRecords().isEmpty()) {
            return overview(target, List.of(),
                    pageResult == null ? 0 : pageResult.getTotal(),
                    pageReq.getCurrent(), pageReq.getSize());
        }

        List<AttendanceMonth> months = pageResult.getRecords();
        Set<Long> ids = months.stream().map(AttendanceMonth::getEngineerId).collect(Collectors.toSet());
        List<Engineer> engineers = engineerMapper.selectBatchIds(ids).stream()
                .sorted(Comparator.comparing(Engineer::getId)).toList();
        Map<Long, String> names = engineers.stream().collect(Collectors.toMap(Engineer::getId,
                e -> e.getFullName() == null ? "" : e.getFullName()));

        Map<Long, List<EmployeeAttendance>> daysByEngineer = Map.of();
        if (includeDays) {
            LambdaQueryWrapper<EmployeeAttendance> dayQuery = new LambdaQueryWrapper<EmployeeAttendance>()
                    .ge(EmployeeAttendance::getWorkDate, target.atDay(1))
                    .le(EmployeeAttendance::getWorkDate, target.atEndOfMonth())
                    .orderByAsc(EmployeeAttendance::getWorkDate);
            dayQuery.in(EmployeeAttendance::getEngineerId, ids);
            daysByEngineer = employeeAttendanceMapper.selectList(dayQuery)
                    .stream().collect(Collectors.groupingBy(EmployeeAttendance::getEngineerId));
        }

        Map<Long, List<EmployeeAttendance>> daysMap = daysByEngineer;
        List<AttendanceMonthDto> result = months.stream().map(month -> toMonthDto(month, names.get(month.getEngineerId()),
                includeDays ? daysMap.getOrDefault(month.getEngineerId(), List.of()) : List.of())).toList();
        return overview(target, result, pageResult.getTotal(), pageResult.getCurrent(), pageResult.getSize());
    }

    private AttendanceOverviewDto overview(YearMonth target, List<AttendanceMonthDto> months,
                                           long total, long current, long size) {
        AttendanceOverviewDto dto = new AttendanceOverviewDto();
        dto.setMonth(target.toString());
        dto.setMonths(months);
        dto.setTotal(total);
        dto.setCurrent(current);
        dto.setSize(size);
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
        dto.setBreaks(loadBreakOffsets(day.getId()).stream().map(interval -> {
            AttendanceBreakDto breakDto = new AttendanceBreakDto();
            breakDto.setStartTime(offsetToTime(day.getClockIn(), interval.startOffsetMinutes()));
            breakDto.setEndTime(offsetToTime(day.getClockIn(), interval.endOffsetMinutes()));
            return breakDto;
        }).toList());
        dto.setWorkedMinutes(sum(day.getRegularMinutes(), day.getOvertimeMinutes(), day.getHolidayMinutes()));
        dto.setWorkType(day.getWorkType());
        dto.setWorkplaceType(day.getWorkplaceType());
        dto.setStatus(day.getStatus());
        dto.setRemarks(day.getRemarks());
        dto.setVersion(day.getVersion());
        return dto;
    }

    private EmployeeAttendance toAttendance(AttendanceDayRequest request, Long engineerId,
                                             AttendanceScopeSnapshot scope,
                                             List<AttendanceCalculator.BreakInterval> intervals) {
        int priorWeekMinutes = priorWeekMinutes(engineerId, request.getWorkDate());
        AttendanceCalculation calculation = attendanceCalculator.calculate(
                request.getWorkDate(), engineerId, scope.legalEntityId(), scope.organizationId(),
                request.getClockIn(), request.getClockOut(), intervals, priorWeekMinutes);
        int breakMinutes = intervals.stream().mapToInt(interval -> interval.endOffsetMinutes()
                - interval.startOffsetMinutes()).sum();
        return EmployeeAttendance.builder()
                .engineerId(engineerId)
                .legalEntityId(scope.legalEntityId())
                .organizationId(scope.organizationId())
                .workCalendarId(calculation.workCalendarId())
                .workDate(request.getWorkDate())
                .clockIn(request.getClockIn())
                .clockOut(request.getClockOut())
                .breakMinutes(breakMinutes)
                .regularMinutes(calculation.regularMinutes())
                .overtimeMinutes(calculation.overtimeMinutes())
                .holidayMinutes(calculation.holidayMinutes())
                .lateNightMinutes(calculation.lateNightMinutes())
                .workType(calculation.workType())
                .workplaceType(request.getWorkplaceType())
                .source("manual")
                .status(INPUT)
                .remarks(request.getRemarks())
                .version(0)
                .build();
    }

    /**
     * 入力された休憩区間（時刻）を勤務開始基準のoffsetへ変換する（方式A）。
     * 退勤時刻を跨ぐ区間は跨夜休憩として翌日側へ繰り上げ、日付を曖昧にしない。
     */
    private List<AttendanceCalculator.BreakInterval> toBreakIntervals(AttendanceDayRequest request) {
        List<AttendanceBreakRequest> input = request.getBreaks();
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        if (request.getClockIn() == null) {
            throw BusinessException.of(400, "error.attendance.invalidTime");
        }
        int clockInMinute = request.getClockIn().getHour() * 60 + request.getClockIn().getMinute();
        List<AttendanceCalculator.BreakInterval> result = new ArrayList<>();
        for (AttendanceBreakRequest breakRequest : input) {
            if (breakRequest == null || breakRequest.getStartTime() == null
                    || breakRequest.getEndTime() == null) {
                throw BusinessException.of(400, "error.attendance.breakInvalid");
            }
            if (breakRequest.getStartTime().equals(breakRequest.getEndTime())) {
                throw BusinessException.of(400, "error.attendance.breakInvalid");
            }
            int startOffset = Math.floorMod(
                    breakRequest.getStartTime().getHour() * 60 + breakRequest.getStartTime().getMinute()
                            - clockInMinute, 24 * 60);
            int endOffset = Math.floorMod(
                    breakRequest.getEndTime().getHour() * 60 + breakRequest.getEndTime().getMinute()
                            - clockInMinute, 24 * 60);
            if (endOffset <= startOffset) {
                endOffset += 24 * 60;
            }
            result.add(new AttendanceCalculator.BreakInterval(startOffset, endOffset));
        }
        return result;
    }

    /**
     * breakMinutesは保存済み休憩区間の合計から導出する（design §5.1.1）。
     * 入力値が指定された場合は区間合計と一致しなければ400で拒否する（R3-P2-01確定）。
     */
    private void assertBreakMinutesMatch(Integer requested, List<AttendanceCalculator.BreakInterval> intervals) {
        if (requested == null) {
            return;
        }
        int sum = intervals.stream().mapToInt(interval -> interval.endOffsetMinutes()
                - interval.startOffsetMinutes()).sum();
        if (!requested.equals(sum)) {
            throw BusinessException.of(400, "error.attendance.breakMinutesMismatch");
        }
    }

    private void replaceBreaks(Long attendanceId, List<AttendanceCalculator.BreakInterval> intervals) {
        employeeAttendanceBreakMapper.delete(new LambdaQueryWrapper<EmployeeAttendanceBreak>()
                .eq(EmployeeAttendanceBreak::getAttendanceId, attendanceId));
        int sequenceNo = 1;
        for (AttendanceCalculator.BreakInterval interval : intervals) {
            employeeAttendanceBreakMapper.insert(EmployeeAttendanceBreak.builder()
                    .attendanceId(attendanceId)
                    .sequenceNo(sequenceNo++)
                    .startOffsetMinutes(interval.startOffsetMinutes())
                    .endOffsetMinutes(interval.endOffsetMinutes())
                    .build());
        }
    }

    private List<AttendanceCalculator.BreakInterval> loadBreakOffsets(Long attendanceId) {
        if (attendanceId == null) {
            return List.of();
        }
        return employeeAttendanceBreakMapper.selectList(new LambdaQueryWrapper<EmployeeAttendanceBreak>()
                        .eq(EmployeeAttendanceBreak::getAttendanceId, attendanceId)
                        .orderByAsc(EmployeeAttendanceBreak::getSequenceNo))
                .stream()
                .map(breakRow -> new AttendanceCalculator.BreakInterval(
                        value(breakRow.getStartOffsetMinutes()), value(breakRow.getEndOffsetMinutes())))
                .toList();
    }

    private LocalTime offsetToTime(LocalTime clockIn, int offsetMinutes) {
        if (clockIn == null) {
            return null;
        }
        int minuteOfDay = (clockIn.getHour() * 60 + clockIn.getMinute() + offsetMinutes) % (24 * 60);
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
    }

    private void refreshAggregate(AttendanceMonth month) {
        List<EmployeeAttendance> days = employeeAttendanceMapper.selectList(
                new LambdaQueryWrapper<EmployeeAttendance>()
                        .eq(EmployeeAttendance::getEngineerId, month.getEngineerId())
                        .ge(EmployeeAttendance::getWorkDate, month.getWorkMonth())
                        .le(EmployeeAttendance::getWorkDate, YearMonth.from(month.getWorkMonth()).atEndOfMonth()));
        assertNoUnknownBreakDays(days);
        int regular = days.stream().mapToInt(d -> value(d.getRegularMinutes())).sum();
        int overtime = days.stream().mapToInt(d -> value(d.getOvertimeMinutes())).sum();
        int holiday = days.stream().mapToInt(d -> value(d.getHolidayMinutes())).sum();
        int lateNight = days.stream().mapToInt(d -> value(d.getLateNightMinutes())).sum();
        int scheduled = days.stream().mapToInt(this::scheduledMinutes).sum();
        int worked = regular + overtime + holiday;
        int version = value(month.getVersion());
        int updated = attendanceMonthMapper.update(null, new LambdaUpdateWrapper<AttendanceMonth>()
                .set(AttendanceMonth::getScheduledMinutes, scheduled)
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

    private AttendanceMonth lockOrCreateMonth(Long engineerId, YearMonth target,
                                              AttendanceScopeSnapshot scope) {
        AttendanceMonth existing = findMonthForUpdate(engineerId, target);
        if (existing != null) {
            if (existing.getLegalEntityId() == null || existing.getOrganizationId() == null) {
                throw BusinessException.of(404, "error.attendance.scopeUnknown");
            }
            return existing;
        }
        Engineer engineer = engineerMapper.selectById(engineerId);
        if (engineer == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        AttendanceMonth created = AttendanceMonth.builder()
                .engineerId(engineerId)
                .legalEntityId(scope.legalEntityId())
                .organizationId(scope.organizationId())
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
        if (month.getLegalEntityId() == null || month.getOrganizationId() == null) {
            throw BusinessException.of(404, "error.attendance.scopeUnknown");
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
        if ("管理者".equals(SecurityUtils.currentRole())) {
            return engineerId;
        }
        // managerのasOf判定は、対象月末の組織所属へ固定する。
        if (!organizationScopeService.hasFullAccess()
                && !organizationScopeService.allowedEngineerIds(target.atEndOfMonth()).contains(engineerId)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return engineerId;
    }

    private void assertHrMonthSnapshotAllowed(AttendanceMonth month, YearMonth target) {
        if (!"HR".equals(SecurityUtils.currentRole())) return;
        if (month.getLegalEntityId() == null
                || !attendanceScopeResolver.allowedHrLegalEntityIds(SecurityUtils.currentUserId(),
                target.atEndOfMonth()).contains(month.getLegalEntityId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
    }

    private void assertEditable(AttendanceMonth month) {
        if (!INPUT.equals(month.getStatus()) && !RETURNED.equals(month.getStatus())) {
            throw BusinessException.of(400, "error.attendance.closedEdit");
        }
    }

    /**
     * 休憩区間を持たないのにbreakMinutes > 0の既存行（区間不明）がある月は、
     * 補正・承認が完了するまで月次再確定を拒否する（design §5.1.1 / R1.1）。
     * 既存データから架空の休憩時刻を生成しない。
     */
    private void assertNoUnknownBreakDays(List<EmployeeAttendance> days) {
        for (EmployeeAttendance day : days) {
            if (value(day.getBreakMinutes()) > 0 && loadBreakOffsets(day.getId()).isEmpty()) {
                throw BusinessException.of(400, "error.attendance.breakUnknown");
            }
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

    private int priorWeekMinutes(Long engineerId, LocalDate workDate) {
        LocalDate weekStart = workDate.minusDays(workDate.getDayOfWeek().getValue() - 1L);
        LocalDate previousDay = workDate.minusDays(1);
        if (previousDay.isBefore(weekStart)) return 0;
        return employeeAttendanceMapper.selectList(new LambdaQueryWrapper<EmployeeAttendance>()
                        .eq(EmployeeAttendance::getEngineerId, engineerId)
                        .ge(EmployeeAttendance::getWorkDate, weekStart)
                        .le(EmployeeAttendance::getWorkDate, previousDay))
                .stream()
                .mapToInt(day -> value(day.getRegularMinutes()) + value(day.getOvertimeMinutes()))
                .sum();
    }

    private int scheduledMinutes(EmployeeAttendance day) {
        if (day.getWorkCalendarId() == null || day.getWorkDate() == null) return 0;
        com.ses.entity.WorkCalendarDay calendarDay = workCalendarDayMapper.selectOne(
                new LambdaQueryWrapper<com.ses.entity.WorkCalendarDay>()
                        .eq(com.ses.entity.WorkCalendarDay::getCalendarId, day.getWorkCalendarId())
                        .eq(com.ses.entity.WorkCalendarDay::getCalendarDate, day.getWorkDate())
                        .last("LIMIT 1"));
        return calendarDay == null || calendarDay.getScheduledMinutes() == null
                ? 0 : calendarDay.getScheduledMinutes();
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
