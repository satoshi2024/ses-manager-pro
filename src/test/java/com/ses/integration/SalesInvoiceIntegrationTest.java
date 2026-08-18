package com.ses.integration;

import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.entity.*;
import com.ses.service.CustomerService;
import com.ses.service.InvoiceService;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.SalesInvoiceIntegrationService;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@ActiveProfiles("test")
class SalesInvoiceIntegrationTest {

    @Autowired
    private SalesInvoiceIntegrationService salesIntegrationService;

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
    private MonthlyClosingService monthlyClosingService;

    @Autowired
    private com.ses.mapper.SystemConfigMapper systemConfigMapper;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer mockServer;
    private IntegrationConnection connection;
    private Customer customer;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();

        connection = connectionService.getOrCreateConnection("default", null, "freee", "accounting");
        IntegrationTokensDto tokens = IntegrationTokensDto.builder()
                .accessToken("test-token-sales-001")
                .refreshToken("test-refresh-sales-001")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
        connectionService.saveTokens(connection.getId(), tokens, 99001L, "売上テスト事業所", 1L);

        // 顧客 & 請求書作成
        customer = new Customer();
        customer.setCompanyName("売上連携テスト顧客");
        customerService.save(customer);

        invoice = new Invoice();
        invoice.setInvoiceNo("INV-SALES-" + UUID.randomUUID().toString().substring(0, 8));
        invoice.setCustomerId(customer.getId());
        invoice.setBillingMonth("2026-08");
        invoice.setIssuedDate(LocalDate.of(2026, 8, 1));
        invoice.setDueDate(LocalDate.of(2026, 8, 31));
        invoice.setSubtotal(new BigDecimal("1000000"));
        invoice.setTax(new BigDecimal("100000"));
        invoice.setTotal(new BigDecimal("1100000"));
        invoice.setTaxRate(new BigDecimal("0.100"));
        invoiceService.save(invoice);

        // マッピング登録・検証
        ExternalMapping partnerMap = new ExternalMapping();
        partnerMap.setConnectionId(connection.getId());
        partnerMap.setObjectType("CUSTOMER_PARTNER");
        partnerMap.setInternalCode("CUST-" + customer.getId());
        partnerMap.setExternalId("1001");
        partnerMap.setExternalCode("テスト取引先");
        mappingService.saveOrUpdateMapping(partnerMap);
        ExternalMapping savedPartner = mappingService.getMapping(connection.getId(), "CUSTOMER_PARTNER", "CUST-" + customer.getId());
        mappingService.verifyMapping(savedPartner.getId(), "{\"verified\":true}");

        ExternalMapping salesMap = new ExternalMapping();
        salesMap.setConnectionId(connection.getId());
        salesMap.setObjectType("ACCOUNT_SALES");
        salesMap.setInternalCode("SALES_DEFAULT");
        salesMap.setExternalId("2001");
        salesMap.setExternalCode("売上高");
        mappingService.saveOrUpdateMapping(salesMap);
        ExternalMapping savedSales = mappingService.getMapping(connection.getId(), "ACCOUNT_SALES", "SALES_DEFAULT");
        mappingService.verifyMapping(savedSales.getId(), "{\"verified\":true}");

