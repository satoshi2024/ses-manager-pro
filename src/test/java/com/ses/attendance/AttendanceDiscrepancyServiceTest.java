package com.ses.attendance;

import com.ses.common.exception.BusinessException;
import com.ses.dto.attendance.discrepancy.AttendanceDiscrepancyDto;
import com.ses.entity.AttendanceMonth;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.attendance.AttendanceDiscrepancyService;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T073 B2: 客先工数差異のread-only比較・理由確認・scope・請求金額不変。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
class AttendanceDiscrepancyServiceTest {

    @Autowired
    private AttendanceDiscrepancyService discrepancyService;

    @Autowired
    private AttendanceMonthMapper attendanceMonthMapper;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long engineerId;
    private long organizationId;

    @BeforeEach
    void setUp() {
        systemConfigService.put("attendance.discrepancy.threshold-minutes", "480", "test");
        String name = "T073-" + System.nanoTime();
        String code = "T073-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 73001, ?, ?, '部門', '2026-01-01', '有効')", code, name);
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

    private AttendanceMonth insertMonth(int workedMinutes, String month, String status) {
        AttendanceMonth monthRow = new AttendanceMonth();
        monthRow.setEngineerId(engineerId);
        monthRow.setLegalEntityId(73001L);
        monthRow.setOrganizationId(organizationId);
        monthRow.setWorkMonth(LocalDate.parse(month + "-01"));
        monthRow.setScheduledMinutes(14400);
        monthRow.setWorkedMinutes(workedMinutes);
        monthRow.setRegularMinutes(workedMinutes);
        monthRow.setOvertimeMinutes(0);
        monthRow.setHolidayMinutes(0);
        monthRow.setLateNightMinutes(0);
        monthRow.setLeaveMinutes(0);
        monthRow.setStatus(status);
        monthRow.setVersion(0);
        attendanceMonthMapper.insert(monthRow);
        return monthRow;
    }

    private long insertContract(int actualHours) {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('T073顧客')");
        long customerId = jdbcTemplate.queryForObject("SELECT id FROM m_customer ORDER BY id DESC LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id) VALUES ('T073案件', ?)", customerId);
        long projectId = jdbcTemplate.queryForObject("SELECT id FROM t_project ORDER BY id DESC LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract (engineer_id, project_id, customer_id, contract_no, contract_type, "
                + "start_date, end_date, selling_price, cost_price, status) "
                + "VALUES (?, ?, ?, 'CT073-', '準委任', '2026-01-01', '2026-12-31', 1000000, 800000, '稼動中')",
                engineerId, projectId, customerId);
        long contractId = jdbcTemplate.queryForObject("SELECT id FROM t_contract ORDER BY id DESC LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, payment_amount, status) "
                + "VALUES (?, '2026-08', ?, 1000000, 800000, '入力中')", contractId, new BigDecimal(actualHours));
        return contractId;
    }

    private String billingAmountOf(long contractId) {
        return jdbcTemplate.queryForObject(
                "SELECT billing_amount FROM t_work_record WHERE contract_id = ?", String.class, contractId);
    }

    @Test
    void 差異は雇用勤怠と契約工数の差を分単位で返す() {
        authenticate(93001L, "管理者");
        insertMonth(24000, "2026-08", "締め済"); // 400時間
        insertContract(380); // 380時間

        AttendanceDiscrepancyDto dto = discrepancyService.list("2026-08");
        assertNotNull(dto);
        assertEquals(480, dto.getThresholdMinutes());
        assertEquals(1, dto.getItems().size());
        AttendanceDiscrepancyDto.Item item = dto.getItems().get(0);
        assertEquals(24000, item.getAttendanceMinutes());
        assertEquals(22800, item.getContractMinutes()); // 380h×60
        assertEquals(1200, item.getDiffMinutes()); // 20時間
        assertTrue(item.isOverThreshold(), "20時間差は閾値480分超過");
        assertFalse(item.isConfirmed());
    }

    @Test
    void 閾値境界は480分ちょうどで超過() {
        authenticate(93001L, "管理者");
        // 契約工数=392h（23520分）、勤怠=24000分 → 差=480分ちょうど
        insertMonth(24000, "2026-08", "締め済");
        insertContract(392);

        AttendanceDiscrepancyDto dto = discrepancyService.list("2026-08");
        assertEquals(480, dto.getItems().get(0).getDiffMinutes());
        assertTrue(dto.getItems().get(0).isOverThreshold(), "閾値ちょうどは超過（>=）");
    }

