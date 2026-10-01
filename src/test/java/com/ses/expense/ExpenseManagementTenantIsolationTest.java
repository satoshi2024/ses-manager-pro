package com.ses.expense;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.expense.ExpenseRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 経費管理一覧が、null scopeを跨tenant全件として解釈しないことをHTTP境界で検証する。
 */
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class ExpenseManagementTenantIsolationTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 管理者は現在tenantだけを検索状態ページング詳細markPaidで参照する() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Fixture tenantA = fixture("expense-a-" + suffix, "tenant-a-" + suffix,
                "A専用要員-" + suffix, "会計連携済");
        Fixture tenantB = fixture("expense-b-" + suffix, "tenant-b-" + suffix,
                "B専用要員-" + suffix, "会計連携済");

        mockMvc.perform(get("/api/expense-requests")
                        .param("current", "1").param("size", "1")
                        .with(asAdmin(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].engineerName").value(tenantA.engineerName()));

        // tenant BのengineerNameはSQL検索でも見えず、総件数も0のまま。
        mockMvc.perform(get("/api/expense-requests")
                        .param("engineerName", tenantB.engineerName())
                        .with(asAdmin(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(0));

        // status条件もtenant条件より後段のJava filterではなく、同じSQL母集団で評価される。
        mockMvc.perform(get("/api/expense-requests")
                        .param("status", "会計連携済")
                        .with(asAdmin(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get("/api/expense-requests/" + tenantB.expenseId())
                        .with(asAdmin(tenantA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/expense-requests/" + tenantB.expenseId() + "/mark-paid")
                        .with(csrf()).with(asAdmin(tenantA)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/expense-requests/" + tenantB.expenseId())
                        .with(asAdmin(tenantB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engineerId").value(tenantB.engineerId()));
    }

    @Test
    void managerは組織外と他tenantを一覧詳細markPaidから除外する() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Fixture manager = fixture("expense-manager-" + suffix, "tenant-a-" + suffix,
                "manager-owner-" + suffix, "下書き");
        Fixture inScope = fixture("expense-in-" + suffix, manager.tenantId(),
                "A組織内要員-" + suffix, "会計連携済");
        Fixture outOfScope = fixture("expense-out-" + suffix, manager.tenantId(),
                "A組織外要員-" + suffix, "会計連携済");
        Fixture otherTenant = fixture("expense-other-" + suffix, "tenant-b-" + suffix,
                "B要員-" + suffix, "会計連携済");
        long organizationId = insertOrganization(suffix);
        long outsideOrganizationId = insertOrganization("outside-" + suffix);
        jdbcTemplate.update("UPDATE t_engineer SET organization_id = ? WHERE id = ?", organizationId,
                inScope.engineerId());
        jdbcTemplate.update("UPDATE t_engineer SET organization_id = ? WHERE id = ?", outsideOrganizationId,
                outOfScope.engineerId());
        jdbcTemplate.update("INSERT INTO t_user_organization "
                        + "(tenant_id, user_id, organization_id, primary_flag, valid_from, valid_to) "
                        + "VALUES (?, ?, ?, 1, '2020-01-01', NULL)",
                manager.tenantId(), manager.userId(), organizationId);

        mockMvc.perform(get("/api/expense-requests")
                        .with(asManager(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].engineerName").value(
                        org.hamcrest.Matchers.hasItem(inScope.engineerName())))
                .andExpect(jsonPath("$.data.records[*].engineerName").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(outOfScope.engineerName()))))
                .andExpect(jsonPath("$.data.records[*].engineerName").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(otherTenant.engineerName()))));

        mockMvc.perform(get("/api/expense-requests/" + outOfScope.expenseId())
                        .with(asManager(manager)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/expense-requests/" + otherTenant.expenseId())
                        .with(asManager(manager)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/expense-requests/" + outOfScope.expenseId() + "/mark-paid")
                        .with(csrf()).with(asManager(manager)))
                .andExpect(status().isNotFound());
    }

    private Fixture fixture(String username, String tenantId, String engineerName, String status) {
        jdbcTemplate.update("INSERT INTO sys_user "
                        + "(username, password, real_name, role, tenant_id, status) VALUES (?, 'x', ?, ?, ?, 1)",
                username, username, "管理者", tenantId);
        long userId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        jdbcTemplate.update("INSERT INTO t_engineer "
                        + "(tenant_id, full_name, employment_type, status, created_by) "
                        + "VALUES (?, ?, '正社員', 'Bench', ?)",
                tenantId, engineerName, userId);
        long engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?",
                Long.class, engineerName);
        // 共有H2で同一IDのlegacy linkが残っている場合も、今回の一意なownerを明示する。
        jdbcTemplate.update("DELETE FROM t_engineer_account_link WHERE engineer_id = ? OR sys_user_id = ?",
                engineerId, userId);
        jdbcTemplate.update("INSERT INTO t_engineer_account_link (tenant_id, engineer_id, sys_user_id) VALUES (?, ?, ?)",
                tenantId, engineerId, userId);
        String expenseNo = "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbcTemplate.update("INSERT INTO t_expense_request "
                        + "(engineer_id, expense_no, expense_date, category, amount, status, version) "
                        + "VALUES (?, ?, ?, '交通費', 1000, ?, 0)",
                engineerId, expenseNo, LocalDate.of(2026, 8, 1), status);
        long expenseId = jdbcTemplate.queryForObject("SELECT id FROM t_expense_request WHERE expense_no = ?",
                Long.class, expenseNo);
        return new Fixture(userId, engineerId, expenseId, tenantId, engineerName);
    }

    private long insertOrganization(String suffix) {
        String code = "EXP-" + suffix;
        jdbcTemplate.update("INSERT INTO m_organization_unit "
                        + "(tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                        + "VALUES (1, 70001, ?, ?, '部門', '2020-01-01', '有効')",
                code, code);
        return jdbcTemplate.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
    }

    private RequestPostProcessor asAdmin(Fixture fixture) {
        return asPrincipal(fixture, "管理者");
    }

    private RequestPostProcessor asManager(Fixture fixture) {
        return asPrincipal(fixture, "マネージャー");
    }

    private RequestPostProcessor asPrincipal(Fixture fixture, String role) {
        SysUser user = new SysUser();
        user.setId(fixture.userId());
        user.setUsername("expense-test-" + fixture.userId());
        user.setRole(role);
        user.setTenantId(fixture.tenantId());
        user.setStatus(1);
        LoginUser principal = new LoginUser(user,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null,
                principal.getAuthorities());
        return authentication(auth);
    }

    private record Fixture(long userId, long engineerId, long expenseId,
                           String tenantId, String engineerName) {
    }
}
