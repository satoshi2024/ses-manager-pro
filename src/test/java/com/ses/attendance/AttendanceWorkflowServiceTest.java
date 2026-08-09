package com.ses.attendance;

import com.ses.common.exception.BusinessException;
import com.ses.dto.attendance.AttendanceDayRequest;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.EngineerAccountLink;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
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

    private long engineerId;

    @BeforeEach
    void setUp() {
        String name = "T070-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')", name);
        engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, name);
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
        request.setClockOut(LocalTime.of(18, 0));
        request.setBreakMinutes(60);
        request.setWorkType("通常");

        attendanceService.saveMyDay(request);
        AttendanceMonth month = month();
        assertEquals("入力中", month.getStatus());
        assertEquals(480, month.getWorkedMinutes());

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
