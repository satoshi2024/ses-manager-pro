package com.ses.integration;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.WorkRecordGridDto;
import com.ses.dto.workrecord.PendingApprovalItemDto;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 実MySQLで勤怠のtenant母集団とversion CASを検証する。 */
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class WorkRecordTenantIsolationMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_work_record_tenant")
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

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private WorkRecordMapper workRecordMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void MySQLの勤怠母集団とCASはtenant境界を維持する() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Fixture tenantA = fixture("tenant-a-" + suffix, "A", "提出済");
        Fixture tenantB = fixture("tenant-b-" + suffix, "B", "提出済");

        List<WorkRecordGridDto> grid = workRecordMapper.selectMonthlyGrid(
                tenantA.workMonth(), tenantA.monthEnd(), tenantA.tenantId());
        assertThat(grid).extracting(WorkRecordGridDto::getContractId)
                .containsExactly(tenantA.contractId());

        Page<PendingApprovalItemDto> pending = workRecordMapper.selectPendingApprovalPage(
                new Page<>(1, 20), tenantA.workMonth(), tenantA.monthEnd(), tenantA.tenantId());
        assertThat(pending.getTotal()).isEqualTo(1);
        assertThat(pending.getRecords()).extracting(PendingApprovalItemDto::getContractId)
                .containsExactly(tenantA.contractId());
        assertThat(workRecordMapper.selectByIdForTenant(tenantB.workRecordId(), tenantA.tenantId()))
                .isNull();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> confirmCas(tenantA, ready, start));
            Future<Integer> second = executor.submit(() -> confirmCas(tenantA, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(1, 0);
        } finally {
            executor.shutdownNow();
        }

        assertThat(workRecordMapper.selectByIdForTenant(tenantA.workRecordId(), tenantA.tenantId())
                .getStatus()).isEqualTo("確定");
        assertThat(workRecordMapper.selectByIdForTenant(tenantB.workRecordId(), tenantA.tenantId()))
                .isNull();
    }

    private int confirmCas(Fixture fixture, CountDownLatch ready, CountDownLatch start) {
        return AccountingTenantContextHolder.runWithTenant(fixture.tenantId(), () ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    ready.countDown();
                    await(start);
                    return workRecordMapper.updateToConfirmedForTenant(
                            fixture.workRecordId(), 0, null, null, fixture.tenantId());
                }));
    }

    private Fixture fixture(String tenantId, String label, String workRecordStatus) {
        String username = "wrm-" + tenantId;
        jdbcTemplate.update("INSERT INTO sys_user "
                        + "(username, password, real_name, role, tenant_id, status, deleted_flag) "
                        + "VALUES (?, 'x', ?, '管理者', ?, 1, 0)",
                username, "勤怠管理" + label, tenantId);
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        jdbcTemplate.update("INSERT INTO m_customer (tenant_id, company_name, deleted_flag) "
                        + "VALUES (?, ?, 0)", tenantId, "勤怠顧客" + label);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "勤怠顧客" + label);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status, deleted_flag) "
                        + "VALUES (?, ?, '募集中', 0)", "勤怠案件" + label, customerId);
        long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "勤怠案件" + label);
        jdbcTemplate.update("INSERT INTO t_engineer "
                        + "(tenant_id, full_name, employment_type, status, created_by, deleted_flag) "
                        + "VALUES (?, ?, '正社員', '稼動中', ?, 0)", tenantId, "勤怠要員" + label, userId);
        long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "勤怠要員" + label);
        jdbcTemplate.update("INSERT INTO t_contract "
                        + "(tenant_id, contract_no, engineer_id, project_id, customer_id, contract_type, "
                        + "start_date, selling_price, cost_price, status, created_by, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, ?, '準委任', ?, 600000, 400000, '稼動中', ?, 0)",
                tenantId, "WR-" + label + "-" + tenantId, engineerId, projectId, customerId,
                LocalDate.of(2026, 1, 1), userId);
        long contractId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_contract WHERE engineer_id = ? AND customer_id = ?",
                Long.class, engineerId, customerId);
        String month = "2026-08";
        jdbcTemplate.update("INSERT INTO t_work_record "
                        + "(contract_id, work_month, actual_hours, billing_amount, payment_amount, status, version) "
                        + "VALUES (?, ?, 160, 600000, 400000, ?, 0)",
                contractId, month, workRecordStatus);
        long workRecordId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_work_record WHERE contract_id = ? AND work_month = ?",
                Long.class, contractId, month);
        return new Fixture(tenantId, month, month + "-31", contractId, workRecordId);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("CAS開始待ちがタイムアウトしました");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CASテストが割り込まれました", e);
        }
    }

    private record Fixture(String tenantId, String workMonth, String monthEnd,
                           long contractId, long workRecordId) {
    }
}
