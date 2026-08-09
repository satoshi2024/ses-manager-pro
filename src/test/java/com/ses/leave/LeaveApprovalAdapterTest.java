package com.ses.leave;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.LeaveLedger;
import com.ses.entity.LeaveRequest;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.LeaveLedgerMapper;
import com.ses.mapper.LeaveRequestMapper;
import com.ses.service.NotificationService;
import com.ses.service.EngineerSalesService;
import com.ses.service.leave.LeaveApprovalAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T071の承認adapter直接test（L2）。最終承認時の状態CAS・残数CAS・月次反映・営業通知・取消戻しを固定する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LeaveApprovalAdapterTest {

    private static final long USER_ID = 92031L;

    @Autowired
    private LeaveApprovalAdapter adapter;

    @Autowired
    private LeaveRequestMapper leaveRequestMapper;

    @Autowired
    private LeaveLedgerMapper leaveLedgerMapper;

    @Autowired
    private AttendanceMonthMapper attendanceMonthMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private EngineerSalesService engineerSalesService;

    @MockBean
    private NotificationService notificationService;

    private long engineerId;
    private long organizationId;
    private long calendarId;

    @BeforeEach
    void setUp() {
        String name = "T071adapter-" + System.nanoTime();
        String code = "T071adapter-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 70001, ?, ?, '部門', '2026-01-01', '有効')", code, name);
        organizationId = jdbcTemplate.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) VALUES (?, '正社員', 'Bench', ?)",
                name, organizationId);
        engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, name);
        jdbcTemplate.update("INSERT INTO m_work_calendar (legal_entity_id, organization_id, engineer_id, name, valid_from, status) "
                + "VALUES (70001, ?, ?, ?, '2026-01-01', '有効')", organizationId, engineerId, name);
        calendarId = jdbcTemplate.queryForObject("SELECT id FROM m_work_calendar WHERE engineer_id = ?", Long.class, engineerId);
        jdbcTemplate.update("INSERT INTO m_work_calendar_day (calendar_id, calendar_date, day_type, scheduled_minutes) "
                + "VALUES (?, '2026-08-03', '通常', 480)", calendarId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_要員"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 最終承認で承認済へ遷移し消化行と月次leaveMinutesと営業通知が行われる() {
        insertGrant(960);
        LeaveRequest leave = insertLeave("有給", "申請中", 480);
        when(engineerSalesService.findPrimarySalesUserId(engineerId)).thenReturn(93001L);

        adapter.applyApproved(approvalRequest(leave, LeaveApprovalAdapter.REQUEST_TYPE, 0));

        assertEquals("承認済", leaveRequestMapper.selectById(leave.getId()).getStatus());
        List<LeaveLedger> consumes = leaveLedgerMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LeaveLedger>()
                        .eq(LeaveLedger::getLeaveRequestId, leave.getId()));
        assertEquals(1, consumes.size());
        assertEquals("CONSUME", consumes.get(0).getLedgerType());
        assertEquals(480, consumes.get(0).getAmountMinutes());

        AttendanceMonth month = attendanceMonthMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AttendanceMonth>()
                        .eq(AttendanceMonth::getEngineerId, engineerId)
                        .eq(AttendanceMonth::getWorkMonth, LocalDate.of(2026, 8, 1)));
        assertTrue(month != null, "月次行が作成されるはず");
        assertEquals(480, month.getLeaveMinutes());

        verify(notificationService).publishToUser(org.mockito.ArgumentMatchers.eq(93001L),
                org.mockito.ArgumentMatchers.eq("LEAVE_APPROVED_SALES"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 客先報告不要な種別の承認では営業通知しない() {
        LeaveRequest leave = insertLeave("欠勤", "申請中", 480);

        adapter.applyApproved(approvalRequest(leave, LeaveApprovalAdapter.REQUEST_TYPE, 0));

        verify(notificationService, never()).publishToUser(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("LEAVE_APPROVED_SALES"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 承認時点で残数が不足していれば承認を失敗させる() {
        insertGrant(240);
        LeaveRequest leave = insertLeave("有給", "申請中", 480);

        assertThrows(BusinessException.class,
                () -> adapter.applyApproved(approvalRequest(leave, LeaveApprovalAdapter.REQUEST_TYPE, 0)));
        assertEquals("申請中", leaveRequestMapper.selectById(leave.getId()).getStatus());
    }

    @Test
    void 取消承認で取消済へ遷移し消化行と月次leaveMinutesを戻す() {
        insertGrant(960);
        LeaveRequest leave = insertLeave("有給", "承認済", 480);
        LeaveLedger consume = LeaveLedger.builder()
                .engineerId(engineerId)
                .leaveType("有給")
                .ledgerType("CONSUME")
                .amountMinutes(480)
                .entryDate(LocalDate.of(2026, 8, 3))
                .leaveRequestId(leave.getId())
                .source("system")
                .version(0)
                .build();
        leaveLedgerMapper.insert(consume);
        jdbcTemplate.update("INSERT INTO t_attendance_month (engineer_id, legal_entity_id, organization_id, work_month, leave_minutes, status, version) "
                + "VALUES (?, 70001, ?, '2026-08-01', 480, '入力中', 0)", engineerId, organizationId);

        adapter.applyApproved(approvalRequest(leave, LeaveApprovalAdapter.CANCEL_REQUEST_TYPE, 0));

        assertEquals("取消済", leaveRequestMapper.selectById(leave.getId()).getStatus());
        assertEquals(0, leaveLedgerMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LeaveLedger>()
                        .eq(LeaveLedger::getLeaveRequestId, leave.getId())).size());
        AttendanceMonth month = attendanceMonthMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AttendanceMonth>()
                        .eq(AttendanceMonth::getEngineerId, engineerId)
                        .eq(AttendanceMonth::getWorkMonth, LocalDate.of(2026, 8, 1)));
        assertEquals(0, month.getLeaveMinutes());
    }

    @Test
    void version不一致の承認は競合として拒否する() {
        LeaveRequest leave = insertLeave("有給", "申請中", 480);

        assertThrows(BusinessException.class,
                () -> adapter.applyApproved(approvalRequest(leave, LeaveApprovalAdapter.REQUEST_TYPE, 99)));
    }

    private LeaveRequest insertLeave(String type, String status, int minutes) {
        LeaveRequest leave = LeaveRequest.builder()
                .engineerId(engineerId)
                .legalEntityId(70001L)
                .organizationId(organizationId)
                .leaveType(type)
                .startDate(LocalDate.of(2026, 8, 3))
                .endDate(LocalDate.of(2026, 8, 3))
                .startTime(type.equals("時間休") ? LocalTime.of(10, 0) : null)
                .endTime(type.equals("時間休") ? LocalTime.of(12, 0) : null)
                .requestedMinutes(minutes)
                .status(status)
                .version(0)
                .createdBy(USER_ID)
                .build();
        leaveRequestMapper.insert(leave);
        return leave;
    }

    private void insertGrant(int minutes) {
        jdbcTemplate.update("INSERT INTO t_leave_ledger "
                + "(engineer_id, legal_entity_id, leave_type, ledger_type, amount_minutes, entry_date, source, version) "
                + "VALUES (?, 70001, '有給', 'GRANT', ?, '2026-04-01', 'manual', 0)", engineerId, minutes);
    }

    private ApprovalRequest approvalRequest(LeaveRequest leave, String requestType, long targetVersion) {
        ApprovalRequest request = new ApprovalRequest();
        request.setRequestType(requestType);
        request.setTargetId(leave.getId());
        request.setTargetVersion(targetVersion);
        return request;
    }
}
