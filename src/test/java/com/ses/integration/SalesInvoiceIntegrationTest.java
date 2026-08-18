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
        invoice.setStatus("送付済");
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
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"deal\": {\"id\": 77799}}", MediaType.APPLICATION_JSON));

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

    @Test
    @DisplayName("適格性ガード: 未送付・下書きステータスの請求書は会計連携が拒否される")
    void triggerSalesSync_draftStatus_rejected() {
        Invoice draftInvoice = new Invoice();
        draftInvoice.setInvoiceNo("INV-DRAFT-" + UUID.randomUUID().toString().substring(0, 8));
        draftInvoice.setCustomerId(customer.getId());
        draftInvoice.setBillingMonth("2026-08");
        draftInvoice.setIssuedDate(LocalDate.of(2026, 8, 1));
        draftInvoice.setSubtotal(new BigDecimal("272727"));
        draftInvoice.setTax(new BigDecimal("27273"));
        draftInvoice.setTotal(new BigDecimal("300000"));
        draftInvoice.setStatus("未送付"); // 送付前
        invoiceService.save(draftInvoice);

        assertThatThrownBy(() -> salesIntegrationService.triggerSalesSync(draftInvoice.getId(), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("送付済または入金済の請求書のみ会計連携可能");
    }

    @Test
    @DisplayName("Snapshot 整合性: Enqueue後に請求書が変更されてもWorkerは保存済みSnapshot (100万円) を送信すること (R1-P1-07)")
    void processSalesInvoiceJob_mutatedPayload_usesSnapshot() {
        Invoice inv = new Invoice();
        inv.setInvoiceNo("INV-MUTATE-" + UUID.randomUUID().toString().substring(0, 8));
        inv.setCustomerId(customer.getId());
        inv.setBillingMonth("2026-08");
        inv.setIssuedDate(LocalDate.of(2026, 8, 1));
        inv.setSubtotal(new BigDecimal("909091"));
        inv.setTax(new BigDecimal("90909"));
        inv.setTotal(new BigDecimal("1000000"));
        inv.setStatus("送付済");
        invoiceService.save(inv);

        // ジョブ登録 (この時点の100万円Snapshotが記録される)
        IntegrationJob job = salesIntegrationService.triggerSalesSync(inv.getId(), 1L);

        // ジョブ登録後に請求書金額を120万円に変更
        inv.setTotal(new BigDecimal("1200000"));
        invoiceService.updateById(inv);

        // freeeモック: Snapshot の 100万円 で送信されること
        String responseJson = "{\"deal\": {\"id\": 778899, \"company_id\": 99001, \"amount\": 1000000, \"status\": \"unsettled\"}}";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Freee-Request-ID", "req-snap-001");
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON).headers(headers));

        // Worker による処理実行
        salesIntegrationService.processSalesInvoiceJob(job.getId());

        mockServer.verify();
        IntegrationJob succeededJob = jobService.getById(job.getId());
        assertThat(succeededJob.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(succeededJob.getExternalId()).isEqualTo("778899");
    }

    @Test
    @DisplayName("In-Flight 取消時原子補償: RUNNING 中キャンセル時の CANCELLED_EXTERNALLY_CREATED 検知と補償 SALES_INVOICE_CANCEL エンキュー (R1-P1-02)")
    void cancelJob_inFlightAtomicCompensation() {
        // 1. 売上同期ジョブを登録し RUNNING へ
        IntegrationJob job = salesIntegrationService.triggerSalesSync(invoice.getId(), 1L);
        job = jobService.claimJob(job.getId());

        // 2. HTTP 呼出中 (Worker 送信中) にユーザーがジョブをキャンセル
        jobService.cancelJob(job.getId(), "ユーザーによるキャンセル");
        assertThat(jobService.getById(job.getId()).getStatus()).isEqualTo("CANCELLED");

        // 3. 外部で取引が作成された結果が Worker に返却されたシミュレーション
        com.ses.dto.accounting.canonical.CanonicalDealResult externalResult =
                com.ses.dto.accounting.canonical.CanonicalDealResult.builder()
                        .success(true)
                        .externalId("667788")
                        .providerRequestId("req-inflight-001")
                        .build();

        // 4. Worker の結果処理を実行 -> CANCELLED_EXTERNALLY_CREATED を検知し、同一Txで補償ジョブが生成されること
        salesIntegrationService.handleSalesInvoiceResult(job.getId(), job, connection, externalResult);

        // 5. 補償ジョブ (SALES_INVOICE_CANCEL) の存在確認
        IntegrationJob compensationJob = jobService.getLatestJob("INVOICE", invoice.getId(), "SALES_INVOICE_CANCEL");
        assertThat(compensationJob).isNotNull();
        assertThat(compensationJob.getStatus()).isEqualTo("PENDING");
        assertThat(compensationJob.getPayloadSnapshot()).contains("667788");

        // イベントログの確認
        List<IntegrationJobEvent> events = jobService.listEvents(job.getId());
        assertThat(events).anyMatch(e -> "CANCELLED_EXTERNALLY_CREATED".equals(e.getEventType()));
    }

    @Test
    @DisplayName("Snapshot 厳格実行: 取消 Worker が変更可能テーブルを再読込せず snapshot から dealId と理由を取り出して送信すること (R1-P1-07)")
    void cancelJob_executesStrictlyFromSnapshot() throws Exception {
        // 1. SALES_INVOICE_CANCEL ジョブを snapshot 付きで登録 (SHA-256 ハッシュを計算)
        String payloadJson = "{\"invoiceId\":" + invoice.getId() + ",\"externalDealId\":\"998877\",\"reason\":\"snapshotに保存された理由\"}";
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        IntegrationJob cancelJob = jobService.createJob(
                connection.getId(), "SALES_INVOICE_CANCEL", "INVOICE", invoice.getId(),
                "CANCEL-INV-SNAPSHOT-001", hash, payloadJson,
                connection.getTenantId(), connection.getLegalEntityId(), 1L);

        // 2. freee への 取消 DELETE をモック (snapshot の dealId 998877 に対して)
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals/998877?company_id=99001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"deal\": {\"id\": 998877}}", MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals/998877?company_id=99001"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // 3. Worker 実行
        salesIntegrationService.processSalesCancelJob(cancelJob.getId());

        mockServer.verify();
        IntegrationJob updated = jobService.getById(cancelJob.getId());
        assertThat(updated.getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("In-Flight 取消時補償トランザクション原子性: 補償INSERT失敗時にCANCELLED_EXTERNALLY_CREATEDイベントもロールバックされること (R1-P1-02)")
    void inFlightCancel_compensationJobFailure_rollsBackTransaction() {
        // 1. ジョブを RUNNING -> CANCELLED に
        IntegrationJob job = salesIntegrationService.triggerSalesSync(invoice.getId(), 1L);
        job = jobService.claimJob(job.getId());
        jobService.cancelJob(job.getId(), "ユーザーによるキャンセル");

        int initialEventCount = jobService.listEvents(job.getId()).size();

        // 2. 意図的に同等の補償ジョブを先に作成しておき、createJob が DUPLICATE_ACTIVE_JOB 例外をスローするように仕込む
        String dealId = "667799";
        String compIdempotencyKey = "COMPENSATE_CANCEL:" + invoice.getId() + ":" + dealId;
        jobService.createJob(connection.getId(), "SALES_INVOICE_CANCEL", "INVOICE", invoice.getId(), compIdempotencyKey, "hash_dummy");

        com.ses.dto.accounting.canonical.CanonicalDealResult externalResult =
                com.ses.dto.accounting.canonical.CanonicalDealResult.builder()
                        .success(true)
                        .externalId(dealId)
                        .providerRequestId("req-inflight-002")
                        .build();

        // 3. TransactionCoordinator のトランザクション実行 (補償作成で重複例外発生)
        final IntegrationJob targetJob = job;
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            salesIntegrationService.handleSalesInvoiceResult(targetJob.getId(), targetJob, connection, externalResult);
        });

        // 4. トランザクションがロールバックされ、CANCELLED_EXTERNALLY_CREATED イベントが残っていないことを検証
        List<IntegrationJobEvent> events = jobService.listEvents(job.getId());
        assertThat(events.size()).isEqualTo(initialEventCount);
        assertThat(events).noneMatch(e -> "CANCELLED_EXTERNALLY_CREATED".equals(e.getEventType()));
    }

    @Test
    @DisplayName("Snapshot必須・ハッシュ検証: 売上/取消WorkerはNULL snapshot・改変snapshotを外部送信せずfail-closed (R1-P1-07)")
    void workers_snapshotRequiredAndHashVerified() throws Exception {
        // 1. 売上 sync Worker: NULL snapshot -> 送信せず FAILED (LEGACY_SNAPSHOT_MISSING)
        IntegrationJob legacySync = jobService.createJob(connection.getId(), "SALES_INVOICE_SYNC", "INVOICE",
                invoice.getId(), "SALES-LEGACY-SNAP", "hash-legacy");
        // Worker が内部で claim するため、テスト側で先行 claim しない
        salesIntegrationService.processSalesInvoiceJob(legacySync.getId());
        IntegrationJob legacySyncAfter = jobService.getById(legacySync.getId());
        assertThat(legacySyncAfter.getStatus()).isEqualTo("FAILED");
        assertThat(legacySyncAfter.getErrorCode()).isEqualTo("LEGACY_SNAPSHOT_MISSING");

        // 2. 売上 sync Worker: 改変 snapshot (SHA-256 不一致) -> 送信せず FAILED (PAYLOAD_HASH_MISMATCH)
        IntegrationJob tamperedSync = jobService.createJob(connection.getId(), "SALES_INVOICE_SYNC", "INVOICE",
                invoice.getId(), "SALES-TAMPER-SNAP", "hash-tampered",
                "{\"invoiceId\":999,\"total\":1}", connection.getTenantId(), connection.getLegalEntityId(), null);
        // Worker が内部で claim するため、テスト側で先行 claim しない
        salesIntegrationService.processSalesInvoiceJob(tamperedSync.getId());
        IntegrationJob tamperedSyncAfter = jobService.getById(tamperedSync.getId());
        assertThat(tamperedSyncAfter.getStatus()).isEqualTo("FAILED");
        assertThat(tamperedSyncAfter.getErrorCode()).isEqualTo("PAYLOAD_HASH_MISMATCH");

        // 3. 取消 Worker: NULL snapshot -> 送信せず FAILED
        IntegrationJob legacyCancel = jobService.createJob(connection.getId(), "SALES_INVOICE_CANCEL", "INVOICE",
                invoice.getId(), "CANCEL-LEGACY-SNAP", "hash-cancel-legacy");
        // Worker が内部で claim するため、テスト側で先行 claim しない
        salesIntegrationService.processSalesCancelJob(legacyCancel.getId());
        IntegrationJob legacyCancelAfter = jobService.getById(legacyCancel.getId());
        assertThat(legacyCancelAfter.getStatus()).isEqualTo("FAILED");
        assertThat(legacyCancelAfter.getErrorCode()).isEqualTo("LEGACY_SNAPSHOT_MISSING");

        // 4. 取消 Worker: 改変 snapshot -> 送信せず FAILED
        IntegrationJob tamperedCancel = jobService.createJob(connection.getId(), "SALES_INVOICE_CANCEL", "INVOICE",
                invoice.getId(), "CANCEL-TAMPER-SNAP", "hash-cancel-tampered",
                "{\"invoiceId\":999,\"externalDealId\":\"998877\"}", connection.getTenantId(), connection.getLegalEntityId(), null);
        // Worker が内部で claim するため、テスト側で先行 claim しない
        salesIntegrationService.processSalesCancelJob(tamperedCancel.getId());
        IntegrationJob tamperedCancelAfter = jobService.getById(tamperedCancel.getId());
        assertThat(tamperedCancelAfter.getStatus()).isEqualTo("FAILED");
        assertThat(tamperedCancelAfter.getErrorCode()).isEqualTo("PAYLOAD_HASH_MISMATCH");

        // 5. 外部 API は一切呼ばれていないこと
        mockServer.verify();
    }
}
