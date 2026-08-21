package com.ses.service.attendance;

import com.ses.dto.attendance.AttendanceOverviewDto;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.EmployeeAttendance;
import com.ses.entity.Engineer;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EmployeeAttendanceMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.AttendanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ATT-01: 管理勤怠の摘要は SQL ページングし、日次行を一覧のために物化しない。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
@DisplayName("雇用勤怠 overview SQLページング (ATT-01)")
class AttendanceServiceOverviewSqlPageTest {

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private AttendanceMonthMapper attendanceMonthMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @SpyBean
    private EmployeeAttendanceMapper employeeAttendanceMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("管理一覧は摘要のみをページングし日次SELECTを発行しない")
    void management_paginatesSummaryWithoutMaterializingAllDays() {
        authenticateAdmin();
        LocalDate workMonth = LocalDate.of(2026, 8, 1);
        for (int i = 1; i <= 12; i++) {
            Engineer engineer = Engineer.builder()
                    .fullName("ATT-PAGE-" + i)
                    .employmentType("正社員")
                    .status("Bench")
                    .build();
            engineerMapper.insert(engineer);

            attendanceMonthMapper.insert(AttendanceMonth.builder()
                    .engineerId(engineer.getId())
                    .legalEntityId(90001L)
                    .organizationId(90001L)
                    .workMonth(workMonth)
                    .scheduledMinutes(480)
                    .workedMinutes(480)
                    .regularMinutes(480)
                    .overtimeMinutes(0)
                    .holidayMinutes(0)
                    .lateNightMinutes(0)
                    .leaveMinutes(0)
                    .status("入力中")
                    .version(0)
                    .build());

            // 全日分を投入しても、管理一覧のロードでは日次を読まないこと。
            for (int day = 1; day <= 10; day++) {
                employeeAttendanceMapper.insert(EmployeeAttendance.builder()
                        .engineerId(engineer.getId())
                        .legalEntityId(90001L)
                        .organizationId(90001L)
                        .workDate(LocalDate.of(2026, 8, day))
                        .clockIn(LocalTime.of(9, 0))
                        .clockOut(LocalTime.of(18, 0))
                        .breakMinutes(60)
                        .regularMinutes(480)
                        .overtimeMinutes(0)
                        .holidayMinutes(0)
                        .lateNightMinutes(0)
                        .workType("通常")
                        .source("manual")
                        .status("入力中")
                        .version(0)
                        .build());
            }
        }

        org.mockito.Mockito.clearInvocations(employeeAttendanceMapper);

        AttendanceOverviewDto page1 = attendanceService.management("2026-08", 1L, 5L);
        assertEquals(12L, page1.getTotal());
        assertEquals(5, page1.getMonths().size());
        assertTrue(page1.getMonths().stream().allMatch(m -> m.getDays() == null || m.getDays().isEmpty()),
                "管理一覧の摘要に日次を同梱しないこと");
        verify(employeeAttendanceMapper, never()).selectList(any());

        AttendanceOverviewDto page2 = attendanceService.management("2026-08", 2L, 5L);
        assertEquals(5, page2.getMonths().size());
        verify(employeeAttendanceMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("日次明細は要員単位で遅延取得できる")
    void managementDays_loadsOnlyOneEngineer() {
        authenticateAdmin();
        Engineer engineer = Engineer.builder()
                .fullName("ATT-DAYS-ONE")
                .employmentType("正社員")
                .status("Bench")
                .build();
        engineerMapper.insert(engineer);
        attendanceMonthMapper.insert(AttendanceMonth.builder()
                .engineerId(engineer.getId())
                .legalEntityId(90002L)
                .organizationId(90002L)
                .workMonth(LocalDate.of(2026, 8, 1))
                .scheduledMinutes(480)
                .workedMinutes(480)
                .regularMinutes(480)
                .overtimeMinutes(0)
                .holidayMinutes(0)
                .lateNightMinutes(0)
                .leaveMinutes(0)
                .status("入力中")
                .version(0)
                .build());
        employeeAttendanceMapper.insert(EmployeeAttendance.builder()
                .engineerId(engineer.getId())
                .legalEntityId(90002L)
                .organizationId(90002L)
                .workDate(LocalDate.of(2026, 8, 3))
                .clockIn(LocalTime.of(9, 0))
                .clockOut(LocalTime.of(18, 0))
                .breakMinutes(60)
                .regularMinutes(480)
                .overtimeMinutes(0)
                .holidayMinutes(0)
                .lateNightMinutes(0)
                .workType("通常")
                .source("manual")
                .status("入力中")
                .version(0)
                .build());

        var days = attendanceService.managementDays(engineer.getId(), "2026-08");
        assertEquals(1, days.size());
        assertEquals(LocalDate.of(2026, 8, 3), days.get(0).getWorkDate());
    }

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
    }
}