    @Test
    void 閾値以内の差異は超過にならない() {
        authenticate(93001L, "管理者");
        insertMonth(24000, "2026-08", "締め済");
        insertContract(392); // 差480分ちょうど→超過
        // 差479分のケース
        jdbcTemplate.update("UPDATE t_work_record SET actual_hours = 392.016 WHERE contract_id = "
                + "(SELECT id FROM t_contract WHERE engineer_id = ? AND contract_no LIKE 'CT073-%')", engineerId);
        // 392.016h×60=23521分 → 差479分
        AttendanceDiscrepancyDto dto = discrepancyService.list("2026-08");
        int diff = dto.getItems().get(0).getDiffMinutes();
        assertTrue(Math.abs(diff) < 480, "479分差は範囲内: " + diff);
        assertFalse(dto.getItems().get(0).isOverThreshold());
    }

    @Test
    void 確認理由を保存しても請求金額は変わらない() {
        authenticate(93001L, "管理者");
        insertMonth(24000, "2026-08", "締め済");
        long contractId = insertContract(380);
        String before = billingAmountOf(contractId);

        discrepancyService.confirm(engineerId, "2026-08", "客先側の実績との調整のため");

        AttendanceDiscrepancyDto dto = discrepancyService.list("2026-08");
        AttendanceDiscrepancyDto.Item item = dto.getItems().get(0);
        assertTrue(item.isConfirmed());
        assertEquals("客先側の実績との調整のため", item.getReason());
        assertNotNull(item.getConfirmedAt());
        assertEquals("93001", item.getConfirmedBy());

        // R4.2: 確認しても請求金額・工数は不変
        assertEquals(before, billingAmountOf(contractId), "確認理由の保存で請求金額は変わらない");
        BigDecimal actualHours = jdbcTemplate.queryForObject(
                "SELECT actual_hours FROM t_work_record WHERE contract_id = ?", BigDecimal.class, contractId);
        assertEquals(0, new BigDecimal("380").compareTo(actualHours), "契約工数も不変");
    }

    @Test
    void 確認理由は空だと拒否される() {
        authenticate(93001L, "管理者");
        insertMonth(24000, "2026-08", "締め済");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> discrepancyService.confirm(engineerId, "2026-08", "  "));
        assertEquals(400, ex.getCode());
    }

    @Test
    void 営業は差異APIを拒否される() {
        authenticate(93201L, "営業");
        insertMonth(24000, "2026-08", "締め済");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> discrepancyService.list("2026-08"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void HRは担当法人内の差異だけ見える() {
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password, real_name, role, status) "
                + "VALUES (93101, 'hr-t073', 'x', 'HR', 'HR', 1)");
        jdbcTemplate.update("INSERT INTO t_user_organization (user_id, organization_id, primary_flag, valid_from, deleted_flag) "
                + "VALUES (93101, ?, 1, '2026-01-01', 0)", organizationId);
        authenticate(93101L, "HR");
        insertMonth(24000, "2026-08", "締め済");
        insertContract(380);

        AttendanceDiscrepancyDto dto = discrepancyService.list("2026-08");
        assertEquals(1, dto.getItems().size(), "担当法人の差異は見える");

        // 他法人の確認は404
        discrepancyService.confirm(engineerId, "2026-08", "担当内");
        // 他法人の要員を作る
        String name = "T073B-" + System.nanoTime();
        String code = "T073B-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 73002, ?, ?, '部門', '2026-01-01', '有効')", code, name);
        long otherOrgId = jdbcTemplate.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) VALUES (?, '正社員', 'Bench', ?)",
                name, otherOrgId);
        long otherEngineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, name);
        AttendanceMonth other = new AttendanceMonth();
        other.setEngineerId(otherEngineerId);
        other.setLegalEntityId(73002L);
        other.setOrganizationId(otherOrgId);
        other.setWorkMonth(LocalDate.of(2026, 8, 1));
        other.setScheduledMinutes(14400);
        other.setWorkedMinutes(24000);
        other.setRegularMinutes(24000);
        other.setOvertimeMinutes(0);
        other.setHolidayMinutes(0);
        other.setLateNightMinutes(0);
        other.setLeaveMinutes(0);
        other.setStatus("締め済");
        other.setVersion(0);
        attendanceMonthMapper.insert(other);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> discrepancyService.confirm(otherEngineerId, "2026-08", "他法人"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void pendingWarningsは閾値超過かつ未確認だけを全件で返す() {
        insertMonth(24000, "2026-08", "締め済");
        insertContract(380); // 差1200分→超過

        // pendingWarningsはprincipal非依存（scheduler相当）
        AttendanceDiscrepancyDto pending = discrepancyService.pendingWarnings("2026-08");
        assertEquals(1, pending.getItems().size(), "閾値超過・未確認はwarning対象");
        assertTrue(pending.getItems().get(0).isOverThreshold());

        // confirm()経由で確認済みにするとwarning対象外になる
        authenticate(93001L, "管理者");
        discrepancyService.confirm(engineerId, "2026-08", "確認済み");
        AttendanceDiscrepancyDto pending2 = discrepancyService.pendingWarnings("2026-08");
        assertEquals(0, pending2.getItems().size(), "確認済みはwarning対象外");
    }
}
