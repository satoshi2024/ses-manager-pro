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

    @Autowired
    private com.ses.mapper.OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private com.ses.mapper.CostCenterMapper costCenterMapper;

    @Autowired
    private com.ses.mapper.SysUserMapper sysUserMapper;

    @Autowired
    private com.ses.mapper.UserOrganizationMapper userOrganizationMapper;

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

        // 他テナントのジョブに対する retry/cancel は SQL 境界で 404 (R1-P1-06)
        IntegrationJob otherTenantJob = jobService.createJob(
                connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", 8889L, "idemp-other-tenant-8889", "hash8889",
                null, "other-tenant", null, null);
        jobService.claimJob(otherTenantJob.getId());
        jobService.markRetryable(otherTenantJob.getId(), "TIMEOUT", "一時的タイムアウト", 300);
        mockMvc.perform(post("/api/accounting/jobs/" + otherTenantJob.getId() + "/retry").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(post("/api/accounting/jobs/" + otherTenantJob.getId() + "/cancel?reason=REASON_CLIENT_CANCEL").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
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
        String[] expectedLabels = {"マッピング設定", "Mapping Settings", "映射配置", "매핑 설정"};
        String[] expectedCustomerCodeAttrs = {"顧客コード", "Customer Code", "客户代码", "고객 코드"};

        for (int i = 0; i < languages.length; i++) {
            // CookieLocaleResolver 経由で locale を切り替え、サーバー解決済みの可視文言がローカライズされること (R1-P1-11)
            String content = mockMvc.perform(get("/accounting/integration")
                            .cookie(new jakarta.servlet.http.Cookie("SES_LOCALE", languages[i])))
                    .andExpect(status().isOk())
                    .andExpect(view().name("accounting/integration"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(content).contains(expectedLabels[i]);
            // i18n 単一翻訳源データコンテナ: 動的表示用キーが data 属性として各言語へ解決されていること
            assertThat(content).contains("id=\"i18n-data\"");
            assertThat(content).contains("data-customer-code=\"" + expectedCustomerCodeAttrs[i] + "\"");
        }

        // ジョブキャンセル時の定型 reasonCode (design §8.3)
        IntegrationJob job = jobService.createJob(
                connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", 777L, "idemp-i18n-777", "hash777");
        mockMvc.perform(post("/api/accounting/jobs/" + job.getId() + "/cancel?reason=REASON_AMOUNT_CORRECTION")
                        .with(csrf())
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        IntegrationJob cancelled = jobService.getById(job.getId());
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        String eventDetail = jobService.listEvents(job.getId()).stream()
                .map(e -> e.getSafeDetail() != null ? e.getSafeDetail() : "")
                .reduce("", (a, b) -> a + b);
        assertThat(eventDetail).contains("REASON_AMOUNT_CORRECTION");

        // 日本語・未知の取消理由は REASON_OTHER へ正規化され、表示言語に依存しないコードで保存されること
        IntegrationJob job2 = jobService.createJob(
                connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", 778L, "idemp-i18n-778", "hash778");
        mockMvc.perform(post("/api/accounting/jobs/" + job2.getId() + "/cancel?reason=手動キャンセル")
                        .with(csrf())
                        .header("Accept-Language", "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        String eventDetail2 = jobService.listEvents(job2.getId()).stream()
                .map(e -> e.getSafeDetail() != null ? e.getSafeDetail() : "")
                .reduce("", (a, b) -> a + b);
        assertThat(eventDetail2).contains("REASON_OTHER");
        assertThat(eventDetail2).doesNotContain("手動キャンセル");

        // 未知の REASON_* (allow-list 外・PII含む) は REASON_OTHER へ正規化され、未定義値が監査イベントへ保存されないこと (R1-P1-11)
        IntegrationJob job3 = jobService.createJob(
                connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", 779L, "idemp-i18n-779", "hash779");
        mockMvc.perform(post("/api/accounting/jobs/" + job3.getId() + "/cancel?reason=REASON_customer@example.com")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        String eventDetail3 = jobService.listEvents(job3.getId()).stream()
                .map(e -> e.getSafeDetail() != null ? e.getSafeDetail() : "")
                .reduce("", (a, b) -> a + b);
        assertThat(eventDetail3).contains("REASON_OTHER");
        assertThat(eventDetail3).doesNotContain("REASON_customer@example.com");
        assertThat(eventDetail3).doesNotContain("@example.com");

        // 定義済み5コードはそのまま保存されること (REASON_DUPLICATE)
        IntegrationJob job4 = jobService.createJob(
                connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", 780L, "idemp-i18n-780", "hash780");
        mockMvc.perform(post("/api/accounting/jobs/" + job4.getId() + "/cancel?reason=REASON_DUPLICATE")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        String eventDetail4 = jobService.listEvents(job4.getId()).stream()
                .map(e -> e.getSafeDetail() != null ? e.getSafeDetail() : "")
                .reduce("", (a, b) -> a + b);
        assertThat(eventDetail4).contains("REASON_DUPLICATE");
    }

    @Test
    @DisplayName("マネージャースコープ非空集合: 他組織・他テナントのジョブ/接続/マッピング/プレビュー/照合がSQL境界で遮断される (R1-P1-06)")
    void managerScope_nonEmptyAllowed_otherOrgAndCrossTenantHidden() throws Exception {
        // --- 組織・マネージャー seeding ---
        Long orgXId = insertOrg("SCOPE-ORG-X", "スコープ組織X", 1L);
        Long orgYId = insertOrg("SCOPE-ORG-Y", "スコープ組織Y", 2L);
        Long managerUserId = insertManagerUser(orgXId);

        // --- ジョブ seeding (自組織 / 他組織 / 他テナント) ---
        IntegrationJob jobA = jobService.createJob(connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", 3101L,
                "SCOPE-JOB-A", "hash-a", "{\"a\":1}", "default", null, orgXId);
        IntegrationJob jobB = jobService.createJob(connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", 3102L,
                "SCOPE-JOB-B", "hash-b", "{\"b\":1}", "default", null, orgYId);
        IntegrationJob jobC = jobService.createJob(connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", 3103L,
                "SCOPE-JOB-C", "hash-c", "{\"c\":1}", "other-tenant", null, orgXId);

        // マネージャー (username = ローカルID) として認証
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        String.valueOf(managerUserId), null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_マネージャー"))));

        // --- ジョブ一覧: 自組織 (orgX, tenant=default) のみ ---
        mockMvc.perform(get("/api/accounting/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(jobA.getId().intValue()));

        // --- ジョブ詳細: 自組織OK / 他組織404 / 他テナント404 ---
        mockMvc.perform(get("/api/accounting/jobs/" + jobA.getId()))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/accounting/jobs/" + jobB.getId()))
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/accounting/jobs/" + jobC.getId()))
                .andExpect(jsonPath("$.code").value(404));

        // --- 接続一覧: 許可法人 (1L) と NULL のみ可視、法人2L は不可視 ---
        IntegrationConnection otherLegalConn = connectionService.getOrCreateConnection("default", 2L, "freee", "accounting");
        mockMvc.perform(get("/api/accounting/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[?(@.id == " + connection.getId() + ")]").exists())
                .andExpect(jsonPath("$.data[?(@.id == " + otherLegalConn.getId() + ")]").doesNotExist());

        // --- マッピング: 許可接続のマッピングのみ ---
        ExternalMapping otherMapping = new ExternalMapping();
        otherMapping.setConnectionId(otherLegalConn.getId());
        otherMapping.setObjectType("CUSTOMER_PARTNER");
        otherMapping.setInternalCode("SCOPE-MAP-OTHER");
        otherMapping.setExternalId("9901");
        mappingService.saveOrUpdateMapping(otherMapping);
        mockMvc.perform(get("/api/accounting/mappings?connectionId=" + otherLegalConn.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/accounting/mappings?connectionId=" + connection.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // --- プレビュー: 組織Xの請求書は200 / 組織Yは404 (SQL境界) ---
        Long ccXId = insertCostCenter("SCOPE-CC-X", "スコープCCX", orgXId);
        Long ccYId = insertCostCenter("SCOPE-CC-Y", "スコープCCY", orgYId);

        Customer cust = new Customer();
        cust.setCompanyName("スコープ顧客");
        customerService.save(cust);

        Invoice invoiceX = new Invoice();
        invoiceX.setInvoiceNo("INV-SCOPE-X"); invoiceX.setCustomerId(cust.getId());
        invoiceX.setBillingMonth("2026-08"); invoiceX.setIssuedDate(java.time.LocalDate.of(2026, 8, 15));
        invoiceX.setDueDate(java.time.LocalDate.of(2026, 9, 15));
        invoiceX.setSubtotal(new BigDecimal("100000")); invoiceX.setTax(new BigDecimal("10000"));
        invoiceX.setTotal(new BigDecimal("110000")); invoiceX.setTaxRate(new BigDecimal("0.100"));
        invoiceX.setCostCenterId(ccXId);
        invoiceService.save(invoiceX);

        Invoice invoiceY = new Invoice();
        invoiceY.setInvoiceNo("INV-SCOPE-Y"); invoiceY.setCustomerId(cust.getId());
        invoiceY.setBillingMonth("2026-08"); invoiceY.setIssuedDate(java.time.LocalDate.of(2026, 8, 15));
        invoiceY.setDueDate(java.time.LocalDate.of(2026, 9, 15));
        invoiceY.setSubtotal(new BigDecimal("200000")); invoiceY.setTax(new BigDecimal("20000"));
        invoiceY.setTotal(new BigDecimal("220000")); invoiceY.setTaxRate(new BigDecimal("0.100"));
        invoiceY.setCostCenterId(ccYId);
        invoiceService.save(invoiceY);

        mockMvc.perform(get("/api/accounting/preview/sales-invoice/" + invoiceX.getId()))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.invoiceNo").value("INV-SCOPE-X"));
        mockMvc.perform(get("/api/accounting/preview/sales-invoice/" + invoiceY.getId()))
                .andExpect(jsonPath("$.code").value(404));

        // --- 照合: 組織Xの請求のみ内部母集団へ含まれ、組織Yは除外される ---
        // 照合は全社共通接続 (legal_entity_id NULL) を使用するためトークンを設定
        IntegrationConnection commonConn = connectionService.getOrCreateConnection("default", null, "freee", "accounting");
        IntegrationTokensDto commonTokens = IntegrationTokensDto.builder()
                .accessToken("scope-common-token")
                .refreshToken("scope-common-refresh")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
        connectionService.saveTokens(commonConn.getId(), commonTokens, 10001L, "スコープ共通事業所", 1L);

        org.springframework.test.web.client.MockRestServiceServer reconMock =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        reconMock.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("/api/1/deals")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"deals\": []}", MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/api/accounting/reconciliation?month=2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[?(@.category == 'SALES' && @.internalId == " + invoiceX.getId() + ")]").exists())
                .andExpect(jsonPath("$.data.items[?(@.category == 'SALES' && @.internalId == " + invoiceY.getId() + ")]").doesNotExist());
        reconMock.verify();

        // --- 管理者の retry/cancel: 他テナントジョブは SQL 境界で 404 ---
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "admin_user", null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_管理者"))));
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("connection cross-tenant: マネージャー一覧/health で他テナント接続がSQL境界により不可視 (R1-P1-06)")
    void connection_crossTenant_hidden_forManager() throws Exception {
        Long orgXId = insertOrg("SCOPE-CONN-ORG", "スコープ接続組織", 1L);
        Long managerUserId = insertManagerUser(orgXId);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        String.valueOf(managerUserId), null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_マネージャー"))));

        IntegrationConnection otherTenantNull = connectionService.getOrCreateConnection("other-tenant", null, "freee", "accounting");
        IntegrationConnection otherTenantSameLegal = connectionService.getOrCreateConnection("other-tenant", 1L, "freee", "accounting");

        mockMvc.perform(get("/api/accounting/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[?(@.id == " + otherTenantNull.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.data[?(@.id == " + otherTenantSameLegal.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.data[?(@.id == " + connection.getId() + ")]").exists());

        mockMvc.perform(get("/api/accounting/connections/" + otherTenantNull.getId() + "/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/accounting/connections/" + otherTenantSameLegal.getId() + "/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("connection cross-tenant: 管理者のstatus更新も他テナント接続は404 (R1-P1-06)")
    @WithMockUser(username = "admin_user", roles = {"管理者"})
    void connection_crossTenant_statusUpdate_admin404() throws Exception {
        // 他テナント接続を CONNECTED にしてから、管理者(default tenant)が状態変更を試みる
        IntegrationConnection otherTenant = connectionService.getOrCreateConnection("other-tenant", null, "freee", "accounting");
        connectionService.saveTokens(otherTenant.getId(), IntegrationTokensDto.builder()
                .accessToken("x-other-tenant").refreshToken("x-other-refresh").tokenType("Bearer").expiresIn(3600L).build(),
                90001L, "他テナント事業所", 1L);
        assertThat(connectionService.getById(otherTenant.getId()).getStatus()).isEqualTo("CONNECTED");

        mockMvc.perform(post("/api/accounting/connections/" + otherTenant.getId() + "/status?status=DISCONNECTED")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        // 状態が変わっていないこと (DISCONNECTED へ遷移していない)
        assertThat(connectionService.getById(otherTenant.getId()).getStatus()).isEqualTo("CONNECTED");
    }

    private Long insertOrg(String code, String name, Long legalEntityId) {
        com.ses.entity.OrganizationUnit org = new com.ses.entity.OrganizationUnit();
        org.setTenantId(1L); org.setLegalEntityId(legalEntityId); org.setCode(code); org.setName(name);
        org.setType("部門"); org.setValidFrom(java.time.LocalDate.of(2026, 1, 1)); org.setStatus("有効"); org.setVersion(0);
        organizationUnitMapper.insert(org);
        return org.getId();
    }

    private Long insertCostCenter(String code, String name, Long organizationId) {
        com.ses.entity.CostCenter cc = new com.ses.entity.CostCenter();
        cc.setCode(code); cc.setName(name); cc.setOrganizationId(organizationId);
        cc.setValidFrom(java.time.LocalDate.of(2026, 1, 1)); cc.setStatus("有効"); cc.setVersion(0);
        costCenterMapper.insert(cc);
        return cc.getId();
    }

    private Long insertManagerUser(Long orgId) {
        com.ses.entity.SysUser user = new com.ses.entity.SysUser();
        user.setUsername("scope-manager-" + System.nanoTime());
        user.setPassword("pass");
        user.setRealName("スコープマネージャー");
        user.setRole("マネージャー");
        user.setStatus(1);
        sysUserMapper.insert(user);
        userOrganizationMapper.insert(com.ses.entity.UserOrganization.builder()
                .userId(user.getId()).organizationId(orgId).primaryFlag(1)
                .validFrom(java.time.LocalDate.of(2026, 1, 1)).build());
        return user.getId();
    }
}
