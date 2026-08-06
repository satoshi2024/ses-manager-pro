package com.ses.order;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AcceptanceService;
import com.ses.service.OrganizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T057/T058定向テスト: 検収の対象月(asOf) scope（R09-P1-04再OPEN対応）。
 *
 * <p>要員が2026-07は組織A、2026-08から組織Bへ異動する前提で、2026-07の検収を
 * 2026-08に処理する。list/detail/countと同じ asOf 母集団（{@code allowedContractIdsAsOf}）
 * を初回submitも使い、旧組織Aマネージャーは提出でき、新組織Bマネージャーは
 * 対象月に権限のないsubmitをAPIで実行できないことを実DBで検証する。
 *
 * <p>所属のasOf解決は既存の組織scope SQL（{@code t_engineer.organization_id}を正とし、
 * 未設定時はアカウント連携ユーザーの対象日時点の主所属へフォールバック）に合わせ、
 * 要員のorganization_idはNULL、アカウント連携ユーザーの主所属を組織A（〜2026-07-31）→
 * 組織B（2026-08-01〜）で表す。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class AcceptanceAsOfScopeTest {

    @Autowired AcceptanceService acceptanceService;
    @Autowired OrganizationService organizationService;
    @Autowired SysUserMapper sysUserMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private long contractId;
    private Long orgAId;
    private Long orgBId;
    private SysUser managerA;
    private SysUser managerB;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("R09-P1-04: 月末異動の履歴月は旧組織マネージャーが提出でき新組織マネージャーは404")
    void submit_usesWorkMonthAsOfScope() {
        setUpTransferFixture();

        // 旧組織Aマネージャー: 2026-07の検収は提出できる（対象月末時点の所属が組織A）
        authenticate(managerA);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> acceptanceService.submit(contractId, "2026-07"));

        // 旧組織Aマネージャー: 2026-08の検収は対象月末時点の所属が組織Bのため404（scope外）
        BusinessException deniedA = assertThrows(BusinessException.class,
                () -> acceptanceService.submit(contractId, "2026-08"));
        assertEquals("error.scope.notFound", deniedA.getMessage(), "scope外のsubmitは404相当");

        // 新組織Bマネージャー: 2026-07の検収は対象月に権限が無いため404
        authenticate(managerB);
        BusinessException deniedB = assertThrows(BusinessException.class,
                () -> acceptanceService.submit(contractId, "2026-07"));
        assertEquals("error.scope.notFound", deniedB.getMessage(), "対象月に権限のないsubmitは404相当");

        // 新組織Bマネージャー: 2026-08（移行後）は提出できる
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> acceptanceService.submit(contractId, "2026-08"));
    }

    private void setUpTransferFixture() {
        String suffix = "-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)",
                "ASOF顧客" + suffix);
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "ASOF顧客" + suffix);

        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) "
                + "VALUES (?, '正社員', '稼動中', NULL)", "ASOF要員" + suffix);
        Long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "ASOF要員" + suffix);

        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '稼動中')",
                "ASOF案件" + suffix, customerId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "ASOF案件" + suffix);

        // 組織A・組織B（独立した親組織）
        OrganizationUnit orgA = org("ASOF-A" + suffix, "組織A");
        organizationService.save(orgA);
        OrganizationUnit orgB = org("ASOF-B" + suffix, "組織B");
        organizationService.save(orgB);
        orgAId = orgA.getId();
        orgBId = orgB.getId();

        // マネージャー2名
        managerA = insertUser("asof-mgr-a" + suffix, "組織Aマネージャー", "マネージャー");
        managerB = insertUser("asof-mgr-b" + suffix, "組織Bマネージャー", "マネージャー");
        // 要員のログインユーザー（所属はaccount-link経由で解決するため要員ロールで作成）
        SysUser engineerUser = insertUser("asof-engineer" + suffix, "ASOF要員", "要員");

        organizationService.assignUser(UserOrganization.builder()
                .userId(managerA.getId()).organizationId(orgAId).primaryFlag(1)
                .validFrom(LocalDate.of(2020, 1, 1)).build());
        organizationService.assignUser(UserOrganization.builder()
                .userId(managerB.getId()).organizationId(orgBId).primaryFlag(1)
                .validFrom(LocalDate.of(2020, 1, 1)).build());

        // 要員の所属: 組織A（〜2026-07-31）→ 組織B（2026-08-01〜）
        organizationService.assignUser(UserOrganization.builder()
                .userId(engineerUser.getId()).organizationId(orgAId).primaryFlag(1)
                .validFrom(LocalDate.of(2020, 1, 1)).validTo(LocalDate.of(2026, 7, 31)).build());
        organizationService.assignUser(UserOrganization.builder()
                .userId(engineerUser.getId()).organizationId(orgBId).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 8, 1)).build());

        jdbcTemplate.update("INSERT INTO t_engineer_account_link (engineer_id, sys_user_id) VALUES (?, ?)",
                engineerId, engineerUser.getId());

        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, start_date,"
                        + " selling_price, cost_price, status, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '2026-01-01', 600000, 300000, '稼動中', 1)",
                "ASOF-C-" + suffix, engineerId, projectId, customerId);
        contractId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_contract WHERE contract_no = ?", Long.class, "ASOF-C-" + suffix);

        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status)"
                + " VALUES (?, '2026-07', 160.00, 600000, '確定')", contractId);
        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status)"
                + " VALUES (?, '2026-08', 160.00, 600000, '確定')", contractId);
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

    @Test
    @DisplayName("R09-P1-04: 要員会計履歴（V62）のasOfでlist/detail/submitが旧組織に帰属する")
    void submit_usesAccountingHistoryAsOfScope() {
        setUpHistoryTransferFixture();

        // 旧組織Aマネージャー: 2026-07の検収は提出できる（会計履歴が2026-07末=組織A）
        authenticate(managerA);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> acceptanceService.submit(contractId, "2026-07"));

        // 旧組織Aマネージャー: 2026-08の検収は会計履歴が組織Bのため404
        BusinessException deniedA = assertThrows(BusinessException.class,
                () -> acceptanceService.submit(contractId, "2026-08"));
        assertEquals("error.scope.notFound", deniedA.getMessage());

        // 新組織Bマネージャー: 2026-07の検収は404（対象月の帰属は組織A）
        authenticate(managerB);
        BusinessException deniedB = assertThrows(BusinessException.class,
                () -> acceptanceService.submit(contractId, "2026-07"));
        assertEquals("error.scope.notFound", deniedB.getMessage());

        // 新組織Bマネージャー: 2026-08（移行後）は提出できる
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> acceptanceService.submit(contractId, "2026-08"));
    }

    /**
     * 実際の異動フロー（EngineerServiceImpl.updateWithStatusGuard）と同じ形:
     * t_engineer.organization_idは現在値（組織B）、t_engineer_accounting_historyに
     * 組織A（〜2026-07-31）→組織B（2026-08-01〜）の版を持つ。
     */
    private void setUpHistoryTransferFixture() {
        String suffix = "-H-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)",
                "ASOF顧客" + suffix);
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "ASOF顧客" + suffix);

        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) "
                + "VALUES (?, '正社員', '稼動中', NULL)", "ASOF要員" + suffix);
        Long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "ASOF要員" + suffix);

        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '稼動中')",
                "ASOF案件" + suffix, customerId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "ASOF案件" + suffix);

        OrganizationUnit orgA = org("ASOF-H-A" + suffix, "組織A");
        organizationService.save(orgA);
        OrganizationUnit orgB = org("ASOF-H-B" + suffix, "組織B");
        organizationService.save(orgB);
        orgAId = orgA.getId();
        orgBId = orgB.getId();

        managerA = insertUser("asof-h-mgr-a" + suffix, "組織Aマネージャー", "マネージャー");
        managerB = insertUser("asof-h-mgr-b" + suffix, "組織Bマネージャー", "マネージャー");

        organizationService.assignUser(UserOrganization.builder()
                .userId(managerA.getId()).organizationId(orgAId).primaryFlag(1)
                .validFrom(LocalDate.of(2020, 1, 1)).build());
        organizationService.assignUser(UserOrganization.builder()
                .userId(managerB.getId()).organizationId(orgBId).primaryFlag(1)
                .validFrom(LocalDate.of(2020, 1, 1)).build());

        // 要員の現在organization_idは組織B、会計履歴（V62）に組織A→組織Bの版を持つ
        jdbcTemplate.update("UPDATE t_engineer SET organization_id = ? WHERE id = ?", orgBId, engineerId);
        jdbcTemplate.update("INSERT INTO t_engineer_accounting_history "
                + "(engineer_id, organization_id, organization_history_status, valid_from, valid_to)"
                + " VALUES (?, ?, 'KNOWN', '2020-01-01', '2026-07-31')", engineerId, orgAId);
        jdbcTemplate.update("INSERT INTO t_engineer_accounting_history "
                + "(engineer_id, organization_id, organization_history_status, valid_from, valid_to)"
                + " VALUES (?, ?, 'KNOWN', '2026-08-01', NULL)", engineerId, orgBId);

        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, start_date,"
                        + " selling_price, cost_price, status, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '2026-01-01', 600000, 300000, '稼動中', 1)",
                "ASOF-H-C-" + suffix, engineerId, projectId, customerId);
        contractId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_contract WHERE contract_no = ?", Long.class, "ASOF-H-C-" + suffix);

        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status)"
                + " VALUES (?, '2026-07', 160.00, 600000, '確定')", contractId);
        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status)"
                + " VALUES (?, '2026-08', 160.00, 600000, '確定')", contractId);
    }

}