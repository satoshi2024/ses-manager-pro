package com.ses.portal;

import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.service.approval.ApprovalEngineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T085 A2のBP portal API検証（L2〜L3）。
 * org scope（IDOR）・受領確認CAS・空き要員reviewフロー・口座変更（承認前未反映）・
 * 提出物のscan fail-closed・金額/支払状態の非変更API。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PortalBpApiTest extends PortalTestSupport {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected BpAvailabilityMapper bpAvailabilityMapper;
    @Autowired
    protected ApprovalRouteMapper approvalRouteMapper;
    @Autowired
    protected ApprovalRouteStepMapper approvalRouteStepMapper;
    @Autowired
    protected ApprovalEngineService approvalEngineService;

    @Override
    protected JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    private String unique() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private long insertSalesUser() {
        jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, role, status) "
                + "VALUES (?, 'x', ?, '営業', 1)", "sales-" + unique(), "営業テスト");
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM sys_user", Long.class);
    }

    private long insertApproverUser() {
        jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, role, status) "
                + "VALUES (?, 'x', ?, '管理者', 1)", "approver-" + unique(), "承認テスト");
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM sys_user", Long.class);
    }

    /** BP会社＋portal org＋user（primarySalesUserId付き）を作る。 */
    private record BpFixture(PortalOrganization org, PortalUser user, long bpCompanyId, long salesUserId) {
    }

    private BpFixture bpFixture() {
        long salesUserId = insertSalesUser();
        jdbcTemplate.update("INSERT INTO m_bp_company (legal_name, entity_type, status, primary_sales_user_id) "
                + "VALUES (?, 'CORPORATE', 'ACTIVE', ?)", "portal-bp-" + unique(), salesUserId);
        long bpCompanyId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM m_bp_company", Long.class);
        PortalOrganization org = createBpOrg("org-" + unique());
        // createBpOrgが作ったorgのbp_company_idを差し替え（1:1ユニークを満たすため）
        org.setBpCompanyId(bpCompanyId);
        organizationMapper.updateById(org);
        PortalUser user = createUser(org, "bp-" + unique() + "@example.com");
        String secret = uniqueSecret();
        enableMfa(user, secret);
        consentTerms(user);
        return new BpFixture(org, user, bpCompanyId, salesUserId);
    }

    private long insertEngineer() {
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')",
                "bp-portal-engineer-" + unique());
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_engineer", Long.class);
    }

    private long insertProject(long customerId) {
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')",
                "bp-portal-project-" + unique(), customerId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_project", Long.class);
    }

    /** work record＋BP支払行を作る（発注相当）。 */
    private long seedBpPayment(long bpCompanyId) {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "bp-portal-customer-" + unique());
        long customerId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM m_customer", Long.class);
        long engineerId = insertEngineer();
        long projectId = insertProject(customerId);
        jdbcTemplate.update("INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, status,"
                        + " start_date, end_date, selling_price, cost_price, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '稼動中', '2026-01-01', '2026-12-31', 900000, 600000, 1)",
                "BP-CONTRACT-" + unique(), engineerId, projectId, customerId);
        long contractId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_contract", Long.class);
        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount,"
                        + " payment_amount, status) VALUES (?, '2026-01', 160, 900000, 600000, '確定')",
                contractId);
        long workRecordId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_work_record", Long.class);
        jdbcTemplate.update("INSERT INTO t_bp_payment (work_record_id, layer_order, bp_company_id, amount, status)"
                        + " VALUES (?, 1, ?, 600000, '未払')", workRecordId, bpCompanyId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_bp_payment", Long.class);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder portalPost(String url,
                                                                                                 CsrfPair csrf,
                                                                                                 MockCookie session) {
        return post(url).cookie(csrf.cookie()).cookie(session).header("X-XSRF-TOKEN-PORTAL", csrf.headerValue());
    }

    private void insertRoute(String requestType, Long approverUserId) {
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L)
                .requestType(requestType)
                .activeFlag(1)
                .validFrom(LocalDate.now().minusDays(1))
                .validTo(null)
                .versionNo(1)
                .build();
        approvalRouteMapper.insert(route);
        approvalRouteStepMapper.insert(ApprovalRouteStep.builder()
                .routeId(route.getId())
                .stepNo(1)
                .parallelGroup(1)
                .approverType("USER")
                .approverValue(String.valueOf(approverUserId))
                .build());
    }

    @Test
    void BPは自社の発注と実績だけを参照でき他BPのID直接指定は404になる() throws Exception {
        BpFixture bpA = bpFixture();
        BpFixture bpB = bpFixture();
        long paymentA = seedBpPayment(bpA.bpCompanyId());
        long paymentB = seedBpPayment(bpB.bpCompanyId());
        MockCookie session = issueSession(bpA.user());

        mockMvc.perform(get("/api/portal/bp/payments").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(paymentA));
        mockMvc.perform(get("/api/portal/bp/payments/" + paymentB).cookie(session))
                .andExpect(status().isNotFound());
        mockMvc.perform(portalPost("/api/portal/bp/payments/" + paymentB + "/confirm-receipt",
                        fetchPortalCsrf(mockMvc), session))
                .andExpect(status().isNotFound());
        // 他BPの提出物一覧・downloadも404
        mockMvc.perform(get("/api/portal/bp/payments/" + paymentB + "/submissions").cookie(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void 受領確認は一回だけ設定できる() throws Exception {
        BpFixture bp = bpFixture();
        long paymentId = seedBpPayment(bp.bpCompanyId());
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        MockCookie session = issueSession(bp.user());

        mockMvc.perform(portalPost("/api/portal/bp/payments/" + paymentId + "/confirm-receipt", csrf, session))
                .andExpect(status().isOk());
        // 2回目は409（CAS）
        mockMvc.perform(portalPost("/api/portal/bp/payments/" + paymentId + "/confirm-receipt", csrf, session))
                .andExpect(status().isConflict());
        // 支払済の行は受領確認できない（未払のみ）
        jdbcTemplate.update("UPDATE t_bp_payment SET status = '支払済' WHERE id = ?", paymentId);
        long paymentId2 = seedBpPayment(bp.bpCompanyId());
        jdbcTemplate.update("UPDATE t_bp_payment SET status = '支払済' WHERE id = ?", paymentId2);
        mockMvc.perform(portalPost("/api/portal/bp/payments/" + paymentId2 + "/confirm-receipt", csrf, session))
                .andExpect(status().isConflict());
    }

    @Test
    void 金額と支払状態を変更するAPIは存在しない() throws Exception {
        BpFixture bp = bpFixture();
        long paymentId = seedBpPayment(bp.bpCompanyId());
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        MockCookie session = issueSession(bp.user());

        mockMvc.perform(put("/api/portal/bp/payments/" + paymentId)
                        .cookie(csrf.cookie()).cookie(session)
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"支払済\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(405));
        mockMvc.perform(delete("/api/portal/bp/payments/" + paymentId)
                        .cookie(csrf.cookie()).cookie(session)
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(405));
        mockMvc.perform(portalPost("/api/portal/bp/payments/" + paymentId + "/pay", csrf, session))
                .andExpect(status().isNotFound());
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM t_bp_payment WHERE id = ?", String.class, paymentId);
        assertEquals("未払", status, "BP経由で支払状態を変更できないはず（R3.3）");
    }

    @Test
    void 空き要員はreview後に有効化されreview前は内部候補に出ない() throws Exception {
        BpFixture bp = bpFixture();
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        MockCookie session = issueSession(bp.user());

        // 登録（未確認）
        long id;
        mockMvc.perform(portalPost("/api/portal/bp/availabilities", csrf, session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initialName\":\"田中 太郎\",\"unitPrice\":700000,\"experienceYears\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("未確認"));
        id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM t_bp_availability WHERE bp_company_id = ?", Long.class, bp.bpCompanyId());

        // review前は内部候補（/api/bp-availabilities）に出ない
        mockMvc.perform(get("/api/bp-availabilities").with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("admin").roles("管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(0));

        // 内部review（却下）→ 却下。編集可能
        mockMvc.perform(post("/api/bp-availabilities/" + id + "/review")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user("admin").roles("管理者"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false,\"comment\":\"単価が想定外\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("却下"));

        // 却下後はBPが編集できる（reject→update→再提出）
        mockMvc.perform(put("/api/portal/bp/availabilities/" + id).cookie(csrf.cookie()).cookie(session)
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initialName\":\"田中 太郎\",\"unitPrice\":650000,\"experienceYears\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("未確認"));

        // 内部review（承認）→ 提案可能
        mockMvc.perform(post("/api/bp-availabilities/" + id + "/review")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user("admin").roles("管理者"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("提案可能"));

        // 有効化後は内部候補に出る
        mockMvc.perform(get("/api/bp-availabilities").with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("admin").roles("管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1));

        // 提案可能の行はBPから編集不可・停止のみ可能
        mockMvc.perform(put("/api/portal/bp/availabilities/" + id).cookie(csrf.cookie()).cookie(session)
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initialName\":\"変更\",\"unitPrice\":1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(portalPost("/api/portal/bp/availabilities/" + id + "/stop", csrf, session))
                .andExpect(status().isOk());
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM t_bp_availability WHERE id = ?", String.class, id);
        assertEquals("失効", status, "停止で失効になるはず");
    }

    @Test
    void 口座変更は承認前は支払先へ反映されず承認後にAPPROVEDになる() throws Exception {
        BpFixture bp = bpFixture();
        // 申請者=BP担当営業（bp.salesUserId）。承認者は職務分離のため別ユーザー（管理者）
        long approverId = insertApproverUser();
        insertRoute("bp_bank_account.change", approverId);
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        MockCookie session = issueSession(bp.user());

        mockMvc.perform(portalPost("/api/portal/bp/bank-accounts", csrf, session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"テスト銀行\",\"branchName\":\"本店\",\"accountType\":\"ORDINARY\","
                                + "\"accountNumber\":\"1234567\",\"accountHolder\":\"テスト株式会社\"}"))
                .andExpect(status().isOk());

        // 承認前: PENDINGのまま（支払先へ未反映: R3.4）。口座番号はマスクのみ
        mockMvc.perform(get("/api/portal/bp/bank-accounts").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.data[0].accountNumber").doesNotExist());
        String approvalStatus = jdbcTemplate.queryForObject(
                "SELECT approval_status FROM t_bp_bank_account WHERE bp_company_id = ?",
                String.class, bp.bpCompanyId());
        assertEquals("PENDING", approvalStatus, "承認前はmasterへ反映されないはず（R3.4）");

        // 承認申請がengineに存在する
        Long requestId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM t_approval_request WHERE request_type = 'bp_bank_account.change'", Long.class);
        assertNotNull(requestId, "承認申請が作成されるはず");

        // 承認者（管理者user）で承認 → APPROVEDへ反映
        approvalEngineService.approve(requestId, approverId, "承認します");
        String after = jdbcTemplate.queryForObject(
                "SELECT approval_status FROM t_bp_bank_account WHERE bp_company_id = ?",
                String.class, bp.bpCompanyId());
        assertEquals("APPROVED", after, "承認後に支払先へ反映されるはず（R3.4）");

        // 2回目の申請は別口座としてPENDINGで受付
        mockMvc.perform(portalPost("/api/portal/bp/bank-accounts", csrf, session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"テスト銀行2\",\"branchName\":\"二号店\",\"accountType\":\"ORDINARY\","
                                + "\"accountNumber\":\"7654321\",\"accountHolder\":\"テスト株式会社\"}"))
                .andExpect(status().isOk());
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_bp_bank_account WHERE bp_company_id = ?", Long.class, bp.bpCompanyId()));
    }

    @Test
    void 口座変更は担当営業未設定なら申請できない() throws Exception {
        jdbcTemplate.update("INSERT INTO m_bp_company (legal_name, entity_type, status) "
                + "VALUES (?, 'CORPORATE', 'ACTIVE')", "portal-bp-nosales-" + unique());
        long bpCompanyId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM m_bp_company WHERE legal_name LIKE 'portal-bp-nosales%'", Long.class);
        PortalOrganization org = createBpOrg("nosales-" + unique());
        org.setBpCompanyId(bpCompanyId);
        organizationMapper.updateById(org);
        PortalUser user = createUser(org, "nosales-" + unique() + "@example.com");
        String secret = uniqueSecret();
        enableMfa(user, secret);
        consentTerms(user);
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        MockCookie session = issueSession(user);

        mockMvc.perform(portalPost("/api/portal/bp/bank-accounts", csrf, session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"テスト銀行\",\"branchName\":\"本店\",\"accountType\":\"ORDINARY\","
                                + "\"accountNumber\":\"1234567\",\"accountHolder\":\"テスト株式会社\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 提出物はscan通過後のみ公開され感染ファイルは拒否される() throws Exception {
        BpFixture bp = bpFixture();
        long paymentId = seedBpPayment(bp.bpCompanyId());
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        MockCookie session = issueSession(bp.user());

        // 正常PDF（モック）→ 登録成功・一覧に出る・download可能
        MockMultipartFile pdf = new MockMultipartFile("file", "invoice.pdf",
                "application/pdf", "%PDF-1.4 test invoice".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/portal/bp/payments/" + paymentId + "/submissions")
                        .file(pdf)
                        .cookie(csrf.cookie()).cookie(session)
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/portal/bp/payments/" + paymentId + "/submissions").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].downloadable").value(true));

        // 感染ファイル（EICAR）→ scan fail-closedで拒否（R4.4）
        MockMultipartFile eicar = new MockMultipartFile("file", "malware.pdf",
                "application/pdf", "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/portal/bp/payments/" + paymentId + "/submissions")
                        .file(eicar)
                        .cookie(csrf.cookie()).cookie(session)
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue()))
                .andExpect(status().isBadRequest());
        // 一覧は1件のまま（感染ファイルは未登録）
        mockMvc.perform(get("/api/portal/bp/payments/" + paymentId + "/submissions").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void 支払詳細は自社の任意の行を解決できる() throws Exception {
        BpFixture bp = bpFixture();
        long paymentA = seedBpPayment(bp.bpCompanyId());
        long paymentB = seedBpPayment(bp.bpCompanyId());
        MockCookie session = issueSession(bp.user());

        // 最新でない行（id順で古い方）の詳細も200（S13-R1-P1-02）
        long older = Math.min(paymentA, paymentB);
        long newer = Math.max(paymentA, paymentB);
        mockMvc.perform(get("/api/portal/bp/payments/" + newer).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(newer));
        mockMvc.perform(get("/api/portal/bp/payments/" + older).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(older))
                .andExpect(jsonPath("$.data.workMonth").value("2026-01"));
    }

    @Test
    void 公開DTOに社内情報が含まれない() throws Exception {
        BpFixture bp = bpFixture();
        long paymentId = seedBpPayment(bp.bpCompanyId());
        MockCookie session = issueSession(bp.user());

        mockMvc.perform(get("/api/portal/bp/payments").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].costCenterId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].parentPaymentId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].remarks").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].workRecordId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].contractNo").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].engineerName").doesNotExist());
    }
}