        ExternalMapping taxMap = new ExternalMapping();
        taxMap.setConnectionId(connection.getId());
        taxMap.setObjectType("TAX_SALES_10");
        taxMap.setInternalCode("TAX_10");
        taxMap.setExternalId("21");
        taxMap.setExternalCode("課税売上10%");
        mappingService.saveOrUpdateMapping(taxMap);
        ExternalMapping savedTax = mappingService.getMapping(connection.getId(), "TAX_SALES_10", "TAX_10");
        mappingService.verifyMapping(savedTax.getId(), "{\"verified\":true}");
    }

    @Test
    @DisplayName("冪等性: 同一請求書に対して10回同時にtriggerSalesSyncを実行してもジョブは1件しか生成されない")
    void triggerSalesSync_concurrent10Times_singleJobCreated() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<IntegrationJob>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return salesIntegrationService.triggerSalesSync(invoice.getId(), 1L);
            }));
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        Long firstJobId = null;
        for (Future<IntegrationJob> f : futures) {
            IntegrationJob job = f.get();
            assertThat(job).isNotNull();
            if (firstJobId == null) {
                firstJobId = job.getId();
            } else {
                assertThat(job.getId()).isEqualTo(firstJobId);
            }
        }
    }

    @Test
    @DisplayName("売上連携成功: Worker実行でfreee APIへ送信されSUCCEEDEDとexternalIdが記録される")
    void processSalesInvoiceJob_success() {
        IntegrationJob job = salesIntegrationService.triggerSalesSync(invoice.getId(), 1L);
        assertThat(job.getStatus()).isEqualTo("PENDING");

        String responseJson = "{\"deal\": {\"id\": 77777, \"company_id\": 99001, \"amount\": 1100000, \"status\": \"unsettled\"}}";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Freee-Request-ID", "req-sales-success-001");

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON).headers(headers));

        // Worker 同期実行
        salesIntegrationService.processSalesInvoiceJob(job.getId());

        mockServer.verify();
        IntegrationJob updated = jobService.getById(job.getId());
        assertThat(updated.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(updated.getExternalId()).isEqualTo("77777");
        assertThat(updated.getProviderRequestId()).isEqualTo("req-sales-success-001");
    }

    @Test
    @DisplayName("金額不一致ガード: 送信金額とfreee応答金額が乖離している場合はSUCCEEDEDとみなさずFAILED")
    void processSalesInvoiceJob_amountMismatch_failed() {
        IntegrationJob job = salesIntegrationService.triggerSalesSync(invoice.getId(), 1L);

        // freee側の応答金額が 1,000,000 円 (送信は1,100,000円)
        String responseJson = "{\"deal\": {\"id\": 77778, \"company_id\": 99001, \"amount\": 1000000, \"status\": \"unsettled\"}}";

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        salesIntegrationService.processSalesInvoiceJob(job.getId());

        IntegrationJob updated = jobService.getById(job.getId());
        assertThat(updated.getStatus()).isEqualTo("FAILED");
        assertThat(updated.getErrorCode()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("請求取消連携: 連携済み取引に対して取消ジョブをキューイング・実行しfreee取引を取り消す")
    void triggerAndProcessSalesCancel_success() {
        // 先に売上連携成功を記録
        IntegrationJob syncJob = salesIntegrationService.triggerSalesSync(invoice.getId(), 1L);
        jobService.claimJob(syncJob.getId());
        jobService.markSucceeded(syncJob.getId(), "77799", "req-sync-99", "売上登録完了");

        // 取消トリガー
        IntegrationJob cancelJob = salesIntegrationService.triggerSalesCancel(invoice.getId(), "顧客都合によるキャンセル", 1L);
        assertThat(cancelJob).isNotNull();
        assertThat(cancelJob.getJobType()).isEqualTo("SALES_INVOICE_CANCEL");

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals/77799?company_id=99001"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        salesIntegrationService.processSalesCancelJob(cancelJob.getId());

        mockServer.verify();
        IntegrationJob updatedCancel = jobService.getById(cancelJob.getId());
        assertThat(updatedCancel.getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("月次締めガード: 締め済み月の請求書に対する連携トリガーは拒否される")
    void triggerSalesSync_closedMonth_rejected() {
        Invoice closedInvoice = new Invoice();
        closedInvoice.setInvoiceNo("INV-CLOSED-" + UUID.randomUUID().toString().substring(0, 8));
        closedInvoice.setCustomerId(customer.getId());
        closedInvoice.setBillingMonth("2025-01"); // 過去の締め済み月
        closedInvoice.setIssuedDate(LocalDate.of(2025, 1, 15));
        closedInvoice.setSubtotal(new BigDecimal("500000"));
        closedInvoice.setTax(new BigDecimal("50000"));
        closedInvoice.setTotal(new BigDecimal("550000"));
        closedInvoice.setTaxRate(new BigDecimal("0.100"));
        invoiceService.save(closedInvoice);

        // 2025-01 を締め済みに設定
        SystemConfig config = systemConfigMapper.selectById("closing.confirmed-months");
        if (config == null) {
            config = new SystemConfig();
            config.setConfigKey("closing.confirmed-months");
            config.setConfigValue("[{\"month\":\"2025-01\",\"by\":1,\"at\":\"2025-02-01T00:00:00\"}]");
            systemConfigMapper.insert(config);
        } else {
            config.setConfigValue("[{\"month\":\"2025-01\",\"by\":1,\"at\":\"2025-02-01T00:00:00\"}]");
            systemConfigMapper.updateById(config);
        }

        assertThatThrownBy(() -> salesIntegrationService.triggerSalesSync(closedInvoice.getId(), 1L))
                .isInstanceOf(BusinessException.class);
    }
}
