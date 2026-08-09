package com.ses.attendance;

import com.ses.common.exception.BusinessException;
import com.ses.dto.attendance.AttendanceBreakRequest;
import com.ses.dto.attendance.AttendanceDayRequest;
import com.ses.dto.attendance.AttendanceOverviewDto;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EmployeeAttendance;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EmployeeAttendanceBreakMapper;
import com.ses.mapper.EmployeeAttendanceMapper;
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

/**
 * R2-P1-02方式A（休憩区間保存＋区間intersection）のL2定向test。
 * 深夜前の休憩位置・不一致400拒否・区間不明行のfail-closedを固定する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AttendanceBreakIntervalServiceTest {

    private static final long USER_ID = 92011L;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private EmployeeAttendanceMapper employeeAttendanceMapper;

    @Autowired
    private EmployeeAttendanceBreakMapper employeeAttendanceBreakMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long engineerId;
    private long organizationId;
    private long calendarId;

    @BeforeEach
    void setUp() {
        String name = "T070break-" + System.nanoTime();
        String code = "T070break-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 70001, ?, ?, '部門', '2026-01-01', '有効')", code, name);
        organizationId = jdbcTemplate.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) VALUES (?, '正社員', 'Bench', ?)",
                name, organizationId);
        engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, name);
        jdbcTemplate.update("INSERT INTO m_work_calendar (legal_entity_id, organization_id, engineer_id, name, valid_from, status) "
                + "VALUES (70001, ?, ?, ?, '2026-01-01', '有効')", organizationId, engineerId, name);
        calendarId = jdbcTemplate.queryForObject("SELECT id FROM m_work_calendar WHERE engineer_id = ?", Long.class, engineerId);
        for (LocalDate date : List.of(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4))) {
            jdbcTemplate.update("INSERT INTO m_work_calendar_day (calendar_id, calendar_date, day_type, scheduled_minutes) "
                    + "VALUES (?, ?, '通常', 480)", calendarId, date);
        }
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
    void 深夜前の休憩区間を保存し月次とreadDTOへ反映する() {
        AttendanceDayRequest request = dayRequest(LocalDate.of(2026, 8, 3),
                LocalTime.of(21, 0), LocalTime.of(23, 0), List.of(interval(21, 0, 22, 0)), null);

        attendanceService.saveMyDay(request);

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_employee_attendance_break", Integer.class));
        AttendanceOverviewDto overview = attendanceService.mine("2026-08");
        assertEquals(60, overview.getMonths().get(0).getLateNightMinutes());
        assertEquals(60, overview.getMonths().get(0).getWorkedMinutes());
        assertEquals(1, overview.getMonths().get(0).getDays().size());
        assertEquals(LocalTime.of(21, 0),
                overview.getMonths().get(0).getDays().get(0).getBreaks().get(0).getStartTime());
        assertEquals(LocalTime.of(22, 0),
                overview.getMonths().get(0).getDays().get(0).getBreaks().get(0).getEndTime());
        assertEquals(60, overview.getMonths().get(0).getDays().get(0).getBreakMinutes());
    }

    @Test
    void 休憩分と区間合計の不一致は400で拒否する() {
        AttendanceDayRequest request = dayRequest(LocalDate.of(2026, 8, 3),
                LocalTime.of(9, 0), LocalTime.of(18, 0), List.of(interval(12, 0, 13, 0)), 30);

        BusinessException e = assertThrows(BusinessException.class,
                () -> attendanceService.saveMyDay(request));

        assertEquals("error.attendance.breakMinutesMismatch", e.getMessageKey());
    }

    @Test
    void 休憩分だけの入力で区間が無い場合は400で拒否する() {
        AttendanceDayRequest request = dayRequest(LocalDate.of(2026, 8, 3),
                LocalTime.of(9, 0), LocalTime.of(18, 0), null, 60);

        BusinessException e = assertThrows(BusinessException.class,
                () -> attendanceService.saveMyDay(request));

        assertEquals("error.attendance.breakMinutesMismatch", e.getMessageKey());
    }

    @Test
    void 重複する休憩区間は400で拒否する() {
        AttendanceDayRequest request = dayRequest(LocalDate.of(2026, 8, 3),
                LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(interval(12, 0, 13, 0), interval(12, 30, 13, 30)), null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> attendanceService.saveMyDay(request));

        assertEquals("error.attendance.breakOverlap", e.getMessageKey());
    }

    @Test
    void 区間不明行がある月は再確定を拒否し区間補正で解消する() {
        insertUnknownBreakDay();

        BusinessException blocked = assertThrows(BusinessException.class,
                () -> attendanceService.saveMyDay(dayRequest(LocalDate.of(2026, 8, 3),
                        LocalTime.of(9, 0), LocalTime.of(18, 0), null, null)));
        assertEquals("error.attendance.breakUnknown", blocked.getMessageKey());

        BusinessException resaveWithoutIntervals = assertThrows(BusinessException.class,
                () -> attendanceService.saveMyDay(dayRequest(LocalDate.of(2026, 8, 4),
                        LocalTime.of(9, 0), LocalTime.of(18, 0), null, null)));
        assertEquals("error.attendance.breakUnknown", resaveWithoutIntervals.getMessageKey());

        attendanceService.saveMyDay(dayRequest(LocalDate.of(2026, 8, 4),
                LocalTime.of(9, 0), LocalTime.of(18, 0), List.of(interval(12, 0, 13, 0)), null));
        attendanceService.saveMyDay(dayRequest(LocalDate.of(2026, 8, 3),
                LocalTime.of(9, 0), LocalTime.of(18, 0), null, null));
    }

    @Test
    void 日次削除で休憩区間も一緒に削除される() {
        attendanceService.saveMyDay(dayRequest(LocalDate.of(2026, 8, 3),
                LocalTime.of(9, 0), LocalTime.of(18, 0), List.of(interval(12, 0, 13, 0)), null));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_employee_attendance_break", Integer.class));

        attendanceService.deleteMyDay("2026-08", "2026-08-03");

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_employee_attendance_break", Integer.class));
    }

    private void insertUnknownBreakDay() {
        EmployeeAttendance unknown = EmployeeAttendance.builder()
                .engineerId(engineerId)
                .legalEntityId(70001L)
                .organizationId(organizationId)
                .workCalendarId(calendarId)
                .workDate(LocalDate.of(2026, 8, 4))
                .clockIn(LocalTime.of(9, 0))
                .clockOut(LocalTime.of(18, 0))
                .breakMinutes(60)
                .workType("通常")
                .source("manual")
                .status("入力中")
                .version(0)
                .build();
        employeeAttendanceMapper.insert(unknown);
    }

    private AttendanceDayRequest dayRequest(LocalDate workDate, LocalTime clockIn, LocalTime clockOut,
                                         List<AttendanceBreakRequest> breaks, Integer breakMinutes) {
        AttendanceDayRequest request = new AttendanceDayRequest();
        request.setWorkDate(workDate);
        request.setClockIn(clockIn);
        request.setClockOut(clockOut);
        request.setBreaks(breaks);
        request.setBreakMinutes(breakMinutes);
        request.setWorkType("通常");
        return request;
    }

    private AttendanceBreakRequest interval(int startHour, int startMinute, int endHour, int endMinute) {
        AttendanceBreakRequest interval = new AttendanceBreakRequest();
        interval.setStartTime(LocalTime.of(startHour, startMinute));
        interval.setEndTime(LocalTime.of(endHour, endMinute));
        return interval;
    }

    private void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
