package com.ses.portal;

import com.ses.common.constant.StatusConstants;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T084 A1の顧客portal API検証（L2〜L3）。
 * org scope（IDOR）・二重検収CAS・差戻し→再提出・入金済状態の非変更・DTO field allowlist。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PortalCustomerApiTest extends PortalTestSupport {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected com.ses.service.AcceptanceService acceptanceService;

    @Override
    protected JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    private String unique() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 顧客A用の業務データfixture（契約・勤怠・検収・請求・見積・注文）。 */
    protected record CustomerData(long contractId, long workRecordId, long acceptanceId,
                                  long invoiceId, long quotationId, long salesOrderId) {
    }

    private long insertEngineer() {
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')",
                "portal-test-engineer-" + unique());
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_engineer", Long.class);
    }

    private long insertProject(PortalOrganization org) {
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')",
                "portal-test-project-" + unique(), org.getCustomerId());
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_project", Long.class);
    }

    private CustomerData seedCustomerData(PortalOrganization org, String month) {
        long engineerId = insertEngineer();
        long projectId = insertProject(org);
        jdbcTemplate.update("INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, status,"
                        + " start_date, end_date, selling_price, cost_price, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '稼動中', '2026-01-01', '2026-12-31', 900000, 600000, 1)",
                "PORTAL-CONTRACT-" + unique(), engineerId, projectId, org.getCustomerId());
        long contractId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_contract", Long.class);
        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, payment_amount, status)"
                        + " VALUES (?, ?, 160, 900000, 600000, '確定')",
                contractId, month);
        long workRecordId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_work_record", Long.class);
        jdbcTemplate.update("INSERT INTO t_acceptance (contract_id, work_record_id, work_month, status, submitted_at,"
                        + " hours_snapshot, amount_snapshot) VALUES (?, ?, ?, '提出済', NOW(), 160, 900000)",
                contractId, workRecordId, month);
        long acceptanceId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_acceptance", Long.class);
        jdbcTemplate.update("INSERT INTO t_invoice (invoice_no, customer_id, billing_month, subtotal, tax, total, status,"
                        + " issued_date, due_date) VALUES (?, ?, ?, 900000, 90000, 990000, '送付済', '2026-01-31', '2026-02-28')",
                "PORTAL-INV-" + unique(), org.getCustomerId(), month);
        long invoiceId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_invoice", Long.class);
        jdbcTemplate.update("INSERT INTO t_quotation (quotation_no, customer_id, title, unit_price, status)"
                        + " VALUES (?, ?, 'テスト見積', 1000000, '提出済')",
                "PORTAL-QUO-" + unique(), org.getCustomerId());
        long quotationId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_quotation", Long.class);
        jdbcTemplate.update("INSERT INTO t_sales_order (order_no, customer_id, order_date, status, total_amount_snapshot,"
                        + " payment_terms_snapshot) VALUES (?, ?, '2026-01-15', '注文請提出', 990000, '月末締め翌月末払い')",
                "PORTAL-ORD-" + unique(), org.getCustomerId());
        long salesOrderId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_sales_order", Long.class);
        return new CustomerData(contractId, workRecordId, acceptanceId, invoiceId, quotationId, salesOrderId);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder portalPost(String url,
                                                                                                 CsrfPair csrf,
                                                                                                 MockCookie session) {
        return post(url).cookie(csrf.cookie()).cookie(session).header("X-XSRF-TOKEN-PORTAL", csrf.headerValue());
    }

    @Test
    void 顧客Aは自組織のデータだけを参照でき他組織のID直接指定は404になる() throws Exception {
        PortalOrganization orgA = createCustomerOrg("a-" + unique());
        PortalOrganization orgB = createCustomerOrg("b-" + unique());
        UserFixture userA = readyUser(orgA, "a-" + unique() + "@example.com");
        UserFixture userB = readyUser(orgB, "b-" + unique() + "@example.com");

        CustomerData dataA = seedCustomerData(orgA, "2026-01");
        CustomerData dataB = seedCustomerData(orgB, "2026-01");
        MockCookie sessionA = userA.sessionCookie();

        // 一覧: 自組織分のみ
        mockMvc.perform(get("/api/portal/customer/acceptances").cookie(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(dataA.acceptanceId()));
        mockMvc.perform(get("/api/portal/customer/invoices").cookie(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(dataA.invoiceId()));
        mockMvc.perform(get("/api/portal/customer/contracts").cookie(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(dataA.contractId()));
        mockMvc.perform(get("/api/portal/customer/quotations").cookie(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(dataA.quotationId()));
        mockMvc.perform(get("/api/portal/customer/sales-orders").cookie(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(dataA.salesOrderId()));

        // ID直接指定: 他組織（B）のIDは404秘匿
        mockMvc.perform(get("/api/portal/customer/acceptances/" + dataB.acceptanceId()).cookie(sessionA))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/portal/customer/invoices/" + dataB.invoiceId()).cookie(sessionA))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/portal/customer/contracts/" + dataB.contractId()).cookie(sessionA))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/portal/customer/quotations/" + dataB.quotationId() + "/download").cookie(sessionA))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/portal/customer/sales-orders/" + dataB.salesOrderId()
                        + "/acknowledgement/download").cookie(sessionA))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/portal/customer/contracts/" + dataB.contractId()
                        + "/document/download").cookie(sessionA))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/portal/customer/acceptances/" + dataB.acceptanceId()
                        + "/document/download").cookie(sessionA))
                .andExpect(status().isNotFound());

        // 他組織の検収に対する操作も404
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        mockMvc.perform(portalPost("/api/portal/customer/acceptances/" + dataB.acceptanceId() + "/accept",
                        csrf, sessionA).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(portalPost("/api/portal/customer/acceptances/" + dataB.acceptanceId() + "/reject",
                        csrf, sessionA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"test\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(portalPost("/api/portal/customer/invoices/" + dataB.invoiceId() + "/register",
                        csrf, sessionA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receivedConfirmed\":true}"))
                .andExpect(status().isNotFound());

        // 顧客BのsessionでAの検収も同様に404
        mockMvc.perform(get("/api/portal/customer/acceptances/" + dataA.acceptanceId()).cookie(userB.sessionCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 顧客portalと内部の同時検収は先着1件だけ成功する() throws Exception {
        PortalOrganization orgA = createCustomerOrg("cas-" + unique());
        UserFixture userA = readyUser(orgA, "cas-" + unique() + "@example.com");
        CustomerData data = seedCustomerData(orgA, "2026-02");
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        MockCookie session = userA.sessionCookie();

        // 1) portalが先に検収 → 内部のacceptは状態CASで409
        mockMvc.perform(portalPost("/api/portal/customer/acceptances/" + data.acceptanceId() + "/accept",
                        csrf, session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("検収済"));
        mockMvc.perform(portalPost("/api/portal/customer/acceptances/" + data.acceptanceId() + "/accept",
                        csrf, session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        // 2) 別acceptanceで内部が先に検収 → portalは409
        CustomerData data2 = seedCustomerData(orgA, "2026-03");
        acceptanceService.accept(data2.acceptanceId(), null);
        mockMvc.perform(portalPost("/api/portal/customer/acceptances/" + data2.acceptanceId() + "/accept",
                        csrf, session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void 差戻しから再提出を経て再検収できる() throws Exception {
        PortalOrganization orgA = createCustomerOrg("rej-" + unique());
        UserFixture userA = readyUser(orgA, "rej-" + unique() + "@example.com");
        CustomerData data = seedCustomerData(orgA, "2026-04");
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        MockCookie session = userA.sessionCookie();

        // 理由なしの差戻しは400
        mockMvc.perform(portalPost("/api/portal/customer/acceptances/" + data.acceptanceId() + "/reject",
                        csrf, session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        // 差戻し
        mockMvc.perform(portalPost("/api/portal/customer/acceptances/" + data.acceptanceId() + "/reject",
                        csrf, session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"工数が誤っています\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("差戻し"))
                .andExpect(jsonPath("$.data.rejectComment").value("工数が誤っています"));

        // 内部が再提出（差戻し→提出済）
        acceptanceService.resubmit(data.acceptanceId());

        // 顧客が再検収
        mockMvc.perform(portalPost("/api/portal/customer/acceptances/" + data.acceptanceId() + "/accept",
                        csrf, session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("検収済"));
    }

    @Test
    void 入金済状態を変更するAPIは存在せずportal登録は状態を変えない() throws Exception {
        PortalOrganization orgA = createCustomerOrg("pay-" + unique());
        UserFixture userA = readyUser(orgA, "pay-" + unique() + "@example.com");
        CustomerData data = seedCustomerData(orgA, "2026-05");
        CsrfPair csrf = fetchPortalCsrf(mockMvc);
        MockCookie session = userA.sessionCookie();

        // 入金済状態を直接変更するPUT系endpointは存在しない（404。CSRFヘッダー付きで確認）
        mockMvc.perform(put("/api/portal/customer/invoices/" + data.invoiceId() + "/status")
                        .cookie(csrf.cookie()).cookie(session)
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(404));
        mockMvc.perform(post("/api/portal/customer/invoices/" + data.invoiceId() + "/pay")
                        .cookie(csrf.cookie()).cookie(session)
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(404));

        // 受領確認・支払予定日・問い合わせの登録
        mockMvc.perform(portalPost("/api/portal/customer/invoices/" + data.invoiceId() + "/register",
                        csrf, session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedConfirmed\":true,\"paymentExpectedDate\":\"2026-03-15\","
                                + "\"inquiry\":\"支払日を教えてください\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedConfirmedAt").exists())
                .andExpect(jsonPath("$.data.paymentExpectedDate").value("2026-03-15"))
                .andExpect(jsonPath("$.data.portalInquiry").value("支払日を教えてください"))
                .andExpect(jsonPath("$.data.status").value("送付済"));

        // 状態は不変（入金済にできない）
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM t_invoice WHERE id = ?", String.class, data.invoiceId());
        assertEquals("送付済", status, "portal登録で入金済状態を変更できないはず");

        // 入金済の請求書に対してもportal登録は状態を変えない
        jdbcTemplate.update("UPDATE t_invoice SET status = '入金済', paid_date = '2026-03-01' WHERE id = ?",
                data.invoiceId());
        mockMvc.perform(portalPost("/api/portal/customer/invoices/" + data.invoiceId() + "/register",
                        csrf, session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inquiry\":\"ありがとうございます\"}"))
                .andDo(result -> System.out.println("DEBUG register-paid: " + result.getResponse().getStatus()
                        + " " + result.getResponse().getContentAsString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("入金済"));
    }

    @Test
    void 公開DTOに内部情報が含まれない() throws Exception {
        PortalOrganization orgA = createCustomerOrg("dto-" + unique());
        UserFixture userA = readyUser(orgA, "dto-" + unique() + "@example.com");
        CustomerData data = seedCustomerData(orgA, "2026-06");
        MockCookie session = userA.sessionCookie();

        mockMvc.perform(get("/api/portal/customer/contracts").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].sellingPrice").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].costPrice").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].salesUserId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].costCenterId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].fractionRule").doesNotExist());
        mockMvc.perform(get("/api/portal/customer/invoices").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].paidDate").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].costCenterId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].createdBy").doesNotExist());
        mockMvc.perform(get("/api/portal/customer/acceptances").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].version").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].workRecordId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].createdBy").doesNotExist());
    }

    @Test
    void 検収一覧はworkMonthとstatusで絞り込める() throws Exception {
        PortalOrganization orgA = createCustomerOrg("flt-" + unique());
        UserFixture userA = readyUser(orgA, "flt-" + unique() + "@example.com");
        seedCustomerData(orgA, "2026-01");
        seedCustomerData(orgA, "2026-02");
        MockCookie session = userA.sessionCookie();

        mockMvc.perform(get("/api/portal/customer/acceptances?workMonth=2026-01").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].workMonth").value("2026-01"));
        mockMvc.perform(get("/api/portal/customer/acceptances?status=提出済").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }
}
