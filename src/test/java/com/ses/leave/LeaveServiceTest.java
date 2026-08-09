package com.ses.leave;

import com.ses.common.exception.BusinessException;
import com.ses.dto.leave.LeaveApplyRequest;
import com.ses.dto.leave.LeaveBalanceDto;
import com.ses.dto.leave.LeaveGrantRequest;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.LeaveRequest;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.LeaveRequestMapper;
import com.ses.service.LeaveService;
import com.ses.service.SystemConfigService;
import com.ses.service.approval.ApprovalEngineService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T071の申請・分計算・期間重複・残数両モード・取消・付与・scopeのL2定向test。
 * approval engineはmock（承認後のadapter動作はLeaveApprovalAdapterTestと統合testが検証する）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LeaveServiceTest {

    private static final long USER_ID = 92021L;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveRequestMapper leaveRequestMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ApprovalEngineService approvalEngineService;

    @MockBean
    private SystemConfigService systemConfigService;

    private long engineerId;
    private long organizationId;
    private long calendarId;

    @BeforeEach
    void setUp() {
        String name = "T071leave-" + System.nanoTime();
        String code = "T071leave-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 70001, ?, ?, '部門', '2026-01-01', '有効')", code, name);
        organizationId = jdbcTemplate.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) VALUES (?, '正社員', 'Bench', ?)",
                name, organizationId);
        engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, name);
        jdbcTemplate.update("INSERT INTO m_work_calendar (legal_entity_id, organization_id, engineer_id, name, valid_from, status) "
                + "VALUES (70001, ?, ?, ?, '2026-01-01', '有効')", organizationId, engineerId, name);
        calendarId = jdbcTemplate.queryForObject("SELECT id FROM m_work_calendar WHERE engineer_id = ?", Long.class, engineerId);
        for (LocalDate date : List.of(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11))) {
            jdbcTemplate.update("INSERT INTO m_work_calendar_day (calendar_id, calendar_date, day_type, scheduled_minutes) "
                    + "VALUES (?, ?, '通常', 480)", calendarId, date);
        }
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(USER_ID);
        engineerAccountLinkMapper.insert(link);
        authenticate(USER_ID, "要員");
        when(approvalEngineService.request(any())).thenReturn(null);
        // SystemConfigServiceは起動時1回だけDBからcacheするため、testではmockでモードを制御する。
        // 既定はexternal（残数に依存しない申請動作をbaselineにする）。内部正の挙動は当該testでinternalへ切替える。
        when(systemConfigService.getString(eq("leave.balance.source"), any())).thenReturn("external");
        when(systemConfigService.getString(eq("leave.balance.types"), any()))
                .thenReturn("有給,半休,時間休,代休,特別休暇");
        when(systemConfigService.getString(eq("leave.sales-notification.types"), any()))
                .thenReturn("有給,特別休暇");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 有給全日と半休と時間休の分計算がcalendarの所定時間から導出される() {
        stubApproval(1L);

        LeaveApplyRequest paid = request("有給", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 3), null, null);
        leaveService.apply(paid);
        LeaveRequest paidRow = singleRow();
        assertEquals(480, paidRow.getRequestedMinutes());

        LeaveApplyRequest half = request("半休", LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 4), null, null);
        leaveService.apply(half);
        assertEquals(240, singleRow().getRequestedMinutes());

        LeaveApplyRequest hourly = request("時間休", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5),
                LocalTime.of(10, 0), LocalTime.of(12, 0));
        leaveService.apply(hourly);
        assertEquals(120, singleRow().getRequestedMinutes());
    }

    @Test
    void 期間が重複する申請は拒否する() {
        stubApproval(2L);
        leaveService.apply(request("有給", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4), null, null));

        BusinessException e = assertThrows(BusinessException.class,
                () -> leaveService.apply(request("有給", LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 5), null, null)));
        assertEquals("error.leave.overlap", e.getMessageKey());

        leaveService.apply(request("欠勤", LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11), null, null));
    }

    @Test
    void 本システム正モードでは残数不足を拒否し充足していれば通す() {
        when(systemConfigService.getString(eq("leave.balance.source"), any())).thenReturn("internal");
        stubApproval(3L);
        authenticate(92023L, "HR");
        grant(960);
        authenticate(USER_ID, "要員");

        BusinessException insufficient = assertThrows(BusinessException.class,
                () -> leaveService.apply(request("有給", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5), null, null)));
        assertEquals("error.leave.balanceInsufficient", insufficient.getMessageKey());

        leaveService.apply(request("有給", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4), null, null));
    }

    @Test
    void 外部正モードでは残数不足でも申請を拒否しない() {
        stubApproval(4L);

        leaveService.apply(request("有給", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5), null, null));

        List<LeaveBalanceDto> balances = leaveService.balance(engineerId);
        assertEquals("external", balances.get(0).getMode());
    }

    @Test
    void 残数の正が未設定の場合は判定不能として拒否する() {
        when(systemConfigService.getString(eq("leave.balance.source"), any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> leaveService.apply(request("有給", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 3), null, null)));
        assertEquals("error.leave.balanceUnknown", e.getMessageKey());
    }

    @Test
    void 締め済み月と重なる申請は拒否する() {
        jdbcTemplate.update("INSERT INTO t_attendance_month (engineer_id, legal_entity_id, organization_id, work_month, status, version) "
                + "VALUES (?, 70001, ?, '2026-08-01', '締め済', 0)", engineerId, organizationId);

        BusinessException e = assertThrows(BusinessException.class,
                () -> leaveService.apply(request("有給", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4), null, null)));
        assertEquals("error.leave.closedMonth", e.getMessageKey());
    }

    @Test
    void 取消は承認済みだけが理由必須で申請できる() {
        stubApproval(5L);
        leaveService.apply(request("有給", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 3), null, null));
        LeaveRequest applied = singleRow();

        assertThrows(BusinessException.class, () -> leaveService.cancel(applied.getId(), null));
        assertEquals("error.leave.notApproved",
                assertThrows(BusinessException.class, () -> leaveService.cancel(applied.getId(), "理由")).getMessageKey());

        applied.setStatus("承認済");
        leaveRequestMapper.updateById(applied);
        assertThrows(BusinessException.class, () -> leaveService.cancel(applied.getId(), " "));
        leaveService.cancel(applied.getId(), "承認済の取消理由");
        verify(approvalEngineService, org.mockito.Mockito.times(2)).request(any());
    }

    @Test
    void 付与は管理者とHRだけが行え営業は拒否される() {
        authenticate(92022L, "営業");
        BusinessException denied = assertThrows(BusinessException.class,
                () -> leaveService.grant(grantRequest(240)));
        assertEquals("error.leave.roleDenied", denied.getMessageKey());

        authenticate(92023L, "HR");
        when(systemConfigService.getString(eq("leave.balance.source"), any())).thenReturn("internal");
        leaveService.grant(grantRequest(240));
        assertEquals(240, leaveService.balance(engineerId).get(0).getBalanceMinutes());
    }

    @Test
    void 営業は管理一覧へ到達できない() {
        authenticate(92022L, "営業");
        BusinessException e = assertThrows(BusinessException.class,
                () -> leaveService.management("2026-08"));
        assertEquals(403, e.getCode());
    }

    private void stubApproval(long id) {
        com.ses.entity.ApprovalRequest approval = new com.ses.entity.ApprovalRequest();
        approval.setId(id);
        when(approvalEngineService.request(any())).thenReturn(approval);
    }

    private void grant(int minutes) {
        leaveService.grant(grantRequest(minutes));
    }

    private LeaveGrantRequest grantRequest(int minutes) {
        LeaveGrantRequest grant = new LeaveGrantRequest();
        grant.setEngineerId(engineerId);
        grant.setLeaveType("有給");
        grant.setAmountMinutes(minutes);
        grant.setEntryDate(LocalDate.of(2026, 4, 1));
        return grant;
    }

    private LeaveApplyRequest request(String type, LocalDate start, LocalDate end, LocalTime startTime, LocalTime endTime) {
        LeaveApplyRequest request = new LeaveApplyRequest();
        request.setLeaveType(type);
        request.setStartDate(start);
        request.setEndDate(end);
        request.setStartTime(startTime);
        request.setEndTime(endTime);
        return request;
    }

    private LeaveRequest singleRow() {
        List<LeaveRequest> rows = leaveRequestMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LeaveRequest>()
                        .eq(LeaveRequest::getEngineerId, engineerId)
                        .orderByDesc(LeaveRequest::getId));
        assertTrue(!rows.isEmpty(), "申請行が存在するはず");
        return rows.get(0);
    }

    private void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
