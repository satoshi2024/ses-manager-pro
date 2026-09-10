package com.ses.expense;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.expense.ExpenseRequestService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実MySQLで経費管理一覧のtenant ownershipとmanager scopeを検証する。 */
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ExpenseManagementTenantIsolationMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_expense_tenant")
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
    private ExpenseRequestService expenseRequestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.Set<String> createdUsernames = new java.util.HashSet<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
        for (String username : createdUsernames) {
            Long userId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?",
                    Long.class, username);
            if (userId == null) {
                continue;
            }
            List<Long> engineerIds = jdbcTemplate.queryForList(
                    "SELECT engineer_id FROM t_engineer_account_link WHERE sys_user_id = ?", Long.class, userId);
            jdbcTemplate.update("DELETE FROM t_expense_request WHERE engineer_id IN "
                    + "(SELECT engineer_id FROM t_engineer_account_link WHERE sys_user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM t_engineer_account_link WHERE sys_user_id = ?", userId);
            for (Long engineerId : engineerIds) {
                jdbcTemplate.update("DELETE FROM t_engineer WHERE id = ?", engineerId);
            }
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
        createdUsernames.clear();
    }

    @Test
    void MySQLの管理一覧はtenantをSQLへ固定し検索状態ページング詳細markPaidも越境しない() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Fixture tenantA = fixture("expense-mysql-a-" + suffix, "tenant-a-" + suffix,
                "MySQL-A-" + suffix, "会計連携済");
        Fixture tenantB = fixture("expense-mysql-b-" + suffix, "tenant-b-" + suffix,
                "MySQL-B-" + suffix, "会計連携済");

        Page<ExpenseRequestService.ExpenseRequestDto> page = asAdmin(tenantA,
                () -> expenseRequestService.pageManagement(null, null, 1, 1));
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).extracting(ExpenseRequestService.ExpenseRequestDto::engineerName)
                .containsExactly(tenantA.engineerName());

        Page<ExpenseRequestService.ExpenseRequestDto> searched = asAdmin(tenantA,
                () -> expenseRequestService.pageManagement(tenantB.engineerName(), null, 1, 20));
        assertThat(searched.getTotal()).isZero();

        Page<ExpenseRequestService.ExpenseRequestDto> status = asAdmin(tenantA,
                () -> expenseRequestService.pageManagement(null, "会計連携済", 1, 20));
        assertThat(status.getTotal()).isEqualTo(1);

        assertThatThrownBy(() -> asAdmin(tenantA,
                () -> expenseRequestService.detailManagement(tenantB.expenseId())))
                .isInstanceOf(com.ses.common.exception.BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 404);
        assertThatThrownBy(() -> asAdmin(tenantA,
                () -> expenseRequestService.markPaid(tenantB.expenseId())))
                .isInstanceOf(com.ses.common.exception.BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 404);
    }

    private Fixture fixture(String username, String tenantId, String engineerName, String status) {
        createdUsernames.add(username);
        jdbcTemplate.update("INSERT INTO sys_user "
                        + "(username, password, real_name, role, tenant_id, status) VALUES (?, 'x', ?, '管理者', ?, 1)",
                username, username, tenantId);
        long userId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        jdbcTemplate.update("INSERT INTO t_engineer "
                        + "(tenant_id, full_name, employment_type, status, created_by) "
                        + "VALUES (?, ?, '正社員', 'Bench', ?)",
                tenantId, engineerName, userId);
        long engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?",
                Long.class, engineerName);
        jdbcTemplate.update("INSERT INTO t_engineer_account_link (tenant_id, engineer_id, sys_user_id) VALUES (?, ?, ?)",
                tenantId, engineerId, userId);
        String expenseNo = "MT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbcTemplate.update("INSERT INTO t_expense_request "
                        + "(engineer_id, expense_no, expense_date, category, amount, status, version) "
                        + "VALUES (?, ?, ?, '交通費', 1000, ?, 0)",
                engineerId, expenseNo, LocalDate.of(2026, 8, 1), status);
        long expenseId = jdbcTemplate.queryForObject("SELECT id FROM t_expense_request WHERE expense_no = ?",
                Long.class, expenseNo);
        return new Fixture(userId, engineerId, expenseId, tenantId, engineerName);
    }

    private <T> T asAdmin(Fixture fixture, java.util.function.Supplier<T> action) {
        SysUser user = new SysUser();
        user.setId(fixture.userId());
        user.setUsername(fixture.username());
        user.setRole("管理者");
        user.setTenantId(fixture.tenantId());
        user.setStatus(1);
        LoginUser principal = new LoginUser(user,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return AccountingTenantContextHolder.runWithTenant(fixture.tenantId(), action);
    }

    private record Fixture(long userId, long engineerId, long expenseId,
                           String tenantId, String engineerName) {
        private String username() {
            return "expense-mysql-user-" + userId;
        }
    }
}
