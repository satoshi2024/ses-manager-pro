package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.leave.LeaveApplyRequest;
import com.ses.dto.leave.LeaveApplicationResult;
import com.ses.dto.leave.LeaveBalanceDto;
import com.ses.dto.leave.LeaveDto;
import com.ses.dto.leave.LeaveGrantRequest;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.Engineer;
import com.ses.entity.LeaveLedger;
import com.ses.entity.LeaveRequest;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LeaveLedgerMapper;
import com.ses.mapper.LeaveRequestMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.LeaveService;
import com.ses.service.NotificationService;
import com.ses.service.SystemConfigService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalRequestCommand;
import com.ses.service.attendance.AttendanceCalculator;
import com.ses.service.attendance.AttendanceScopeResolver;
import com.ses.service.attendance.AttendanceScopeSnapshot;
import com.ses.service.leave.LeaveApprovalAdapter;
import com.ses.service.leave.LeaveMinutesCalculator;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 休暇申請・承認統合（T071/A2）。申請・分計算・期間重複・残数両モード・取消・付与・照会を担当する。
 * 営業は休暇scopeを持たず、客先報告が必要な休暇の通知だけを受ける（design §5.3）。
 */
@Service
@RequiredArgsConstructor
public class LeaveServiceImpl implements LeaveService {

    static final String APPLIED = "申請中";
    static final String RETURNED = "差戻し";
    static final String APPROVED = "承認済";
    static final String CANCELLED = "取消済";
    static final String CLOSED = "締め済";
    static final Set<String> LEAVE_TYPES = Set.of("有給", "半休", "時間休", "代休", "欠勤", "特別休暇");
    private static final Set<String> TERMINAL_APPROVAL = Set.of("rejected", "withdrawn");

    private final LeaveRequestMapper leaveRequestMapper;
    private final LeaveLedgerMapper leaveLedgerMapper;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final AttendanceMonthMapper attendanceMonthMapper;
    private final EngineerMapper engineerMapper;
    private final EngineerAccountLinkService engineerAccountLinkService;
    private final AttendanceScopeResolver attendanceScopeResolver;
    private final AttendanceCalculator attendanceCalculator;
    private final OrganizationScopeService organizationScopeService;
    private final ApprovalEngineService approvalEngineService;
    private final SystemConfigService systemConfigService;

