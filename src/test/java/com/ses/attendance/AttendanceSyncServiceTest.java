package com.ses.attendance;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.FreeeEmployeeLink;
import com.ses.entity.OvertimeFollowup;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EmployeeAttendanceMapper;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.mapper.OvertimeFollowupMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.attendance.AttendanceSyncService;
import com.ses.service.attendance.provider.MockAttendanceProvider;
import com.ses.dto.attendance.sync.AttendanceSyncResultDto;
import com.ses.dto.attendance.sync.ExternalAttendanceRecord;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T072 B1: freee/provider同期の冪等送信・read-only照合・締め済み月拒否+finding・CSV出力。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AttendanceSyncServiceTest {

    @Autowired
    private AttendanceSyncService attendanceSyncService;

    @Autowired
    private AttendanceMonthMapper attendanceMonthMapper;

    @Autowired
    private EmployeeAttendanceMapper employeeAttendanceMapper;

    @Autowired
    private FreeeEmployeeLinkMapper freeeEmployeeLinkMapper;

    @Autowired
    private OvertimeFollowupMapper overtimeFollowupMapper;

    @Autowired
    private MockAttendanceProvider mockAttendanceProvider;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long engineerId;
    private long organizationId;

    @BeforeEach
    void setUp() {
        systemConfigService.put("attendance.sync.provider", "mock", "test");
        mockAttendanceProvider.reset();
        String name = "T072-" + System.nanoTime();
        String code = "T072-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 72001, ?, ?, '部門', '2026-01-01', '有効')", code, name);
        organizationId = jdbcTemplate.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) VALUES (?, '正社員', 'Bench', ?)",
                name, organizationId);
        engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, name);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, "test",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private AttendanceMonth insertMonth(String status, String month) {
        AttendanceMonth monthRow = new AttendanceMonth();
        monthRow.setEngineerId(engineerId);
        monthRow.setLegalEntityId(72001L);
        monthRow.setOrganizationId(organizationId);
        monthRow.setWorkMonth(LocalDate.parse(month + "-01"));
        monthRow.setScheduledMinutes(14400);
        monthRow.setWorkedMinutes(9600);
        monthRow.setRegularMinutes(8640);
        monthRow.setOvertimeMinutes(960);
        monthRow.setHolidayMinutes(0);
        monthRow.setLateNightMinutes(120);
        monthRow.setLeaveMinutes(0);
        monthRow.setStatus(status);
        monthRow.setVersion(0);
        attendanceMonthMapper.insert(monthRow);
        return monthRow;
    }

    @Test
    void pushは承認締め済み月だけを冪等送信し_重複送信で外部1件() {
        authenticate(93001L, "管理者");
        insertMonth("承認済", "2026-08");
        insertMonth("入力中", "2026-07");

        AttendanceSyncResultDto first = attendanceSyncService.syncPush("2026-08");
        assertTrue(first.isSuccess());
        assertEquals(1, first.getPushedCount(), "承認済み月1件だけ送信");
        assertEquals(0, first.getRejectedCount());

        AttendanceSyncResultDto second = attendanceSyncService.syncPush("2026-08");
        assertTrue(second.isSuccess());
        assertEquals(0, second.getPushedCount());
        assertEquals(1, second.getDuplicateSkippedCount(), "同一payloadは外部1件（冪等キーで重複判定）");
    }

    @Test
    void pullは締め済み月への外部更新を拒否してfindingにする() {
        authenticate(93001L, "管理者");
        insertMonth("締め済", "2026-08");
        mockAttendanceProvider.seedExternalRecord(ExternalAttendanceRecord.builder()
                .sourceExternalId("ext-overwrite-1")
                .engineerId(engineerId)
                .workDate(LocalDate.of(2026, 8, 3))
                .clockIn(LocalTime.of(9, 0))
                .clockOut(LocalTime.of(23, 0))
                .breakMinutes(60)
                .regularMinutes(480)
                .overtimeMinutes(360)
                .holidayMinutes(0)
                .lateNightMinutes(60)
                .workType("通常")
                .updatedAt("2026-08-11T01:00:00Z")
                .build());

        AttendanceSyncResultDto result = attendanceSyncService.syncPull("2026-08");
        assertTrue(result.isSuccess());
        assertEquals(1, result.getPulledCount());
        assertEquals(1, result.getRejectedCount(), "締め済み月への外部更新は拒否");

        // finding: t_overtime_followupにEXT_OVERWRITE_REJECTEDが永続化される
        OvertimeFollowup finding = overtimeFollowupMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OvertimeFollowup>()
                        .eq(OvertimeFollowup::getEngineerId, engineerId)
                        .eq(OvertimeFollowup::getPeriodMonth, LocalDate.of(2026, 8, 1))
                        .eq(OvertimeFollowup::getWarningCode, "EXT_OVERWRITE_REJECTED"));
        assertNotNull(finding, "拒否はfindingとして永続化される");
        assertEquals("未対応", finding.getStatus());

        // 外部レコードは登録されない（read-only照合。source='freee'行を作らない）
        assertEquals(0, employeeAttendanceMapper.selectCount(null));

        // 同じcursorで再実行しても拒否は冪等（UNIQUE）
        AttendanceSyncResultDto again = attendanceSyncService.syncPull("2026-08");
        assertEquals(0, again.getPulledCount(), "cursor以降が無ければ取得0件");
    }

    @Test
    void pullは入力中月の外部レコードを照合に使うがDBへ登録しない() {
        authenticate(93001L, "管理者");
        insertMonth("入力中", "2026-08");
        mockAttendanceProvider.seedExternalRecord(ExternalAttendanceRecord.builder()
                .sourceExternalId("ext-ok-1")
                .engineerId(engineerId)
                .workDate(LocalDate.of(2026, 8, 3))
                .clockIn(LocalTime.of(9, 0))
                .clockOut(LocalTime.of(18, 0))
                .breakMinutes(60)
                .regularMinutes(480)
                .updatedAt("2026-08-11T01:00:00Z")
                .build());

        AttendanceSyncResultDto result = attendanceSyncService.syncPull("2026-08");
        assertTrue(result.isSuccess());
        assertEquals(1, result.getPulledCount());
        assertEquals(0, result.getRejectedCount(), "入力中月は拒否しない");
        assertEquals(0, employeeAttendanceMapper.selectCount(null), "照合に使うだけでDB登録しない");
        assertNotNull(result.getCursor());
        assertEquals("2026-08-11T01:00:00Z", result.getCursor());
        // R5-P2-02: 照合の実体（本システムに該当日次なし→unmatched）
        assertEquals(1, result.getUnmatchedCount());
    }

    @Test
    void pullは外部レコードを本システム日次と照合して一致と差異を集計する() {
        authenticate(93001L, "管理者");
        insertMonth("入力中", "2026-08");
        // 本システム日次: 08-03 09:00-18:00 break60 reg480
        jdbcTemplate.update("INSERT INTO t_employee_attendance (engineer_id, legal_entity_id, organization_id, work_date, "
                + "clock_in, clock_out, break_minutes, regular_minutes, overtime_minutes, holiday_minutes, late_night_minutes, "
                + "work_type, source, status) "
                + "VALUES (?, 72001, ?, '2026-08-03', '09:00', '18:00', 60, 480, 0, 0, 0, '通常', 'manual', '入力中')",
                engineerId, organizationId);

        // 外部レコード1: 本システムと一致
        mockAttendanceProvider.seedExternalRecord(ExternalAttendanceRecord.builder()
                .sourceExternalId("ext-match-1")
                .engineerId(engineerId)
                .workDate(LocalDate.of(2026, 8, 3))
                .clockIn(LocalTime.of(9, 0))
                .clockOut(LocalTime.of(18, 0))
                .breakMinutes(60)
                .regularMinutes(480)
                .updatedAt("2026-08-11T01:00:00Z")
                .build());
        // 外部レコード2: 差異あり（残業60分多い）
        mockAttendanceProvider.seedExternalRecord(ExternalAttendanceRecord.builder()
                .sourceExternalId("ext-diff-1")
                .engineerId(engineerId)
                .workDate(LocalDate.of(2026, 8, 3))
                .clockIn(LocalTime.of(9, 0))
                .clockOut(LocalTime.of(19, 0))
                .breakMinutes(60)
                .regularMinutes(480)
                .overtimeMinutes(60)
                .updatedAt("2026-08-11T02:00:00Z")
                .build());
        // 外部レコード3: 本システムに該当日次なし
        mockAttendanceProvider.seedExternalRecord(ExternalAttendanceRecord.builder()
                .sourceExternalId("ext-unmatch-1")
                .engineerId(engineerId)
                .workDate(LocalDate.of(2026, 8, 4))
                .updatedAt("2026-08-11T03:00:00Z")
                .build());

        AttendanceSyncResultDto result = attendanceSyncService.syncPull("2026-08");
        assertTrue(result.isSuccess());
        assertEquals(3, result.getPulledCount());
        assertEquals(1, result.getMatchedCount(), "一致1件");
        assertEquals(1, result.getDiffCount(), "差異1件");
        assertEquals(1, result.getUnmatchedCount(), "該当なし1件");
        assertEquals(2, result.getDifferences().size(), "差異サンプルは2件（diff＋unmatch）");
        assertEquals("ext-diff-1", result.getDifferences().get(0).getSourceExternalId());
        // 照合はread-only: 本システム日次（manual）は1件のまま、外部レコードは登録されない
        assertEquals(1, employeeAttendanceMapper.selectCount(null),
                "本システム日次1件のみ（外部レコードはDB登録されない）");
    }

    @Test
    void pullはtimezone設定でzoneなしupdated_atを正規化する() {
        systemConfigService.put("attendance.sync.timezone", "UTC", "test");
        authenticate(93001L, "管理者");
        insertMonth("入力中", "2026-08");
        mockAttendanceProvider.seedExternalRecord(ExternalAttendanceRecord.builder()
                .sourceExternalId("ext-tz-1")
                .engineerId(engineerId)
                .workDate(LocalDate.of(2026, 8, 3))
                .updatedAt("2026-08-11T10:00:00")
                .build());

        attendanceSyncService.syncPull("2026-08");
        String cursor = jdbcTemplate.queryForObject(
                "SELECT config_value FROM m_system_config WHERE config_key = 'attendance.sync.freee.cursor'",
                String.class);
        assertEquals("2026-08-11T10:00:00Z", cursor, "zoneなしupdated_atはtenant timezone（UTC）で解釈される");
    }

    @Test
    void pullはcursorを保存し_再実行で差分だけ取得する() {
        authenticate(93001L, "管理者");
        insertMonth("入力中", "2026-08");
        mockAttendanceProvider.seedExternalRecord(ExternalAttendanceRecord.builder()
                .sourceExternalId("ext-c1")
                .engineerId(engineerId)
                .workDate(LocalDate.of(2026, 8, 3))
                .updatedAt("2026-08-10T01:00:00Z")
                .build());

        attendanceSyncService.syncPull("2026-08");
        String cursor = jdbcTemplate.queryForObject(
                "SELECT config_value FROM m_system_config WHERE config_key = 'attendance.sync.freee.cursor'",
                String.class);
        assertNotNull(cursor);
        assertEquals("2026-08-10T01:00:00Z", cursor);

        mockAttendanceProvider.seedExternalRecord(ExternalAttendanceRecord.builder()
                .sourceExternalId("ext-c2")
                .engineerId(engineerId)
                .workDate(LocalDate.of(2026, 8, 4))
                .updatedAt("2026-08-12T01:00:00Z")
                .build());
        AttendanceSyncResultDto second = attendanceSyncService.syncPull("2026-08");
        assertEquals(1, second.getPulledCount(), "cursor以降の差分だけ取得");
        assertEquals("2026-08-12T01:00:00Z", second.getCursor());
    }

    @Test
    void HRは法人scope内の月だけを送信する() {
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password, real_name, role, status) "
                + "VALUES (93101, 'hr-t072', 'x', 'HR要員', 'HR', 1)");
        authenticate(93101L, "HR");
        // HRに法人72001を紐付ける
        jdbcTemplate.update("INSERT INTO t_user_organization (user_id, organization_id, primary_flag, valid_from, deleted_flag) "
                + "VALUES (93101, ?, 1, '2026-01-01', 0)", organizationId);
        insertMonth("承認済", "2026-08");

        AttendanceSyncResultDto result = attendanceSyncService.syncPush("2026-08");
        assertTrue(result.isSuccess());
        assertEquals(1, result.getPushedCount());
    }

    @Test
    void 営業は同期APIを拒否される() {
        authenticate(93201L, "営業");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> attendanceSyncService.syncPush("2026-08"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void csv出力は承認締め済み月の勤怠をBOM付きUTF8で返す() {
        authenticate(93001L, "管理者");
        insertMonth("承認済", "2026-08");
        jdbcTemplate.update("INSERT INTO t_employee_attendance (engineer_id, legal_entity_id, organization_id, work_date, "
                + "clock_in, clock_out, break_minutes, regular_minutes, overtime_minutes, holiday_minutes, late_night_minutes, "
                + "work_type, source, status) "
                + "VALUES (?, 72001, ?, '2026-08-03', '09:00', '18:00', 60, 480, 0, 0, 0, '通常', 'manual', '入力中')",
                engineerId, organizationId);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        attendanceSyncService.exportCsv("2026-08", out);
        String csv = out.toString(StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF"), "UTF-8 BOM付き");
        assertTrue(csv.contains("要員ID"));
        assertTrue(csv.contains(String.valueOf(engineerId)));
        assertTrue(csv.contains("2026-08-03"));
    }

    @Test
    void lastResultは実行結果を返す() {
        authenticate(93001L, "管理者");
        insertMonth("承認済", "2026-08");
        attendanceSyncService.syncPush("2026-08");
        AttendanceSyncResultDto last = attendanceSyncService.lastResult();
        assertNotNull(last);
        assertEquals("mock", last.getProvider());
        assertEquals(1, last.getPushedCount());
    }
}
