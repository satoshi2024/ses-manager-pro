package com.ses.service.attendance.overtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.attendance.overtime.OvertimeComplianceFinding;
import com.ses.dto.attendance.overtime.OvertimeRule;
import com.ses.entity.OvertimeAgreement;
import com.ses.entity.OvertimeFollowup;
import com.ses.mapper.OvertimeAgreementMapper;
import com.ses.mapper.OvertimeFollowupMapper;
import com.ses.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * S11-P0-01: OvertimeComplianceCalculatorを月次締め配線へ接続した回帰。
 * 月&gt;45h→RULE1、年&gt;360h→RULE2、特別条項rolling平均→RULE5、協定なし→INDETERMINATE。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OvertimeComplianceWiringTest {

    private static final YearMonth TARGET = YearMonth.of(2026, 8);
    private static final int MONTH_LIMIT = OvertimeLimitDefaults.MONTH_NORMAL_MINUTES; // 2700
    private static final int YEAR_LIMIT = OvertimeLimitDefaults.YEAR_NORMAL_MINUTES; // 21600
    private static final int AVG_LIMIT = OvertimeLimitDefaults.MULTI_MONTH_AVERAGE_MINUTES; // 4800

    @Autowired
    private OvertimeComplianceService overtimeComplianceService;

    @Autowired
    private OvertimeFollowupMapper overtimeFollowupMapper;

    @Autowired
    private OvertimeAgreementMapper overtimeAgreementMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private NotificationService notificationService;

    private long engineerId;
    private long legalEntityId;
    private long organizationId;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 70001, ?, ?, '部門', '2026-01-01', '有効')",
                "otw-" + suffix, "otw-" + suffix);
        organizationId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_organization_unit WHERE code = ?", Long.class, "otw-" + suffix);
        legalEntityId = 70001L;
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id, overtime_exempt_flag) "
                + "VALUES (?, '正社員', 'Bench', ?, 0)", "otw-eng-" + suffix, organizationId);
        engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "otw-eng-" + suffix);
    }

    @AfterEach
    void tearDown() {
        // @Transactional rollback
    }

    @Test
    void 月次45h超過の締め配線でRULE1_followupと通知が発行される() {
        insertAgreement(false);
        insertMonth(TARGET, MONTH_LIMIT + 1, 0);

        List<OvertimeComplianceFinding> findings =
                overtimeComplianceService.evaluateAndPersist(engineerId, TARGET);

        assertEquals(List.of(OvertimeRule.RULE1_MONTH_NORMAL),
                findings.stream().map(OvertimeComplianceFinding::rule).toList());
        assertFollowup("RULE1_MONTH_NORMAL");
        // @Async通知の完了を待つ（taskExecutor）
        verify(notificationService, timeout(5000).atLeastOnce()).publishToUser(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.eq("OVERTIME_COMPLIANCE"),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void 年次360h超過でRULE2_followupが永続化される() {
        insertAgreement(false);
        // 協定年度 2026-04〜08の5か月で年上限(21600)超: 各4400 → 合計22000
        YearMonth yearStart = YearMonth.of(2026, 4);
        for (YearMonth m = yearStart; !m.isAfter(TARGET); m = m.plusMonths(1)) {
            insertMonth(m, 4400, 0);
        }

        List<OvertimeComplianceFinding> findings =
                overtimeComplianceService.evaluateAndPersist(engineerId, TARGET);

        assertTrue(findings.stream().anyMatch(f -> f.rule() == OvertimeRule.RULE2_YEAR_NORMAL),
                "年累計超過でRULE2が必要: " + findings);
        assertFollowup("RULE2_YEAR_NORMAL");
    }

    @Test
    void 特別条項のrolling平均超過でRULE5_followupが永続化される() {
        insertAgreement(true);
        // 2か月連続で平均80h超（合計 > 4800*2）
        insertMonth(TARGET.minusMonths(1), 0, AVG_LIMIT + 100);
        insertMonth(TARGET, 0, AVG_LIMIT + 100);

        List<OvertimeComplianceFinding> findings =
                overtimeComplianceService.evaluateAndPersist(engineerId, TARGET);

        assertTrue(findings.stream().anyMatch(f -> f.rule() == OvertimeRule.RULE5_MULTI_MONTH_AVERAGE),
                "RULE5が必要: " + findings);
        List<OvertimeFollowup> rows = overtimeFollowupMapper.selectList(new LambdaQueryWrapper<OvertimeFollowup>()
                .eq(OvertimeFollowup::getEngineerId, engineerId)
                .eq(OvertimeFollowup::getPeriodMonth, TARGET.atDay(1))
                .likeRight(OvertimeFollowup::getWarningCode, "RULE5_MULTI_MONTH_AVERAGE"));
        assertTrue(rows.size() >= 1, "RULE5 followupが必要");
    }

    @Test
    void 協定なしはINDETERMINATEで適合扱いにしない() {
        // agreement未登録
        insertMonth(TARGET, MONTH_LIMIT + 1, 0);

        List<OvertimeComplianceFinding> findings =
                overtimeComplianceService.evaluateAndPersist(engineerId, TARGET);

        assertEquals(1, findings.size());
        assertEquals(OvertimeRule.AGREEMENT_MISSING, findings.get(0).rule());
        assertFollowup("AGREEMENT_MISSING");
    }

    @Test
    void agreementYearStartは12か月ブロックで切り替わる() {
        assertEquals(YearMonth.of(2026, 4),
                OvertimeComplianceServiceImpl.agreementYearStart(YearMonth.of(2026, 4), YearMonth.of(2026, 8)));
        assertEquals(YearMonth.of(2027, 4),
                OvertimeComplianceServiceImpl.agreementYearStart(YearMonth.of(2026, 4), YearMonth.of(2027, 5)));
    }

    private void insertAgreement(boolean special) {
        overtimeAgreementMapper.insert(OvertimeAgreement.builder()
                .legalEntityId(legalEntityId)
                .validFrom(LocalDate.of(2026, 4, 1))
                .validTo(null)
                .specialClause(special ? 1 : 0)
                .build());
    }

    private void insertMonth(YearMonth month, int overtimeMinutes, int holidayMinutes) {
        jdbcTemplate.update("INSERT INTO t_attendance_month "
                        + "(engineer_id, legal_entity_id, organization_id, work_month, "
                        + "overtime_minutes, holiday_minutes, status, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, '締め済', 0)",
                engineerId, legalEntityId, organizationId, month.atDay(1),
                overtimeMinutes, holidayMinutes);
    }

    private void assertFollowup(String warningCode) {
        List<OvertimeFollowup> rows = overtimeFollowupMapper.selectList(new LambdaQueryWrapper<OvertimeFollowup>()
                .eq(OvertimeFollowup::getEngineerId, engineerId)
                .eq(OvertimeFollowup::getPeriodMonth, TARGET.atDay(1))
                .eq(OvertimeFollowup::getWarningCode, warningCode));
        assertEquals(1, rows.size(), "followup " + warningCode);
    }
}
