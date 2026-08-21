package com.ses.service.leave;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.Engineer;
import com.ses.entity.LeaveLedger;
import com.ses.entity.LeaveRequest;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LeaveLedgerMapper;
import com.ses.mapper.LeaveRequestMapper;
import com.ses.service.NotificationService;
import com.ses.service.EngineerSalesService;
import com.ses.service.SystemConfigService;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import com.ses.service.attendance.AttendanceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 休暇申請/取消を既存approval engineへ接続するadapter（T071）。
 * 最終承認時だけ、状態CAS・残数CAS・月次leave_minutes反映・営業通知を同一transactionで実行する。
 */
@Component
@RequiredArgsConstructor
public class LeaveApprovalAdapter implements ApprovalTargetAdapter {

    public static final String REQUEST_TYPE = "leave.request";
    public static final String CANCEL_REQUEST_TYPE = "leave.cancel";

    private static final String APPLIED = "申請中";
    private static final String RETURNED = "差戻し";
    private static final String APPROVED = "承認済";
    private static final String CANCELLED = "取消済";
    private static final String CLOSED = "締め済";

    private final LeaveRequestMapper leaveRequestMapper;
    private final LeaveLedgerMapper leaveLedgerMapper;
    private final AttendanceMonthMapper attendanceMonthMapper;
    private final EngineerMapper engineerMapper;
    private final EngineerSalesService engineerSalesService;
    private final NotificationService notificationService;
    private final SystemConfigService systemConfigService;
    private final AttendanceCalculator attendanceCalculator;

    @Override
    public String requestType() {
        return REQUEST_TYPE;
    }

