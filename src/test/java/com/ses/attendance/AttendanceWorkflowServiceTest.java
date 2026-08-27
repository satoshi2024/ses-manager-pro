package com.ses.attendance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.attendance.AttendanceBreakRequest;
import com.ses.dto.attendance.AttendanceDayRequest;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.EngineerAccountLink;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.AttendanceService;
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
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/** T070の月次状態機械と締め済み編集拒否を確認するL2定向test。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AttendanceWorkflowServiceTest {

    private static final long USER_ID = 92001L;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private AttendanceMonthMapper attendanceMonthMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    private ApprovalEngineService approvalEngineService;

    private long engineerId;
    private long organizationId;
    private long calendarId;

    @BeforeEach
    void setUp() {
        String name = "T070-" + System.nanoTime();
        String code = "T070-" + System.nanoTime();
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
        // 共有 H2 で engineer id が再利用されると旧 link が UNIQUE(engineer_id) と衝突する
        engineerAccountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getSysUserId, USER_ID)
                .or()
                .eq(EngineerAccountLink::getEngineerId, engineerId));
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(USER_ID);
        engineerAccountLinkMapper.insert(link);
        authenticate(USER_ID, "要員");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 本人提出差戻し再提出承認締めの状態CASと締め後編集拒否() {
        AttendanceDayRequest request = new AttendanceDayRequest();
        request.setWorkDate(LocalDate.of(2026, 8, 3));
        request.setClockIn(LocalTime.of(9, 0));
        request.setClockOut(LocalTime.of(23, 0));
        request.setBreakMinutes(0);
        request.setWorkType("法定休日");

        attendanceService.saveMyDay(request);
        AttendanceMonth month = month();
        assertEquals("入力中", month.getStatus());
        assertEquals(840, month.getWorkedMinutes());
        assertEquals(480, month.getRegularMinutes());
        assertEquals(360, month.getOvertimeMinutes());
        assertEquals(60, month.getLateNightMinutes());

        attendanceService.submitMyMonth("2026-08");
        assertEquals("提出済", month().getStatus());

        authenticate(93001L, "管理者");
        attendanceService.reject(engineerId, "2026-08");
        assertEquals("差戻し", month().getStatus());

        authenticate(USER_ID, "要員");
        attendanceService.submitMyMonth("2026-08");
        assertEquals("提出済", month().getStatus());

        authenticate(93001L, "管理者");
        attendanceService.approve(engineerId, "2026-08");
        attendanceService.reject(engineerId, "2026-08");
        assertEquals("差戻し", month().getStatus());
        authenticate(USER_ID, "要員");
        attendanceService.submitMyMonth("2026-08");
        authenticate(93001L, "管理者");
        attendanceService.approve(engineerId, "2026-08");
        attendanceService.close(engineerId, "2026-08");
        assertEquals("締め済", month().getStatus());

        authenticate(USER_ID, "要員");
        assertThrows(BusinessException.class, () -> attendanceService.saveMyDay(request));
    }

    @Test
    void 再openは理由必須で承認engineへ委譲し締め済みを直接戻さない() {
        AttendanceDayRequest request = new AttendanceDayRequest();
        request.setWorkDate(LocalDate.of(2026, 8, 3));
        request.setClockIn(LocalTime.of(9, 0));
        request.setClockOut(LocalTime.of(18, 0));
        AttendanceBreakRequest breakRequest = new AttendanceBreakRequest();
        breakRequest.setStartTime(LocalTime.of(12, 0));
        breakRequest.setEndTime(LocalTime.of(13, 0));
        request.setBreaks(List.of(breakRequest));
        request.setWorkType("通常");
        attendanceService.saveMyDay(request);
        attendanceService.submitMyMonth("2026-08");
        authenticate(93001L, "管理者");
        attendanceService.approve(engineerId, "2026-08");
        attendanceService.close(engineerId, "2026-08");

        assertThrows(BusinessException.class,
                () -> attendanceService.reopen(engineerId, "2026-08", " "));
        assertEquals("締め済", month().getStatus());

        attendanceService.reopen(engineerId, "2026-08", "訂正申請の根拠");
        verify(approvalEngineService).request(any());
        assertEquals("締め済", month().getStatus());
    }

    private AttendanceMonth month() {
        return attendanceMonthMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AttendanceMonth>()
                .eq(AttendanceMonth::getEngineerId, engineerId)
                .eq(AttendanceMonth::getWorkMonth, LocalDate.of(2026, 8, 1)));
    }

    private void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
