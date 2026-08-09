package com.ses.attendance;

import com.ses.entity.AttendanceMonth;
import com.ses.entity.EmployeeAttendance;
import com.ses.entity.OvertimeAgreement;
import com.ses.entity.WorkCalendar;
import com.ses.entity.WorkCalendarDay;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EmployeeAttendanceMapper;
import com.ses.mapper.OvertimeAgreementMapper;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.mapper.WorkCalendarMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** T068のL1〜L3定向test。H2 replay、entity CRUD、NULL/0、期間/UNIQUEを固定する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class AttendanceSchemaTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private WorkCalendarMapper workCalendarMapper;
    @Autowired private WorkCalendarDayMapper workCalendarDayMapper;
    @Autowired private EmployeeAttendanceMapper employeeAttendanceMapper;
    @Autowired private AttendanceMonthMapper attendanceMonthMapper;
    @Autowired private OvertimeAgreementMapper overtimeAgreementMapper;

    @Test
    void T068の全テーブルとovertime設定がH2へreplayされる() {
        for (String table : List.of(
                "m_work_calendar", "m_work_calendar_day", "t_employee_attendance",
                "t_attendance_month", "t_leave_request", "m_overtime_agreement",
                "t_overtime_followup")) {
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                            + "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME = ?",
                    Integer.class, table.toUpperCase()));
        }

        assertEquals(9, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_system_config WHERE config_key LIKE 'overtime.%'",
                Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME='T_ENGINEER' "
                        + "AND COLUMN_NAME='OVERTIME_EXEMPT_FLAG'",
                Integer.class));
    }

    @Test
    void calendarのscheduledMinutesはNULLと0を区別しentityから読める() {
        long engineerId = newEngineer("T068-calendar-" + System.nanoTime());
        WorkCalendar calendar = WorkCalendar.builder()
                .legalEntityId(7001L)
                .engineerId(engineerId)
                .name("T068 calendar")
                .validFrom(LocalDate.of(2026, 8, 1))
                .build();
        workCalendarMapper.insert(calendar);

        WorkCalendarDay holiday = WorkCalendarDay.builder()
                .calendarId(calendar.getId())
                .calendarDate(LocalDate.of(2026, 8, 8))
                .dayType("法定休日")
                .scheduledMinutes(null)
                .build();
        WorkCalendarDay zeroDay = WorkCalendarDay.builder()
                .calendarId(calendar.getId())
                .calendarDate(LocalDate.of(2026, 8, 9))
                .dayType("所定日")
                .scheduledMinutes(0)
                .build();
        workCalendarDayMapper.insert(holiday);
        workCalendarDayMapper.insert(zeroDay);

        assertNull(workCalendarDayMapper.selectById(holiday.getId()).getScheduledMinutes());
        assertEquals(0, workCalendarDayMapper.selectById(zeroDay.getId()).getScheduledMinutes());
    }

    @Test
    void externalAttendanceはsourceとexternalIdの重複を拒否する() {
        long engineerId = newEngineer("T068-source-" + System.nanoTime());
        EmployeeAttendance first = EmployeeAttendance.builder()
                .engineerId(engineerId)
                .workDate(LocalDate.of(2026, 8, 3))
                .source("freee")
                .sourceExternalId("T068-freee-" + System.nanoTime())
                .build();
        employeeAttendanceMapper.insert(first);

        EmployeeAttendance duplicate = EmployeeAttendance.builder()
                .engineerId(engineerId)
                .workDate(LocalDate.of(2026, 8, 4))
                .source("freee")
                .sourceExternalId(first.getSourceExternalId())
                .build();
        assertThrows(DataIntegrityViolationException.class,
                () -> employeeAttendanceMapper.insert(duplicate));
    }

    @Test
    void agreementのvalidFromは月初だけを許可する() {
        OvertimeAgreement invalid = OvertimeAgreement.builder()
                .legalEntityId(7002L)
                .validFrom(LocalDate.of(2026, 8, 2))
                .specialClause(0)
                .build();
        assertThrows(DataIntegrityViolationException.class,
                () -> overtimeAgreementMapper.insert(invalid));

        OvertimeAgreement valid = OvertimeAgreement.builder()
                .legalEntityId(7002L)
                .validFrom(LocalDate.of(2026, 8, 1))
                .specialClause(0)
                .normalMonthLimitMinutes(2700)
                .build();
        overtimeAgreementMapper.insert(valid);
        assertNotNull(overtimeAgreementMapper.selectById(valid.getId()));
    }

    @Test
    void attendanceMonthの対象月は月初で一意になる() {
        long engineerId = newEngineer("T068-month-" + System.nanoTime());
        AttendanceMonth month = AttendanceMonth.builder()
                .engineerId(engineerId)
                .workMonth(LocalDate.of(2026, 8, 1))
                .build();
        attendanceMonthMapper.insert(month);

        AttendanceMonth duplicate = AttendanceMonth.builder()
                .engineerId(engineerId)
                .workMonth(LocalDate.of(2026, 8, 1))
                .build();
        assertThrows(DataIntegrityViolationException.class,
                () -> attendanceMonthMapper.insert(duplicate));
    }

    private long newEngineer(String name) {
        jdbcTemplate.update(
                "INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')",
                name);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, name);
        assertNotNull(id);
        return id;
    }
}