    @Override
    public Set<String> supportedRequestTypes() {
        return Set.of(REQUEST_TYPE, CANCEL_REQUEST_TYPE);
    }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        LeaveRequest leave = leave(targetId);
        String requestType = command == null ? null : String.valueOf(command.get("requestType"));
        if (CANCEL_REQUEST_TYPE.equals(requestType)) {
            if (!APPROVED.equals(leave.getStatus())) {
                throw BusinessException.of(400, "error.leave.notApproved");
            }
        } else if (!APPLIED.equals(leave.getStatus()) && !RETURNED.equals(leave.getStatus())) {
            throw BusinessException.of(400, "error.leave.invalidTransition", leave.getStatus(), APPROVED);
        }
        return new ApprovalSnapshot(version(leave), null, leave.getOrganizationId(),
                command == null ? Map.of() : Map.copyOf(command),
                Map.of("leaveType", leave.getLeaveType(), "requestedMinutes", leave.getRequestedMinutes()));
    }

    @Override
    public long currentVersion(Long targetId) {
        return version(leave(targetId));
    }

    @Override
    public void validateBeforeRequest(ApprovalSnapshot snapshot) {
        if (snapshot == null || snapshot.targetVersion() == null) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    @Override
    public void applyApproved(ApprovalRequest request) {
        if (request == null || request.getTargetId() == null || request.getTargetVersion() == null) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
        LeaveRequest leave = leave(request.getTargetId());
        requireVersion(request, leave);
        if (CANCEL_REQUEST_TYPE.equals(request.getRequestType())) {
            applyCancel(leave);
        } else {
            applyRequestApproval(leave);
        }
    }

    private void applyRequestApproval(LeaveRequest leave) {
        if (!APPLIED.equals(leave.getStatus()) && !RETURNED.equals(leave.getStatus())) {
            throw BusinessException.of(400, "error.leave.invalidTransition", leave.getStatus(), APPROVED);
        }
        assertBalanceAtApproval(leave);
        casStatus(leave, APPROVED);
        insertConsume(leave);
        applyMonths(leave, true);
        notifySalesRep(leave);
    }

    private void applyCancel(LeaveRequest leave) {
        if (!APPROVED.equals(leave.getStatus())) {
            throw BusinessException.of(400, "error.leave.notApproved");
        }
        casStatus(leave, CANCELLED);
        leaveLedgerMapper.delete(new LambdaQueryWrapper<LeaveLedger>()
                .eq(LeaveLedger::getLeaveRequestId, leave.getId()));
        applyMonths(leave, false);
    }

    /**
     * 承認時点でも残数を再検証する（申請〜承認の間に残数が減った場合をfail-closedにする）。
     * engineer×leaveTypeの台帳行をFOR UPDATEでロックし、unlocked check-then-insertを禁止する（S11-P1-01）。
     */
    private void assertBalanceAtApproval(LeaveRequest leave) {
        String mode = systemConfigService.getString("leave.balance.source", null);
        if (mode == null || mode.isBlank()) {
            throw BusinessException.of(400, "error.leave.balanceUnknown");
        }
        if (!"internal".equals(mode)) {
            return;
        }
        if (!isBalanceManaged(leave.getLeaveType())) {
            return;
        }
        int balance = lockAndBalanceMinutes(leave.getEngineerId(), leave.getLeaveType());
        if (leave.getRequestedMinutes() == null || leave.getRequestedMinutes() > balance) {
            throw BusinessException.of(400, "error.leave.balanceInsufficient", balance, leave.getRequestedMinutes());
        }
    }

    private void insertConsume(LeaveRequest leave) {
        leaveLedgerMapper.insert(LeaveLedger.builder()
                .engineerId(leave.getEngineerId())
                .legalEntityId(leave.getLegalEntityId())
                .leaveType(leave.getLeaveType())
                .ledgerType("CONSUME")
                .amountMinutes(leave.getRequestedMinutes())
                .entryDate(leave.getStartDate())
                .leaveRequestId(leave.getId())
                .source("system")
                .version(0)
                .build());
    }

    /**
     * engineer×leaveTypeの残数を、要員行のFOR UPDATEで直列化してから算出する（S11-P1-01）。
     * 台帳行だけのロックだとH2等で空集合時に競合を防げないため、sentinelは要員行に固定する。
     */
    private int lockAndBalanceMinutes(Long engineerId, String leaveType) {
        Engineer locked = engineerMapper.selectByIdForUpdate(engineerId);
        if (locked == null) {
            throw BusinessException.of(404, "error.leave.notFound");
        }
        // 要員ロック保持中に台帳を再読込（他txのCONSUME確定後の残高を見る）
        List<LeaveLedger> rows = leaveLedgerMapper.selectList(new LambdaQueryWrapper<LeaveLedger>()
                .eq(LeaveLedger::getEngineerId, engineerId)
                .eq(LeaveLedger::getLeaveType, leaveType)
                .orderByAsc(LeaveLedger::getId));
        return rows.stream()
                .mapToInt(row -> "GRANT".equals(row.getLedgerType())
                        ? value(row.getAmountMinutes()) : -value(row.getAmountMinutes()))
                .sum();
    }

    /**
     * 承認/取消を対象月のleave_minutesへ反映する（calendar反映、design §5.1）。
     * 締め済み月は上書き不可（R1.4）。月行が無い場合は入力中で作成する。
     */
    private void applyMonths(LeaveRequest leave, boolean add) {
        Map<LocalDate, Integer> dayMinutes = LeaveMinutesCalculator.dayMinutes(leave, attendanceCalculator);
        Map<LocalDate, Integer> byMonth = new java.util.TreeMap<>();
        for (Map.Entry<LocalDate, Integer> entry : dayMinutes.entrySet()) {
            LocalDate month = entry.getKey().withDayOfMonth(1);
            byMonth.merge(month, entry.getValue(), Integer::sum);
        }
        for (Map.Entry<LocalDate, Integer> entry : byMonth.entrySet()) {
            if (entry.getValue() == 0) {
                continue;
            }
            applyMonth(leave, entry.getKey(), entry.getValue(), add);
        }
    }

    private void applyMonth(LeaveRequest leave, LocalDate workMonth, int minutes, boolean add) {
        AttendanceMonth month = attendanceMonthMapper.selectOne(new LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getEngineerId, leave.getEngineerId())
                .eq(AttendanceMonth::getWorkMonth, workMonth)
                .last("FOR UPDATE"));
        if (month == null) {
            AttendanceMonth created = AttendanceMonth.builder()
                    .engineerId(leave.getEngineerId())
                    .legalEntityId(leave.getLegalEntityId())
                    .organizationId(leave.getOrganizationId())
                    .workMonth(workMonth)
                    .leaveMinutes(add ? minutes : 0)
                    .status("入力中")
                    .version(0)
                    .build();
            attendanceMonthMapper.insert(created);
            return;
        }
        if (CLOSED.equals(month.getStatus())) {
            throw BusinessException.of(400, "error.leave.closedMonth");
        }
        int current = month.getLeaveMinutes() == null ? 0 : month.getLeaveMinutes();
        int next = add ? current + minutes : current - minutes;
        if (next < 0) {
            throw BusinessException.of(409, "error.leave.invalidTransition", month.getStatus(), "取消済");
        }
        int version = month.getVersion() == null ? 0 : month.getVersion();
        int updated = attendanceMonthMapper.update(null, new UpdateWrapper<AttendanceMonth>()
                .set("leave_minutes", next)
                .set("version", version + 1)
                .set("updated_at", java.time.LocalDateTime.now())
                .eq("id", month.getId())
                .eq("version", version));
        if (updated != 1) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    /** R2.3: 客先報告が必要な種別（config）の承認時、担当営業（主担当）へ個人通知する。 */
    private void notifySalesRep(LeaveRequest leave) {
        String types = systemConfigService.getString("leave.sales-notification.types", "有給,特別休暇");
        if (types == null || !Arrays.asList(types.split(",")).contains(leave.getLeaveType())) {
            return;
        }
        Long salesUserId = engineerSalesService.findPrimarySalesUserId(leave.getEngineerId());
        if (salesUserId == null) {
            return;
        }
        String message = "[\"notification.msg.LEAVE_APPROVED_SALES\", \"" + leave.getLeaveType() + "\", \""
                + leave.getStartDate() + "\"]";
        notificationService.publishToUser(salesUserId, "LEAVE_APPROVED_SALES", "休暇承認のお知らせ",
                message, "/engineer/list", "leave-approved:" + leave.getId(), "engineer");
    }

    private boolean isBalanceManaged(String leaveType) {
        String types = systemConfigService.getString("leave.balance.types", "有給,半休,時間休,代休,特別休暇");
        return types != null && Arrays.asList(types.split(",")).contains(leaveType);
    }

    private LeaveRequest leave(Long targetId) {
        LeaveRequest leave = targetId == null ? null : leaveRequestMapper.selectById(targetId);
        if (leave == null) {
            throw BusinessException.of(404, "error.leave.notFound");
        }
        return leave;
    }

    private void requireVersion(ApprovalRequest request, LeaveRequest leave) {
        if (!java.util.Objects.equals(request.getTargetVersion(), version(leave))) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    private void casStatus(LeaveRequest leave, String target) {
        int version = value(leave.getVersion());
        int updated = leaveRequestMapper.update(null, new UpdateWrapper<LeaveRequest>()
                .set("status", target)
                .set("version", version + 1)
                .set("updated_at", java.time.LocalDateTime.now())
                .eq("id", leave.getId())
                .eq("status", leave.getStatus())
                .eq("version", version));
        if (updated != 1) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    private long version(LeaveRequest leave) {
        return leave.getVersion() == null ? 0L : leave.getVersion().longValue();
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
