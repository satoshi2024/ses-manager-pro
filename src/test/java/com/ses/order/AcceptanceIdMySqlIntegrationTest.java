package com.ses.order;

import com.ses.config.LoginUser;
import com.ses.entity.Acceptance;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AcceptanceService;
import com.ses.service.OrganizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.ses.test.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 実MySQL 8コンテナ上での定点抽出（acceptanceId）およびScope遮断動作検証（R7-P2-04）。
 * 1) 通常1ページ目外の目標検収が acceptanceId 定点抽出によりWHERE句先行評価で1件取得される。
 * 2) 権限ありマネージャーAは1件、越権マネージャーBは0件（Scope遮断）となる。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class AcceptanceIdMySqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_acceptance_id_mysql")
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

    @Autowired private AcceptanceService acceptanceService;
    @Autowired private OrganizationService organizationService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("実MySQL8: 通常1ページ目外の目標検収が acceptanceId 指定で1件取得され、越権主体では0件となる")
    void mysql_pageGrid_withAcceptanceId_scopeAndBoundaryIntegration() {
        String suffix = "-MYSQL-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)",
                "MYSQL顧客" + suffix);
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "MYSQL顧客" + suffix);

        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) "
                + "VALUES (?, '正社員', '稼動中', NULL)", "MYSQL要員1" + suffix);
        Long eng1Id = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "MYSQL要員1" + suffix);

        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')",
                "MYSQL案件" + suffix, customerId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "MYSQL案件" + suffix);

        OrganizationUnit orgA = org("MYSQL-A" + suffix, "MySQL組織A");
        organizationService.save(orgA);
        OrganizationUnit orgB = org("MYSQL-B" + suffix, "MySQL組織B");
        organizationService.save(orgB);
        Long orgAId = orgA.getId();
        Long orgBId = orgB.getId();

        SysUser managerA = insertUser("mysql-mgr-a" + suffix, "組織Aマネージャー", "マネージャー");
        SysUser managerB = insertUser("mysql-mgr-b" + suffix, "組織Bマネージャー", "マネージャー");
        SysUser engineerUser1 = insertUser("mysql-eng-1" + suffix, "MYSQL要員1", "要員");

        organizationService.assignUser(UserOrganization.builder()
                .userId(managerA.getId()).organizationId(orgAId).primaryFlag(1)
                .validFrom(LocalDate.of(2020, 1, 1)).build());
        organizationService.assignUser(UserOrganization.builder()
                .userId(managerB.getId()).organizationId(orgBId).primaryFlag(1)
                .validFrom(LocalDate.of(2020, 1, 1)).build());

        organizationService.assignUser(UserOrganization.builder()
                .userId(engineerUser1.getId()).organizationId(orgAId).primaryFlag(1)
                .validFrom(LocalDate.of(2020, 1, 1)).build());
        jdbcTemplate.update("INSERT INTO t_engineer_account_link (engineer_id, sys_user_id) VALUES (?, ?)",
                eng1Id, engineerUser1.getId());

        // 契約1（最古、定点抽出ターゲット）を登録・提出
        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, start_date, selling_price, cost_price, status, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '2026-01-01', 600000, 300000, '稼動中', 1)",
                "MYSQL-C1" + suffix, eng1Id, projectId, customerId);
        Long targetContractId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_contract WHERE contract_no = ?", Long.class, "MYSQL-C1" + suffix);
        jdbcTemplate.update(
                "INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status) VALUES (?, '2026-07', 160.00, 600000, '確定')",
                targetContractId);

        authenticate(managerA);
        Acceptance targetAcceptance = acceptanceService.submit(targetContractId, "2026-07");
        assertNotNull(targetAcceptance);

        // 契約2〜5を追加作成
        for (int i = 2; i <= 5; i++) {
            jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) VALUES (?, '正社員', '稼動中', ?)",
                    "MYSQL要員" + i + suffix, orgAId);
            Long engId = jdbcTemplate.queryForObject(
                    "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "MYSQL要員" + i + suffix);
            jdbcTemplate.update(
                    "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, start_date, selling_price, cost_price, status, acceptance_required)"
                            + " VALUES (?, ?, ?, ?, '2026-01-01', 600000, 300000, '稼動中', 1)",
                    "MYSQL-C" + i + suffix, engId, projectId, customerId);
            Long cId = jdbcTemplate.queryForObject(
                    "SELECT id FROM t_contract WHERE contract_no = ?", Long.class, "MYSQL-C" + i + suffix);
            jdbcTemplate.update(
                    "INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status) VALUES (?, '2026-07', 160.00, 600000, '確定')",
                    cId);
        }

        // 1. 通常検索（size=2）: 最古のtargetAcceptanceは1ページ目（Top 2）に含まれない
        var normalPage = acceptanceService.pageGrid(1, 2, "2026-07", null, null, null, null);
        assertEquals(2, normalPage.getRecords().size());
        boolean containedInNormal = normalPage.getRecords().stream().anyMatch(r -> targetAcceptance.getId().equals(r.getId()));
        assertFalse(containedInNormal, "通常検索1ページ目（size=2）にはターゲットが含まれないこと");

        // 2. 定点抽出（マネージャーA、権限あり）: 1ページ目外のターゲットが1件正確に返る
        var targetedPage = acceptanceService.pageGrid(1, 2, "2026-07", null, null, null, targetAcceptance.getId());
        assertEquals(1, targetedPage.getRecords().size());
        assertEquals(targetAcceptance.getId(), targetedPage.getRecords().get(0).getId());

        // 3. 定点抽出（マネージャーB、越権主体）: 実在する同検収IDを指定しても0件（Scope遮断）
        authenticate(managerB);
        var unauthorizedPage = acceptanceService.pageGrid(1, 2, "2026-07", null, null, null, targetAcceptance.getId());
        assertEquals(0, unauthorizedPage.getRecords().size(), "実MySQL上でも越権マネージャーBからは0件（Scope遮断）となること");
    }

    private OrganizationUnit org(String code, String name) {
        return OrganizationUnit.builder()
                .code(code).name(name).type("部").parentId(null)
                .validFrom(LocalDate.of(2020, 1, 1)).status("有効").version(0).build();
    }

    private SysUser insertUser(String username, String realName, String role) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword("pass");
        user.setRealName(realName);
        user.setRole(role);
        user.setStatus(1);
        sysUserMapper.insert(user);
        return user;
    }

    private void authenticate(SysUser user) {
        LoginUser principal = new LoginUser(user,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
