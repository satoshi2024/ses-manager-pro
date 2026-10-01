package com.ses.service.impl;

import com.ses.entity.WorkRecord;
import com.ses.mapper.MonthlyClosingMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.WorkRecordService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF02 P0: 実MySQLで tenant-A 締めと tenant-B 勤怠保存の同時実行が
 * デッドロック・横断更新を起こさないことを検証する。
 */
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class MonthlyClosingTenantConcurrencyMySqlTest {

    private static final String MONTH = "2026-08";

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_monthly_closing_tenant")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired private MonthlyClosingService monthlyClosingService;
    @Autowired private WorkRecordService workRecordService;
    @Autowired private MonthlyClosingMapper monthlyClosingMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 外部会計照合は本テストの境界外。同時実行のtenantロック境界に集中する。 */
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.ses.service.accounting.AccountingReconciliationService accountingReconciliationService;

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void MySQLでA締めとB保存はデッドロックせず横断しない() throws Exception {
        org.mockito.Mockito.doNothing().when(accountingReconciliationService)
                .assertReconciledForClosing(org.mockito.ArgumentMatchers.anyString());
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String tenantA = "tenant-a-" + suffix;
        String tenantB = "tenant-b-" + suffix;
        fixture(tenantA, "A", false);
        Fixture b = fixture(tenantB, "B", true);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> aErr = new AtomicReference<>();
        AtomicReference<Throwable> bErr = new AtomicReference<>();
        AtomicReference<WorkRecord> bSaved = new AtomicReference<>();
        try {
            Future<?> aFuture = exec.submit(() -> {
                ready.countDown();
                await(ready);
                await(go);
                try {
                    AccountingTenantContextHolder.runWithTenant(tenantA, () ->
                            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                                    monthlyClosingService.confirmClosing(MONTH, 1L, "管理者")));
                } catch (Throwable t) {
                    aErr.set(t);
                } finally {
                    AccountingTenantContextHolder.clear();
                }
            });
            Future<?> bFuture = exec.submit(() -> {
                ready.countDown();
                await(ready);
                await(go);
                try {
                    AccountingTenantContextHolder.runWithTenant(tenantB, () ->
                            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                                    bSaved.set(workRecordService.saveHours(
                                            b.contractId(), MONTH, new BigDecimal("171.00"), "B並走", 0))));
                } catch (Throwable t) {
                    bErr.set(t);
                } finally {
                    AccountingTenantContextHolder.clear();
                }
            });
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            aFuture.get(60, TimeUnit.SECONDS);
            bFuture.get(60, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }

        assertThat(aErr.get()).as("A confirm error").isNull();
        assertThat(bErr.get()).as("B save error").isNull();
        assertThat(bSaved.get()).isNotNull();
        assertThat(bSaved.get().getActualHours()).isEqualByComparingTo("171.00");

        AccountingTenantContextHolder.runWithTenant(tenantA, () ->
                assertThat(monthlyClosingService.isClosed(MONTH)).isTrue());
        AccountingTenantContextHolder.runWithTenant(tenantB, () ->
                assertThat(monthlyClosingService.isClosed(MONTH)).isFalse());

        assertThat(monthlyClosingMapper.selectByTenantAndMonth(tenantA, MONTH).getConfirmedAt()).isNotNull();
        var bRow = monthlyClosingMapper.selectByTenantAndMonth(tenantB, MONTH);
        assertThat(bRow == null || bRow.getConfirmedAt() == null).isTrue();
    }

    private Fixture fixture(String tenantId, String label, boolean withOpenWorkRecord) {
        jdbcTemplate.update("INSERT INTO m_customer (tenant_id, company_name, deleted_flag) VALUES (?, ?, 0)",
                tenantId, "締顧客" + label + tenantId);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "締顧客" + label + tenantId);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status, deleted_flag) VALUES (?, ?, '募集中', 0)",
                "締案件" + label + tenantId, customerId);
        long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "締案件" + label + tenantId);
        jdbcTemplate.update(
                "INSERT INTO t_engineer (tenant_id, full_name, employment_type, status, deleted_flag) "
                        + "VALUES (?, ?, '正社員', 'Bench', 0)",
                tenantId, "締要員" + label + tenantId);
        long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "締要員" + label + tenantId);
        jdbcTemplate.update(
                "INSERT INTO t_contract (tenant_id, contract_no, engineer_id, project_id, customer_id, "
                        + "start_date, end_date, selling_price, cost_price, status, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 600000, 300000, ?, 0)",
                tenantId, "CL-" + label + "-" + tenantId, engineerId, projectId, customerId,
                LocalDate.of(2026, 1, 1),
                withOpenWorkRecord ? null : LocalDate.of(2026, 7, 31),
                withOpenWorkRecord ? "稼動中" : "終了");
        long contractId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_contract WHERE contract_no = ?", Long.class, "CL-" + label + "-" + tenantId);
        if (withOpenWorkRecord) {
            jdbcTemplate.update(
                    "INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status, version) "
                            + "VALUES (?, ?, 160.00, 600000, '入力中', 0)",
                    contractId, MONTH);
        }
        return new Fixture(tenantId, contractId);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("同時実行待ちがタイムアウトしました");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record Fixture(String tenantId, long contractId) {}
}
