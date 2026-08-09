package com.ses.leave;

import com.ses.dto.leave.LeaveApplyRequest;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalDelegation;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.LeaveLedger;
import com.ses.entity.LeaveRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.ApprovalDelegationMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.LeaveLedgerMapper;
import com.ses.mapper.LeaveRequestMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.LeaveService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T071の承認engine統合test（L3）。実engine・route・代理承認を経て、
 * 休暇申請→承認→calendar反映（月次leave_minutes）が一気通貫で動くことを固定する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LeaveApprovalFlowIntegrationTest {

    private static final long APPLICANT_USER_ID = 92041L;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private ApprovalEngineService approvalEngineService;

    @Autowired
    private ApprovalRouteMapper approvalRouteMapper;

    @Autowired
    private ApprovalRouteStepMapper approvalRouteStepMapper;

    @Autowired
    private ApprovalDelegationMapper approvalDelegationMapper;

    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private ApprovalActionMapper approvalActionMapper;

    @Autowired
    private LeaveRequestMapper leaveRequestMapper;

    @Autowired
    private LeaveLedgerMapper leaveLedgerMapper;

    @Autowired
    private AttendanceMonthMapper attendanceMonthMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long engineerId;
    private long approverId;
    private long delegateId;

    @BeforeEach
    void setUp() {
        approverId = insertUser("leave-approver", "管理者");
        delegateId = insertUser("leave-delegate", "管理者");
        String name = "T071flow-" + System.nanoTime();        String code = "T071flow-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 70001, ?, ?, '部門', '2026-01-01', '有効')", code, name);
        long organizationId = jdbcTemplate.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) VALUES (?, '正社員', 'Bench', ?)",
                name, organizationId);
        engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, name);
        jdbcTemplate.update("INSERT INTO m_work_calendar (legal_entity_id, organization_id, engineer_id, name, valid_from, status) "
                + "VALUES (70001, ?, ?, ?, '2026-01-01', '有効')", organizationId, engineerId, name);
        long calendarId = jdbcTemplate.queryForObject("SELECT id FROM m_work_calendar WHERE engineer_id = ?", Long.class, engineerId);
        jdbcTemplate.update("INSERT INTO m_work_calendar_day (calendar_id, calendar_date, day_type, scheduled_minutes) "
                + "VALUES (?, '2026-08-03', '通常', 480)", calendarId);
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(APPLICANT_USER_ID);
        engineerAccountLinkMapper.insert(link);
        // H2 replayのsys_user.role ENUMはV1の4ロール（要員はV32で追加されるためH2 contextに無い）。
        // 申請者DB行のroleは承認engineの自己承認除外（ID比較）に影響しないため管理者を使う。
        insertUser(APPLICANT_USER_ID, "leave-applicant", "管理者");
        authenticate(APPLICANT_USER_ID, "要員");
        insertRoute(List.of(List.of(approverId)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 申請から承認を経て消化行と月次leaveMinutesと承認履歴が一気通貫で成立する() {
        jdbcTemplate.update("INSERT INTO t_leave_ledger "
                + "(engineer_id, legal_entity_id, leave_type, ledger_type, amount_minutes, entry_date, source, version) "
                + "VALUES (?, 70001, '有給', 'GRANT', 960, '2026-04-01', 'manual', 0)", engineerId);

        LeaveApplyRequest request = new LeaveApplyRequest();
        request.setLeaveType("有給");
        request.setStartDate(LocalDate.of(2026, 8, 3));
        request.setEndDate(LocalDate.of(2026, 8, 3));

        var result = leaveService.apply(request);

        ApprovalRequest approval = approvalRequestMapper.selectById(result.approvalRequestId());
        assertNotNull(approval);
        assertEquals("leave.request", approval.getRequestType());

        authenticate(approverId, "管理者");
        approvalEngineService.approve(approval.getId(), approverId, "承認します");

        LeaveRequest leave = leaveRequestMapper.selectById(result.leaveId());
        assertEquals("承認済", leave.getStatus());
        assertEquals("approved", approvalRequestMapper.selectById(approval.getId()).getStatus());

        List<LeaveLedger> consumes = leaveLedgerMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LeaveLedger>()
                        .eq(LeaveLedger::getLeaveRequestId, leave.getId()));
        assertEquals(1, consumes.size());
        assertEquals("CONSUME", consumes.get(0).getLedgerType());

        AttendanceMonth month = attendanceMonthMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AttendanceMonth>()
                        .eq(AttendanceMonth::getEngineerId, engineerId)
                        .eq(AttendanceMonth::getWorkMonth, LocalDate.of(2026, 8, 1)));
        assertNotNull(month);
        assertEquals(480, month.getLeaveMinutes());

        List<ApprovalAction> actions = approvalActionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApprovalAction>()
                        .eq(ApprovalAction::getRequestId, approval.getId()));
        assertEquals(1, actions.size());
        assertEquals(approverId, actions.get(0).getApproverUserId());
    }

    @Test
    void 代理承認で最終承認されdelegatedFromがaction履歴へ記録される() {
        approvalDelegationMapper.insert(ApprovalDelegation.builder()
                .fromUserId(approverId).toUserId(delegateId)
                .validFrom(LocalDate.now().minusDays(1)).validTo(LocalDate.now().plusDays(1))
                .build());

        LeaveApplyRequest request = new LeaveApplyRequest();
        request.setLeaveType("欠勤");
        request.setStartDate(LocalDate.of(2026, 8, 3));
        request.setEndDate(LocalDate.of(2026, 8, 3));
        var result = leaveService.apply(request);

        authenticate(delegateId, "管理者");
        ApprovalRequest approval = approvalRequestMapper.selectById(result.approvalRequestId());
        approvalEngineService.approve(approval.getId(), delegateId, "代理承認");

        assertEquals("承認済", leaveRequestMapper.selectById(result.leaveId()).getStatus());
        List<ApprovalAction> actions = approvalActionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApprovalAction>()
                        .eq(ApprovalAction::getRequestId, approval.getId()));
        assertEquals(1, actions.size());
        assertEquals(approverId, actions.get(0).getDelegatedFrom());
        assertEquals(delegateId, actions.get(0).getApproverUserId());
    }

    private void insertRoute(List<List<Long>> steps) {
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L).requestType("leave.request").organizationId(null)
                .minAmount(null).maxAmount(null).versionNo(1)
                .validFrom(LocalDate.now().minusDays(1)).activeFlag(1).build();
        approvalRouteMapper.insert(route);
        for (int i = 0; i < steps.size(); i++) {
            int stepNo = i + 1;
            for (Long approverId : steps.get(i)) {
                ApprovalRouteStep step = ApprovalRouteStep.builder()
                        .routeId(route.getId()).stepNo(stepNo).parallelGroup(stepNo)
                        .approverType("USER").approverValue(String.valueOf(approverId)).build();
                approvalRouteStepMapper.insert(step);
            }
        }
    }

    private long insertUser(String prefix, String role) {
        return insertUser(null, prefix, role);
    }

    private long insertUser(Long fixedId, String prefix, String role) {
        SysUser user = SysUser.builder()
                .username(prefix + "-" + System.nanoTime())
                .password("x")
                .realName(prefix)
                .role(role)
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    private void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
