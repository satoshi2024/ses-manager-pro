package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.closing.MonthlyClosingSummaryDto;
import com.ses.entity.WorkRecord;
import com.ses.mapper.MonthlyClosingMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.WorkRecordService;
import com.ses.service.accounting.AccountingReconciliationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * NF02 P0: 月次締めの tenant 境界（H2）。
 * A が締め済でも B は同月を更新でき、summary / isClosed は横断しない。
 */
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
@com.ses.test.DisableDefaultTenantTestContext
class MonthlyClosingTenantIsolationH2Test {

    private static final String MONTH = "2026-08";

    @Autowired private MonthlyClosingService monthlyClosingService;
    @Autowired private WorkRecordService workRecordService;
    @Autowired private MonthlyClosingMapper monthlyClosingMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 外部会計照合は本テストの境界外。締め本体のtenant隔離に集中する。 */
    @MockBean
    private AccountingReconciliationService accountingReconciliationService;

    private Fixture tenantA;
    private Fixture tenantB;

    @BeforeEach
    void setUp() {
        doNothing().when(accountingReconciliationService).assertReconciledForClosing(anyString());
        tenantA = fixture("tenant-a", "A");
        tenantB = fixture("tenant-b", "B");
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void Aが締め後もBは同月更新可能でsummaryは横断しない() {
        // A は契約なし → readyToClose。B は未確定勤怠あり。
        AccountingTenantContextHolder.runWithTenant("tenant-a", () -> {
            monthlyClosingService.confirmClosing(MONTH, 1L, "管理者");
            assertThat(monthlyClosingService.isClosed(MONTH)).isTrue();
            MonthlyClosingSummaryDto summaryA = monthlyClosingService.summary(MONTH);
            assertThat(summaryA.isClosed()).isTrue();
            assertThat(summaryA.getUnconfirmedCount()).isZero();
        });

        AccountingTenantContextHolder.runWithTenant("tenant-b", () -> {
            assertThat(monthlyClosingService.isClosed(MONTH)).isFalse();
            MonthlyClosingSummaryDto summaryB = monthlyClosingService.summary(MONTH);
            assertThat(summaryB.isClosed()).isFalse();
            assertThat(summaryB.getUnconfirmedCount()).isEqualTo(1);
            assertThat(summaryB.getUnconfirmedRecords())
                    .extracting(r -> r.getContractId())
                    .containsExactly(tenantB.contractId);

            WorkRecord saved = workRecordService.saveHours(
                    tenantB.contractId, MONTH, new BigDecimal("170.00"), "B更新", 0);
            assertThat(saved.getActualHours()).isEqualByComparingTo("170.00");
        });

        // NULL tenant 行はどの明示tenantからも読めず、ensure/CASでも触れない。
        jdbcTemplate.update(
                "INSERT INTO t_monthly_closing (tenant_id, work_month, confirmed_by, confirmed_at, version) "
                        + "VALUES (NULL, ?, 9, ?, 0)",
                MONTH, LocalDateTime.of(2026, 8, 1, 0, 0));
        AccountingTenantContextHolder.runWithTenant("tenant-a", () -> {
            assertThat(monthlyClosingMapper.selectByTenantAndMonth("tenant-a", MONTH).getConfirmedBy())
                    .isEqualTo(1L);
            assertThat(monthlyClosingService.isClosed(MONTH)).isTrue();
        });
        AccountingTenantContextHolder.runWithTenant("tenant-b", () ->
                assertThat(monthlyClosingService.isClosed(MONTH)).isFalse());

        AccountingTenantContextHolder.clear();
        assertThatThrownBy(() -> monthlyClosingService.isClosed(MONTH))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TENANT_CONTEXT_REQUIRED");
        assertThatThrownBy(() -> monthlyClosingService.assertOpenForUpdate(MONTH))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TENANT_CONTEXT_REQUIRED");
    }

    private Fixture fixture(String tenantId, String label) {
        jdbcTemplate.update(
                "INSERT INTO m_customer (company_name, trust_level, tenant_id, deleted_flag) VALUES (?, 'B', ?, 0)",
                "MC顧客" + label, tenantId);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "MC顧客" + label);
        jdbcTemplate.update(
                "INSERT INTO t_engineer (full_name, employment_type, tenant_id, status) VALUES (?, '正社員', ?, 'Bench')",
                "MC要員" + label, tenantId);
        long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "MC要員" + label);
        jdbcTemplate.update(
                "INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')",
                "MC案件" + label, customerId);
        long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "MC案件" + label);
        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, tenant_id, start_date,"
                        + " selling_price, cost_price, status)"
                        + " VALUES (?, ?, ?, ?, ?, '2026-01-01', 600000, 300000, '稼動中')",
                "MC-" + label, engineerId, projectId, customerId, tenantId);
        long contractId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_contract WHERE contract_no = ?", Long.class, "MC-" + label);

        // tenant-a は締め可能にするため契約を作らない経路でもよいが、
        // summary 横断検証のため B のみ未確定勤怠を持つ。A の契約は締めで未入力扱いにならないよう削除相当にする。
        if ("tenant-b".equals(tenantId)) {
            jdbcTemplate.update(
                    "INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status, version)"
                            + " VALUES (?, ?, 160.00, 600000, '入力中', 0)",
                    contractId, MONTH);
        } else {
            // A: 対象月の契約を持たない（締め可能）ため contract は残すが start/end を対象月外にする必要はない。
            // selectMonthlyGrid は稼動中契約を未入力として返すため、A の契約は対象月に含めないよう終了させる。
            jdbcTemplate.update(
                    "UPDATE t_contract SET status = '終了', end_date = '2026-07-31' WHERE id = ?",
                    contractId);
        }
        return new Fixture(tenantId, contractId);
    }

    private record Fixture(String tenantId, long contractId) {}
}