    @Override
    @Transactional
    public LeaveApplicationResult apply(LeaveApplyRequest request) {
        validate(request);
        Long engineerId = currentEngineerId();
        AttendanceScopeSnapshot scope = attendanceScopeResolver.requireSnapshot(
                engineerId, SecurityUtils.currentUserId(), request.getStartDate());
        LeaveRequest leave = LeaveRequest.builder()
                .engineerId(engineerId)
                .legalEntityId(scope.legalEntityId())
                .organizationId(scope.organizationId())
                .leaveType(request.getLeaveType())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .reason(request.getReason())
                .status(APPLIED)
                .version(0)
                .createdBy(SecurityUtils.currentUserId())
                .build();
        leave.setRequestedMinutes(totalMinutes(leave));
        if (leave.getRequestedMinutes() <= 0) {
            throw BusinessException.of(400, "error.leave.zeroMinutes");
        }
        assertNoClosedMonth(engineerId, leave);
        assertNoOverlap(engineerId, leave);
        assertBalance(engineerId, leave.getLeaveType(), leave.getRequestedMinutes());

        leaveRequestMapper.insert(leave);
        ApprovalRequest approval = approvalEngineService.request(new ApprovalRequestCommand(
                LeaveApprovalAdapter.REQUEST_TYPE,
                "LEAVE_REQUEST", leave.getId(), (long) value(leave.getVersion()),
                SecurityUtils.currentUserId(), leave.getOrganizationId(), null,
                Map.of("leaveType", leave.getLeaveType(),
                        "startDate", leave.getStartDate().toString(),
                        "endDate", leave.getEndDate().toString(),
                        "requestedMinutes", leave.getRequestedMinutes(),
                        "reason", leave.getReason() == null ? "" : leave.getReason()),
                Map.of("beforeStatus", APPLIED, "afterStatus", APPROVED),
                "leave-request:" + leave.getId() + ":" + value(leave.getVersion())));
        if (approval != null) {
            leaveRequestMapper.update(null, new LambdaUpdateWrapper<LeaveRequest>()
                    .set(LeaveRequest::getApprovalRequestId, approval.getId())
                    .eq(LeaveRequest::getId, leave.getId()));
        }
        return new LeaveApplicationResult(leave.getId(), approval == null ? null : approval.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaveDto> mine() {
        Long engineerId = currentEngineerId();
        return toDtos(leaveRequestMapper.selectList(new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getEngineerId, engineerId)
                .orderByDesc(LeaveRequest::getStartDate)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaveDto> management(String month) {
        requireManagementRole();
        YearMonth target = parseMonth(month);
        LambdaQueryWrapper<LeaveRequest> query = new LambdaQueryWrapper<LeaveRequest>()
                .le(LeaveRequest::getStartDate, target.atEndOfMonth())
                .ge(LeaveRequest::getEndDate, target.atDay(1))
                .orderByDesc(LeaveRequest::getStartDate);
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            // 全件
        } else if ("HR".equals(role)) {
            Set<Long> legalEntities = attendanceScopeResolver.allowedHrLegalEntityIds(
                    SecurityUtils.currentUserId(), target.atEndOfMonth());
            if (legalEntities.isEmpty()) {
                return List.of();
            }
            query.in(LeaveRequest::getLegalEntityId, legalEntities);
        } else {
            Set<Long> allowed = organizationScopeService.hasFullAccess()
                    ? null : organizationScopeService.allowedEngineerIds(target.atEndOfMonth());
            if (allowed != null && allowed.isEmpty()) {
                return List.of();
            }
            if (allowed != null) {
                query.in(LeaveRequest::getEngineerId, allowed);
            }
        }
        return toDtos(leaveRequestMapper.selectList(query));
    }

    @Override
    @Transactional
    public void cancel(Long leaveId, String reason) {
        LeaveRequest leave = requireLeave(leaveId);
        if (!leave.getEngineerId().equals(currentEngineerId()) && !"管理者".equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "error.leave.roleDenied");
        }
        if (!APPROVED.equals(leave.getStatus())) {
            throw BusinessException.of(400, "error.leave.notApproved");
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw BusinessException.of(400, "error.leave.reasonRequired");
        }
        approvalEngineService.request(new ApprovalRequestCommand(
                LeaveApprovalAdapter.CANCEL_REQUEST_TYPE,
                "LEAVE_REQUEST", leave.getId(), (long) value(leave.getVersion()),
                SecurityUtils.currentUserId(), leave.getOrganizationId(), null,
                Map.of("reason", reason.trim(), "leaveId", leave.getId()),
                Map.of("beforeStatus", APPROVED, "afterStatus", CANCELLED),
                "leave-cancel:" + leave.getId() + ":" + value(leave.getVersion())));
    }

    /** 差戻し（approval status=returned）からの再提出。engineのresubmitへ委譲する（R4-P2-01）。 */
    @Override
    @Transactional
    public void resubmit(Long leaveId) {
        LeaveRequest leave = requireLeave(leaveId);
        if (!leave.getEngineerId().equals(currentEngineerId())) {
            throw BusinessException.of(403, "error.leave.roleDenied");
        }
        if (leave.getApprovalRequestId() == null) {
            throw BusinessException.of(400, "error.leave.notReturned");
        }
        ApprovalRequest approval = approvalRequestMapper.selectById(leave.getApprovalRequestId());
        if (approval == null || !"returned".equals(approval.getStatus())) {
            throw BusinessException.of(400, "error.leave.notReturned");
        }
        approvalEngineService.resubmit(approval.getId(), SecurityUtils.currentUserId(),
                Map.of("leaveType", leave.getLeaveType(),
                        "startDate", leave.getStartDate().toString(),
                        "endDate", leave.getEndDate().toString(),
                        "requestedMinutes", leave.getRequestedMinutes(),
                        "reason", leave.getReason() == null ? "" : leave.getReason()),
                Map.of("beforeStatus", leave.getStatus(), "afterStatus", APPROVED), null);
    }

    @Override
    @Transactional
    public LeaveLedger grant(LeaveGrantRequest request) {
        String role = SecurityUtils.currentRole();
        if (!Set.of("管理者", "HR").contains(role)) {
            throw BusinessException.of(403, "error.leave.roleDenied");
        }
        if (request == null || request.getEngineerId() == null
                || request.getLeaveType() == null || !LEAVE_TYPES.contains(request.getLeaveType())) {
            throw BusinessException.of(400, "error.leave.invalidType");
        }
        if (request.getAmountMinutes() == null || request.getAmountMinutes() <= 0) {
            throw BusinessException.of(400, "error.leave.grantInvalid");
        }
        if (request.getEntryDate() == null) {
            throw BusinessException.of(400, "error.leave.invalidPeriod");
        }
        Engineer engineer = engineerMapper.selectById(request.getEngineerId());
        if (engineer == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // NOTE-R4-02: 本システム正（internal）以外では台帳が正でないため付与を拒否する。
        String mode = systemConfigService.getString("leave.balance.source", null);
        if (!"internal".equals(mode)) {
            throw BusinessException.of(400, "error.leave.grantExternalDisabled");
        }
        // R4-P1-01: HRは担当法人内の要員のみ付与できる（管理者は全件）。
        // 法人は付与日の要員snapshotを正とし、台帳行へも同じsnapshotを保存する。
        AttendanceScopeSnapshot snapshot = attendanceScopeResolver.requireSnapshot(
                request.getEngineerId(), null, request.getEntryDate());
        if ("HR".equals(role)) {
            Set<Long> allowed = attendanceScopeResolver.allowedHrLegalEntityIds(
                    SecurityUtils.currentUserId(), request.getEntryDate());
            if (allowed == null || !allowed.contains(snapshot.legalEntityId())) {
                throw BusinessException.of(404, "error.scope.notFound");
            }
        }
        LeaveLedger row = LeaveLedger.builder()
                .engineerId(request.getEngineerId())
                .legalEntityId(snapshot.legalEntityId())
                .leaveType(request.getLeaveType())
                .ledgerType("GRANT")
                .amountMinutes(request.getAmountMinutes())
                .entryDate(request.getEntryDate())
                .source("manual")
                .remarks(request.getRemarks())
                .version(0)
                .build();
        leaveLedgerMapper.insert(row);
        return row;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaveBalanceDto> balance(Long engineerId) {
        assertBalanceViewable(engineerId);
        String mode = systemConfigService.getString("leave.balance.source", null);
        if (mode == null || mode.isBlank()) {
            throw BusinessException.of(400, "error.leave.balanceUnknown");
        }
        List<LeaveBalanceDto> result = new ArrayList<>();
        String types = systemConfigService.getString("leave.balance.types", "有給,半休,時間休,代休,特別休暇");
        List<String> typeList = types == null ? List.<String>of() : Arrays.asList(types.split(","));
        for (String type : typeList) {
            LeaveBalanceDto dto = new LeaveBalanceDto();
            dto.setLeaveType(type);
            if ("internal".equals(mode)) {
                dto.setMode("internal");
                dto.setBalanceMinutes(balanceMinutes(engineerId, type));
            } else {
                dto.setMode("external");
                dto.setBalanceMinutes(null);
            }
            result.add(dto);
        }
        return result;
    }

    /**
     * R4-P1-01: 残数照会の母集団を主体別にSQL境界で制限する。
     * 管理者=全件、HR=担当法人内（asOf今日）、マネージャー=組織scope（hasFullAccess先判定）。
     * 所属不明・履歴ありNULLはfail-closedで404。
     */
    private void assertBalanceViewable(Long engineerId) {
        if (engineerId == null) {
            throw BusinessException.of(400, "error.leave.engineerRequired");
        }
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return;
        }
        if (engineerMapper.selectById(engineerId) == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        LocalDate today = LocalDate.now();
        if ("HR".equals(role)) {
            Set<Long> allowed = attendanceScopeResolver.allowedHrLegalEntityIds(
                    SecurityUtils.currentUserId(), today);
            AttendanceScopeSnapshot snapshot = attendanceScopeResolver.resolveSnapshot(
                    engineerId, null, today);
            if (snapshot == null || allowed == null || !allowed.contains(snapshot.legalEntityId())) {
                throw BusinessException.of(404, "error.scope.notFound");
            }
            return;
        }
        if ("マネージャー".equals(role)) {
            if (organizationScopeService.hasFullAccess()) {
                return;
            }
            if (!organizationScopeService.allowedEngineerIds(today).contains(engineerId)) {
                throw BusinessException.of(404, "error.scope.notFound");
            }
            return;
        }
        throw BusinessException.of(403, "error.leave.roleDenied");
    }

    private void validate(LeaveApplyRequest request) {
        if (request == null || request.getStartDate() == null || request.getEndDate() == null
                || request.getLeaveType() == null || !LEAVE_TYPES.contains(request.getLeaveType())) {
            throw BusinessException.of(400, "error.leave.invalidType");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw BusinessException.of(400, "error.leave.invalidPeriod");
        }
        String allocation = LeaveMinutesCalculator.allocationType(request.getLeaveType());
        if (!"full".equals(allocation) && !request.getStartDate().equals(request.getEndDate())) {
            throw BusinessException.of(400, "error.leave.invalidPeriod");
        }
        if ("時間休".equals(request.getLeaveType())
                && (request.getStartTime() == null || request.getEndTime() == null
                || !request.getEndTime().isAfter(request.getStartTime()))) {
            throw BusinessException.of(400, "error.leave.invalidTime");
        }
        if (request.getReason() != null && request.getReason().trim().length() > 500) {
            throw BusinessException.of(400, "error.leave.reasonRequired");
        }
    }

    private int totalMinutes(LeaveRequest leave) {
        return LeaveMinutesCalculator.dayMinutes(leave, attendanceCalculator).values().stream()
                .mapToInt(Integer::intValue).sum();
    }

    /** 休暇期間が締め済み月と重なる場合は拒否する（R1.4 / design §5.1）。 */
    private void assertNoClosedMonth(Long engineerId, LeaveRequest leave) {
        List<AttendanceMonth> months = attendanceMonthMapper.selectList(new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getEngineerId, engineerId)
                .ge(AttendanceMonth::getWorkMonth, leave.getStartDate().withDayOfMonth(1))
                .le(AttendanceMonth::getWorkMonth, leave.getEndDate().withDayOfMonth(1)));
        for (AttendanceMonth month : months) {
            if (CLOSED.equals(month.getStatus())) {
                throw BusinessException.of(400, "error.leave.closedMonth");
            }
        }
    }

    /**
     * 期間重複の拒否。却下・取下げ済みの承認requestは占有と見なさない。
     * 申請前に要員行をFOR UPDATEでロックし、並行する重複「申請中」insertを防ぐ（S11-P2-01）。
     */
    private void assertNoOverlap(Long engineerId, LeaveRequest leave) {
        Engineer locked = engineerMapper.selectByIdForUpdate(engineerId);
        if (locked == null) {
            throw BusinessException.of(404, "error.leave.notFound");
        }
        List<LeaveRequest> candidates = leaveRequestMapper.selectList(new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getEngineerId, engineerId)
                .in(LeaveRequest::getStatus, List.of(APPLIED, RETURNED, APPROVED))
                .le(LeaveRequest::getStartDate, leave.getEndDate())
                .ge(LeaveRequest::getEndDate, leave.getStartDate())
                .last("FOR UPDATE"));
        if (candidates.isEmpty()) {
            return;
        }
        Map<Long, ApprovalRequest> approvals = approvalRequestMapper.selectBatchIds(
                        candidates.stream().map(LeaveRequest::getApprovalRequestId)
                                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ApprovalRequest::getId, a -> a));
        Set<LocalDate> mineDays = daysOf(leave);
        for (LeaveRequest candidate : candidates) {
            ApprovalRequest approval = candidate.getApprovalRequestId() == null
                    ? null : approvals.get(candidate.getApprovalRequestId());
            if (approval != null && TERMINAL_APPROVAL.contains(approval.getStatus())) {
                continue;
            }
            Set<LocalDate> candidateDays = daysOf(candidate);
            for (LocalDate day : candidateDays) {
                if (mineDays.contains(day) && overlapsOnDay(leave, candidate)) {
                    throw BusinessException.of(400, "error.leave.overlap");
                }
            }
        }
    }

    private boolean overlapsOnDay(LeaveRequest left, LeaveRequest right) {
        if ("時間休".equals(left.getLeaveType()) && "時間休".equals(right.getLeaveType())) {
            return left.getStartTime().isBefore(right.getEndTime())
                    && right.getStartTime().isBefore(left.getEndTime());
        }
        return true;
    }

    private Set<LocalDate> daysOf(LeaveRequest leave) {
        Set<LocalDate> days = new LinkedHashSet<>();
        for (LocalDate date = leave.getStartDate(); !date.isAfter(leave.getEndDate()); date = date.plusDays(1)) {
            days.add(date);
        }
        return days;
    }

    private void assertBalance(Long engineerId, String leaveType, int requestedMinutes) {
        String mode = systemConfigService.getString("leave.balance.source", null);
        if (mode == null || mode.isBlank()) {
            throw BusinessException.of(400, "error.leave.balanceUnknown");
        }
        if (!"internal".equals(mode)) {
            return;
        }
        String types = systemConfigService.getString("leave.balance.types", "有給,半休,時間休,代休,特別休暇");
        if (types == null || !Arrays.asList(types.split(",")).contains(leaveType)) {
            return;
        }
        int balance = balanceMinutes(engineerId, leaveType);
        if (requestedMinutes > balance) {
            throw BusinessException.of(400, "error.leave.balanceInsufficient", balance, requestedMinutes);
        }
    }

    private int balanceMinutes(Long engineerId, String leaveType) {
        return leaveLedgerMapper.selectList(new LambdaQueryWrapper<LeaveLedger>()
                        .eq(LeaveLedger::getEngineerId, engineerId)
                        .eq(LeaveLedger::getLeaveType, leaveType))
                .stream()
                .mapToInt(row -> "GRANT".equals(row.getLedgerType())
                        ? value(row.getAmountMinutes()) : -value(row.getAmountMinutes()))
                .sum();
    }

    private List<LeaveDto> toDtos(List<LeaveRequest> leaves) {
        Map<Long, String> names = engineerMapper.selectBatchIds(leaves.stream()
                        .map(LeaveRequest::getEngineerId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Engineer::getId,
                        e -> e.getFullName() == null ? "" : e.getFullName()));
        Map<Long, ApprovalRequest> approvals = approvalRequestMapper.selectBatchIds(leaves.stream()
                        .map(LeaveRequest::getApprovalRequestId)
                        .filter(java.util.Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ApprovalRequest::getId, a -> a));
        return leaves.stream().sorted(Comparator.comparing(LeaveRequest::getStartDate).reversed())
                .map(leave -> {
                    LeaveDto dto = new LeaveDto();
                    dto.setId(leave.getId());
                    dto.setEngineerId(leave.getEngineerId());
                    dto.setEngineerName(names.getOrDefault(leave.getEngineerId(), ""));
                    dto.setLeaveType(leave.getLeaveType());
                    dto.setStartDate(leave.getStartDate());
                    dto.setEndDate(leave.getEndDate());
                    dto.setStartTime(leave.getStartTime());
                    dto.setEndTime(leave.getEndTime());
                    dto.setRequestedMinutes(leave.getRequestedMinutes());
                    dto.setReason(leave.getReason());
                    dto.setStatus(leave.getStatus());
                    dto.setApprovalRequestId(leave.getApprovalRequestId());
                    dto.setVersion(leave.getVersion());
                    ApprovalRequest approval = leave.getApprovalRequestId() == null
                            ? null : approvals.get(leave.getApprovalRequestId());
                    dto.setApprovalStatus(approval == null ? null : approval.getStatus());
                    return dto;
                }).toList();
    }

    private LeaveRequest requireLeave(Long leaveId) {
        LeaveRequest leave = leaveId == null ? null : leaveRequestMapper.selectById(leaveId);
        if (leave == null) {
            throw BusinessException.of(404, "error.leave.notFound");
        }
        return leave;
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

    private YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException e) {
            throw BusinessException.of(400, "error.attendance.invalidMonth");
        }
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
