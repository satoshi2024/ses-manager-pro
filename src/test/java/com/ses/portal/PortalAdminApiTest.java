package com.ses.portal;

import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T086 B1のportal管理/通知/利用規約/監査/return URL/contact失効連動の検証（L2〜L3）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PortalAdminApiTest extends PortalTestSupport {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected com.ses.service.portal.PortalContactInvalidationService invalidationService;
    @Autowired
    protected com.ses.service.AcceptanceService acceptanceService;
    @org.springframework.boot.test.mock.mockito.MockBean
    protected com.ses.service.MailService mailService;
    @Autowired
    protected org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    @Autowired
    protected com.ses.service.SystemConfigService systemConfigService;

    @Override
    protected JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    private String unique() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminUser() {
        return user("admin").roles("管理者");
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor salesUser() {
        return user("sales").roles("営業");
    }

    private long insertCustomerOrg() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "admin-customer-" + unique());
        long customerId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM m_customer", Long.class);
        PortalOrganization org = new PortalOrganization();
        org.setType("CUSTOMER");
        org.setCustomerId(customerId);
        org.setStatus("ACTIVE");
        organizationMapper.insert(org);
        return org.getId();
    }

    private long insertBpOrg() {
        jdbcTemplate.update("INSERT INTO m_bp_company (legal_name, entity_type, status) "
                + "VALUES (?, 'CORPORATE', 'ACTIVE')", "admin-bp-" + unique());
        long bpCompanyId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM m_bp_company", Long.class);
        PortalOrganization org = new PortalOrganization();
        org.setType("BP");
        org.setBpCompanyId(bpCompanyId);
        org.setStatus("ACTIVE");
        organizationMapper.insert(org);
        return org.getId();
    }

    @Test
    void 管理者は組織を管理でき停止で全sessionが失効する() throws Exception {
        long orgId = insertCustomerOrg();
        PortalUser user = createUser(organizationMapper.selectById(orgId), "admin-flow-" + unique() + "@example.com");
        String secret = uniqueSecret();
        enableMfa(user, secret);
        consentTerms(user);
        MockCookie session = issueSession(user);

        // 一覧・作成・停止
        mockMvc.perform(get("/api/portal-admin/orgs").with(adminUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(put("/api/portal-admin/orgs/" + orgId + "/status").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());

        // 停止後: session即時失効（me()が401）
        mockMvc.perform(get("/api/portal/auth/me").cookie(session))
                .andExpect(status().isUnauthorized());
        // org statusがSUSPENDED
        assertEquals("SUSPENDED", jdbcTemplate.queryForObject(
                "SELECT status FROM m_portal_organization WHERE id = ?", String.class, orgId));

        // 再開 → 再loginで使える
        mockMvc.perform(put("/api/portal-admin/orgs/" + orgId + "/status").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 営業は顧客組織のみ参照できBP組織は404になる() throws Exception {
        insertCustomerOrg();
        long bpOrgId = insertBpOrg();

        // 営業: CUSTOMER組織のみ一覧に出る（BPは出ない）
        mockMvc.perform(get("/api/portal-admin/orgs").with(salesUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].type").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("CUSTOMER"))));
        // BP組織のuser一覧・招待発行は404秘匿（招待発行は管理者限定: S13-R1-P2-01 → 営業は403）
        mockMvc.perform(get("/api/portal-admin/orgs/" + bpOrgId + "/users").with(salesUser()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/portal-admin/orgs/" + bpOrgId + "/invitations").with(salesUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isForbidden());
        // 組織作成・MFA reset・規約発行は管理者のみ
        mockMvc.perform(post("/api/portal-admin/orgs").with(salesUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"CUSTOMER\",\"customerId\":1}"))
                .andExpect(status().isForbidden());
        // HR・要員は403
        mockMvc.perform(get("/api/portal-admin/orgs").with(user("hr").roles("HR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/portal-admin/orgs").with(user("member").roles("要員")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 招待発行はtokenをhash保存しメールをDRY_RUNで記録する() throws Exception {
        long orgId = insertCustomerOrg();
        String email = "invite-admin-" + unique() + "@example.com";

        mockMvc.perform(post("/api/portal-admin/orgs/" + orgId + "/invitations").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenHash").doesNotExist())
                .andExpect(jsonPath("$.data.email").value(email));

        // tokenはhashのみ保存
        String tokenHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM t_portal_invitation WHERE email = ?", String.class, email);
        assertNotNull(tokenHash);
        assertEquals(64, tokenHash.length(), "SHA-256 hexのはず");
        // 招待メールがMailService経由で1回送信される（本文にtokenが含まれる）
        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.times(1))
                .send(org.mockito.ArgumentMatchers.eq(email),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.contains("token="),
                        org.mockito.ArgumentMatchers.isNull());

        // 同一emailへの有効な招待の重複は409
        mockMvc.perform(post("/api/portal-admin/orgs/" + orgId + "/invitations").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void MFAリセットで全sessionが失効し再設定が必要になる() throws Exception {
        long orgId = insertCustomerOrg();
        PortalUser user = createUser(organizationMapper.selectById(orgId), "mfa-reset-" + unique() + "@example.com");
        String secret = uniqueSecret();
        enableMfa(user, secret);
        consentTerms(user);
        MockCookie session = issueSession(user);

        mockMvc.perform(get("/api/portal/auth/me").cookie(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/portal-admin/users/" + user.getId() + "/mfa-reset").with(adminUser()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/portal/auth/me").cookie(session))
                .andExpect(status().isUnauthorized());
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT mfa_enabled_at IS NULL FROM t_portal_user WHERE id = ?", Boolean.class, user.getId()),
                "MFA設定がクリアされるはず");
    }

    @Test
    void portal操作が監査ログに記録される() throws Exception {
        // 顧客Aのデータでdownload/検収操作 → access logに記録
        PortalOrganization orgA = createCustomerOrg("audit-" + unique());
        UserFixture userA = readyUser(orgA, "audit-" + unique() + "@example.com");
        jdbcTemplate.update("INSERT INTO t_quotation (quotation_no, customer_id, title, unit_price, status)"
                        + " VALUES (?, ?, '監査テスト', 1000000, '提出済')",
                "AUDIT-QUO-" + unique(), orgA.getCustomerId());
        long quotationId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_quotation", Long.class);

        mockMvc.perform(get("/api/portal/customer/quotations/" + quotationId + "/download")
                        .cookie(userA.sessionCookie()))
                .andExpect(status().isOk());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_portal_access_log WHERE email = ? AND action = 'DOWNLOAD_QUOTATION'",
                Integer.class, userA.user().getEmail());
        assertEquals(1, count, "downloadが監査ログに記録されるはず（R4.2）");
    }

    @Test
    void return_URLは相対パスのみ許可される() throws Exception {
        // 相対パスは保持
        String html = mockMvc.perform(get("/portal/login").param("returnUrl", "/portal/customer"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("/portal/customer"), "相対return URLが保持されるはず");
        // open redirectは既定/portalへ
        String evil = mockMvc.perform(get("/portal/login").param("returnUrl", "//evil.example.com"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(!evil.contains("//evil.example.com"), "外部URLは除去されるはず");
        assertTrue(evil.contains("'/portal'"), "不正値は既定/portalへ");
    }

    @Test
    void 規約version発行で再同意が強制される() throws Exception {
        long orgId = insertCustomerOrg();
        PortalUser user = createUser(organizationMapper.selectById(orgId), "terms-pub-" + unique() + "@example.com");
        String secret = uniqueSecret();
        enableMfa(user, secret);
        consentTerms(user);
        MockCookie session = issueSession(user);

        mockMvc.perform(get("/api/portal/auth/me").cookie(session))
                .andExpect(jsonPath("$.data.termsPending").value(false));

        // 現行より古いversionは拒否
        mockMvc.perform(put("/api/portal-admin/terms").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1\"}"))
                .andExpect(status().isConflict());
        try {
            // version 2を発行。SystemConfigServiceのキャッシュ失効はcommit後（afterCommit）のため、
            // REQUIRES_NEWでコミットしてから検証する。最後に1へ戻す。
            transactionTemplate.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transactionTemplate.executeWithoutResult(status -> {
                try {
                    mockMvc.perform(put("/api/portal-admin/terms").with(adminUser()).with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"2\"}"))
                            .andExpect(status().isOk());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            // コミット後のキャッシュ再読込でversion 2が見える
            mockMvc.perform(get("/api/portal-admin/terms").with(adminUser()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.version").value("2"));

            // 全sessionで再同意が強制される（me()はtermsPending、/portalはtermsへ）
            mockMvc.perform(get("/api/portal/auth/me").cookie(session))
                    .andExpect(jsonPath("$.data.termsPending").value(true));
            mockMvc.perform(get("/portal").cookie(session))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/portal/terms"));
        } finally {
            transactionTemplate.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transactionTemplate.executeWithoutResult(status ->
                    systemConfigService.put("portal.terms.current-version", "1", "test"));
        }
    }

    @Test
    void 担当者失効連動でportal_accessが失効する() throws Exception {
        // 顧客Aに担当者（有効）→ portal user登録
        PortalOrganization orgA = createCustomerOrg("inv-" + unique());
        String email = "contact-" + unique() + "@example.com";
        jdbcTemplate.update("INSERT INTO t_customer_contact (customer_id, name, email, status, valid_from) "
                + "VALUES (?, '担当者', ?, '有効', '2026-01-01')", orgA.getCustomerId(), email);
        long contactId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_customer_contact", Long.class);
        PortalUser user = createUser(orgA, email);
        String secret = uniqueSecret();
        enableMfa(user, secret);
        consentTerms(user);
        MockCookie session = issueSession(user);
        mockMvc.perform(get("/api/portal/auth/me").cookie(session)).andExpect(status().isOk());

        // 担当者を退職させる（R1.5）→ バッチ実行 → portal access失効
        jdbcTemplate.update("UPDATE t_customer_contact SET status = '退職', valid_to = '2026-01-31' WHERE id = ?",
                contactId);
        int invalidated = invalidationService.invalidateByContacts();
        assertEquals(1, invalidated, "担当者emailと一致するportal userが停止されるはず");
        mockMvc.perform(get("/api/portal/auth/me").cookie(session))
                .andExpect(status().isUnauthorized());
        assertEquals("SUSPENDED", jdbcTemplate.queryForObject(
                "SELECT status FROM t_portal_user WHERE id = ?", String.class, user.getId()));
    }

    @Test
    void 検収提出で顧客組織へ通知され重複送信は抑止される() throws Exception {
        // 顧客A: portal user（通知宛先）＋通知オフのuser（P1-03: notify_email=0は送信しない）
        PortalOrganization orgA = createCustomerOrg("ntf-" + unique());
        UserFixture userA = readyUser(orgA, "ntf-" + unique() + "@example.com");
        PortalUser noNotify = createUser(orgA, "ntf-off-" + unique() + "@example.com");
        jdbcTemplate.update("UPDATE t_portal_user SET notify_email = 0 WHERE id = ?", noNotify.getId());

        // 契約・勤怠・検収を用意し、内部submitで通知が発火する
        long engineerId = insertEngineer();
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')",
                "ntf-project-" + unique(), orgA.getCustomerId());
        long projectId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_project", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, status,"
                        + " start_date, end_date, selling_price, cost_price, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '稼動中', '2026-01-01', '2026-12-31', 900000, 600000, 1)",
                "NTF-C-" + unique(), engineerId, projectId, orgA.getCustomerId());
        long contractId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_contract", Long.class);
        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount,"
                        + " payment_amount, status) VALUES (?, '2026-07', 160, 900000, 600000, '確定')",
                contractId);

        com.ses.service.AcceptanceService acceptanceService = this.acceptanceService;
        acceptanceService.submit(contractId, "2026-07");

        // 検収提出通知がMailService経由で顧客組織のACTIVE userへ送信される（R4.1）
        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.times(1))
                .send(org.mockito.ArgumentMatchers.eq(userA.user().getEmail()),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.isNull());
        // notify_email=0のuserへは送信されない（P1-03: 通知設定）
        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.never())
                .send(org.mockito.ArgumentMatchers.eq(noNotify.getEmail()),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.isNull());

        // 2件目のsubmit（別契約）は同日の重複として抑止される（type+org+日）
        long engineerId2 = insertEngineer();
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')",
                "ntf-project2-" + unique(), orgA.getCustomerId());
        long projectId2 = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_project", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, status,"
                        + " start_date, end_date, selling_price, cost_price, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '稼動中', '2026-01-01', '2026-12-31', 900000, 600000, 1)",
                "NTF-C2-" + unique(), engineerId2, projectId2, orgA.getCustomerId());
        long contractId2 = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_contract", Long.class);
        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount,"
                        + " payment_amount, status) VALUES (?, '2026-07', 160, 900000, 600000, '確定')",
                contractId2);
        acceptanceService.submit(contractId2, "2026-07");

        // 同日の重複通知は抑止される（type+org+日のdedupe）
        org.mockito.Mockito.verify(mailService, org.mockito.Mockito.times(1))
                .send(org.mockito.ArgumentMatchers.eq(userA.user().getEmail()),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void 停止時に未使用招待が失効し営業の一覧はDataScopeで絞られる() throws Exception {
        // --- 停止時invitation失効（S13-R1-P0-01の補完） ---
        long orgId = insertCustomerOrg();
        String email = "expire-invite-" + unique() + "@example.com";
        mockMvc.perform(post("/api/portal-admin/orgs/" + orgId + "/invitations").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk());
        PortalUser user = createUser(organizationMapper.selectById(orgId), email);
        mockMvc.perform(put("/api/portal-admin/users/" + user.getId() + "/status").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());
        // 停止後: 未使用invitationが失効（expires_at < now）
        java.time.LocalDateTime expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM t_portal_invitation WHERE email = ?", java.time.LocalDateTime.class, email);
        assertTrue(expiresAt.isBefore(java.time.LocalDateTime.now().plusMinutes(1)),
                "停止時に未使用招待が失効するはず（S13-R1-P0-01）");

        // --- 営業DataScope: orgIdなしの招待/access-logs一覧（S13-R1-P1-01） ---
        // 実在営業user＋担当契約からDataScopeを解決する
        jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, role, status) "
                + "VALUES (?, 'x', '営業A', '営業', 1)", "sales-scope-" + unique());
        long salesUserId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM sys_user", Long.class);
        long orgB = insertCustomerOrg();
        long orgC = insertCustomerOrg();
        PortalOrganization orgBEntity = organizationMapper.selectById(orgB);
        // 営業の担当顧客=orgBのcustomerのみ（契約sales_user_idで紐付け）
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')",
                "scope-e-" + unique());
        long engineerId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_engineer", Long.class);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')",
                "scope-p-" + unique(), orgBEntity.getCustomerId());
        long projectId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_project", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, status,"
                        + " start_date, end_date, selling_price, cost_price, sales_user_id, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '稼動中', '2026-01-01', '2026-12-31', 900000, 600000, ?, 1)",
                "SCOPE-C-" + unique(), engineerId, projectId, orgBEntity.getCustomerId(), salesUserId);
        // orgBの招待1件・orgCの招待1件
        mockMvc.perform(post("/api/portal-admin/orgs/" + orgB + "/invitations").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"b-" + unique() + "@example.com\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/portal-admin/orgs/" + orgC + "/invitations").with(adminUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"c-" + unique() + "@example.com\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk());
        // 監査ログ: orgBに1件作る
        jdbcTemplate.update("INSERT INTO t_portal_access_log (portal_user_id, portal_org_id, email, org_type, action) "
                + "VALUES (1, ?, 'b-user@example.com', 'CUSTOMER', 'DOWNLOAD_QUOTATION')", orgB);

        try {
            transactionTemplate.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transactionTemplate.executeWithoutResult(status ->
                    systemConfigService.put("scope.sales-own-data-only", "true", "test"));

            // 営業（DataScope有効）: 自担当顧客（orgB）の招待のみ・access logもorgBのみ
            var salesPrincipal = user(String.valueOf(salesUserId)).roles("営業");
            mockMvc.perform(get("/api/portal-admin/invitations").with(salesPrincipal))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.records[0].portalOrgId").value(orgB));
            mockMvc.perform(get("/api/portal-admin/access-logs").with(salesPrincipal))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.records[0].portalOrgId").value(orgB));
            // 他顧客（orgC）の招待詳細指定も404
            mockMvc.perform(get("/api/portal-admin/invitations").param("orgId", String.valueOf(orgC))
                            .with(salesPrincipal))
                    .andExpect(status().isNotFound());
        } finally {
            transactionTemplate.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transactionTemplate.executeWithoutResult(status ->
                    systemConfigService.put("scope.sales-own-data-only", "false", "test"));
        }
    }

    @Test
    void user一覧とsessionは秘匿フィールドを返さない() throws Exception {
        long orgId = insertCustomerOrg();
        PortalUser user = createUser(organizationMapper.selectById(orgId), "strip-" + unique() + "@example.com");
        String secret = uniqueSecret();
        enableMfa(user, secret);
        consentTerms(user);
        issueSession(user);

        mockMvc.perform(get("/api/portal-admin/orgs/" + orgId + "/users").with(adminUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].email").value(user.getEmail()))
                .andExpect(jsonPath("$.data.records[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].totpSecretEncrypted").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].recoveryCodeHash").doesNotExist());

        mockMvc.perform(get("/api/portal-admin/users/" + user.getId() + "/sessions").with(adminUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].tokenHash").doesNotExist())
                .andExpect(jsonPath("$.data[0].ipHash").doesNotExist());

        // 不可視組織は404、要員は403
        long bpOrgId = insertBpOrg();
        mockMvc.perform(get("/api/portal-admin/orgs/" + bpOrgId + "/users").with(salesUser()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/portal-admin/orgs/" + orgId + "/users").with(user("member").roles("要員")))
                .andExpect(status().isForbidden());
    }

    private long insertEngineer() {
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')",
                "ntf-engineer-" + unique());
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_engineer", Long.class);
    }
}
