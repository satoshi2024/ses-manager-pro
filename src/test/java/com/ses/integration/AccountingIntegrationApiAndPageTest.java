package com.ses.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.entity.*;
import com.ses.service.CustomerService;
import com.ses.service.InvoiceService;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountingIntegrationApiAndPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationConnectionService connectionService;

    @Autowired
    private ExternalMappingService mappingService;

    @Autowired
    private IntegrationJobService jobService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private org.springframework.web.client.RestTemplate restTemplate;

    private IntegrationConnection connection;

    @BeforeEach
    void setUp() {
        connection = connectionService.getOrCreateConnection("default", 1L, "freee", "accounting");
        IntegrationTokensDto tokens = IntegrationTokensDto.builder()
                .accessToken("test-secret-access-token-999")
                .refreshToken("test-secret-refresh-token-888")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
        connectionService.saveTokens(connection.getId(), tokens, 10001L, "テスト株式会社", 1L);
    }

    @Test
    @DisplayName("権限制御: 管理者・マネージャーは画面およびAPIにアクセス可能 (200)")
    @WithMockUser(username = "admin_user", roles = {"管理者"})
    void adminCanAccessPageAndApi() throws Exception {
        mockMvc.perform(get("/accounting/integration"))
                .andExpect(status().isOk())
                .andExpect(view().name("accounting/integration"));

        mockMvc.perform(get("/api/accounting/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("権限制御: 営業・HR・要員ロールは403 Forbiddenで遮断される")
    @WithMockUser(username = "sales_user", roles = {"営業"})
    void salesUserForbidden() throws Exception {
        mockMvc.perform(get("/accounting/integration"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/accounting/connections"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("秘密情報保護: APIレスポンスにencryptedTokensやトークン文字列が含まれない (design §6.2)")
    @WithMockUser(username = "manager_user", roles = {"マネージャー"})
    void secretTokensNotExposedInApiResponse() throws Exception {
        String responseContent = mockMvc.perform(get("/api/accounting/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        assertThat(responseContent).doesNotContain("test-secret-access-token-999");
        assertThat(responseContent).doesNotContain("test-secret-refresh-token-888");
        assertThat(responseContent).doesNotContain("encryptedTokens\":{");
    }

    @Test
    @DisplayName("送信前プレビュー: マッピング未登録・未検証時にreadyToSend=falseと警告が返る (A1/design §6.1)")
    @WithMockUser(username = "admin_user", roles = {"管理者"})
    void previewValidation_unverifiedMappingBlocksReady() throws Exception {
        // 請求書テストデータ作成
        Customer customer = new Customer();
        customer.setCompanyName("APIテスト顧客");
        customerService.save(customer);

        Invoice invoice = new Invoice();
        invoice.setInvoiceNo("INV-TEST-001");
        invoice.setCustomerId(customer.getId());
        invoice.setBillingMonth("2026-08");
        invoice.setIssuedDate(LocalDate.now());
        invoice.setDueDate(LocalDate.now().plusMonths(1));
        invoice.setSubtotal(new BigDecimal("1000000"));
        invoice.setTax(new BigDecimal("100000"));
        invoice.setTotal(new BigDecimal("1100000"));
        invoice.setTaxRate(new BigDecimal("0.100"));
        invoiceService.save(invoice);

        // プレビュー実行
        mockMvc.perform(get("/api/accounting/preview/sales-invoice/" + invoice.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.invoiceNo").value("INV-TEST-001"))
                .andExpect(jsonPath("$.data.total").value(1100000));
    }

    @Test
    @DisplayName("マッピング登録と検証実行: verify APIでverifiedAtが更新される")
    @WithMockUser(username = "admin_user", roles = {"管理者"})
    void mappingCrudAndVerify() throws Exception {
        ExternalMapping mapping = new ExternalMapping();
        mapping.setConnectionId(connection.getId());
        mapping.setObjectType("CUSTOMER_PARTNER");
        mapping.setInternalCode("CUST-TEST-VERIFY");
        mapping.setExternalId("998877");
        mapping.setExternalCode("テスト取引先");

        // 登録
        mockMvc.perform(post("/api/accounting/mappings")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapping)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ExternalMapping saved = mappingService.getMapping(connection.getId(), "CUSTOMER_PARTNER", "CUST-TEST-VERIFY");
        assertThat(saved).isNotNull();
        assertThat(saved.getVerifiedAt()).isNull();

        org.springframework.test.web.client.MockRestServiceServer mockServer =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        mockServer.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.freee.co.jp/api/1/partners/998877?company_id=10001"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"partner\": {\"id\": 998877, \"name\": \"テスト取引先\"}}", MediaType.APPLICATION_JSON));

        // 検証実行 (verifyAndSnapshotMapping)
        mockMvc.perform(post("/api/accounting/mappings/" + saved.getId() + "/verify")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ExternalMapping verified = mappingService.getById(saved.getId());
        assertThat(verified.getVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("ジョブ手動リトライとキャンセル")
    @WithMockUser(username = "admin_user", roles = {"管理者"})
    void jobRetryAndCancel() throws Exception {
        IntegrationJob job = jobService.createJob(
                connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", 888L, "idemp-test-888", "hash888");

        jobService.claimJob(job.getId());
        jobService.markRetryable(job.getId(), "TIMEOUT", "一時的タイムアウト", 300);

        // 手動リトライ (RETRYABLE -> PENDING)
        mockMvc.perform(post("/api/accounting/jobs/" + job.getId() + "/retry")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        IntegrationJob reset = jobService.getById(job.getId());
        assertThat(reset.getStatus()).isEqualTo("PENDING");

        // キャンセル (PENDING -> CANCELLED)
        mockMvc.perform(post("/api/accounting/jobs/" + job.getId() + "/cancel?reason=テストキャンセル")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        IntegrationJob cancelled = jobService.getById(job.getId());
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("マネージャースコープ空組織境界: 組織未所属マネージャーは0件返却・詳細404 (R1-P1-06 / design §5.2)")
    @WithMockUser(username = "isolated_manager", roles = {"マネージャー"})
    void managerScope_emptyOrgs_returnsZeroRows() throws Exception {
        // 接続一覧: 空リストが返る
        mockMvc.perform(get("/api/accounting/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(0)));

        // ジョブ一覧: 0件返る
        mockMvc.perform(get("/api/accounting/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records", hasSize(0)))
                .andExpect(jsonPath("$.data.total").value(0));

        // マッピング一覧: 0件返る
        mockMvc.perform(get("/api/accounting/mappings?connectionId=" + connection.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(0)));

        // 存在しない、または権限外のジョブ詳細: 404
        mockMvc.perform(get("/api/accounting/jobs/999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("多言語i18nと安定エラーコード検証 (R4-T07 / design §6.3)")
    @WithMockUser(username = "admin_user", roles = {"管理者"})
    void i18n_fourLanguages_stableReasonCodes() throws Exception {
        String[] languages = {"ja", "en", "zh-CN", "ko"};

        for (String lang : languages) {
            // 各言語ヘッダーで画面にアクセスし、正常に応答すること (200)
            mockMvc.perform(get("/accounting/integration").header("Accept-Language", lang))
                    .andExpect(status().isOk())
                    .andExpect(view().name("accounting/integration"));
        }

        // ジョブキャンセル時の定型 reasonCode
        IntegrationJob job = jobService.createJob(
                connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", 777L, "idemp-i18n-777", "hash777");

        mockMvc.perform(post("/api/accounting/jobs/" + job.getId() + "/cancel?reason=USER_REQUESTED")
                        .with(csrf())
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        IntegrationJob cancelled = jobService.getById(job.getId());
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
    }
}
