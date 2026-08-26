package com.ses.integration;

import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.entity.*;
import com.ses.mapper.BpBankAccountMapper;
import com.ses.mapper.BpCompanyMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.accounting.PurchaseExpensePaymentIntegrationService;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PurchaseExpenseIntegrationTest {

    @Autowired
    private PurchaseExpensePaymentIntegrationService purchaseIntegrationService;

    @Autowired
    private IntegrationConnectionService connectionService;

    @Autowired
    private ExternalMappingService mappingService;

    @Autowired
    private IntegrationJobService jobService;

    @Autowired
    private BpCompanyMapper bpCompanyMapper;

    @Autowired
    private BpBankAccountMapper bpBankAccountMapper;

    @Autowired
    private BpPaymentMapper bpPaymentMapper;

    @Autowired
    private WorkRecordMapper workRecordMapper;

    @Autowired
    private com.ses.mapper.ContractMapper contractMapper;

    @Autowired
    private com.ses.mapper.ExpenseRequestMapper expenseRequestMapper;

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    @Autowired
    private AccountingTimezoneResolver timezoneResolver;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer mockServer;
    private IntegrationConnection connection;
    private BpCompany bpCompany;
    private BpPayment bpPayment;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();

        connection = connectionService.getOrCreateConnection("default", null, "freee", "accounting");
        IntegrationTokensDto tokens = IntegrationTokensDto.builder()
                .accessToken("test-token-bp-001")
                .refreshToken("test-refresh-bp-001")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
        connectionService.saveTokens(connection.getId(), tokens, 99001L, "BP仕入テスト事業所", 1L);

        // BP会社作成
        bpCompany = BpCompany.builder()
                .tenantId(1L)
                .legalName("テストBPパートナー株式会社-" + UUID.randomUUID().toString().substring(0, 6))
                .entityType("CORPORATE")
                .status("ACTIVE")
                .build();
        bpCompanyMapper.insert(bpCompany);

        // 正常な承認済み口座
        BpBankAccount bankAccount = BpBankAccount.builder()
                .tenantId(1L)
                .bpCompanyId(bpCompany.getId())
                .bankName("みずほ銀行")
                .branchName("丸の内支店")
                .accountType("ORDINARY")
                .encryptedAccountNumber("enc-1234567")
                .maskedLabel("みずほ銀行 ***4567")
                .accountHolder("テストBP")
                .validFrom(LocalDate.of(2026, 1, 1))
                .approvalStatus("APPROVED")
                .build();
        bpBankAccountMapper.insert(bankAccount);

        // 契約 & 工数レコード作成
        Contract contract = new Contract();
        contract.setContractNo("CON-BP-" + UUID.randomUUID().toString().substring(0, 8));
        contract.setEngineerId(1L);
        contract.setProjectId(1L);
        contract.setCustomerId(1L);
        contract.setContractType("準委任");
        contract.setStatus("稼動中");
        contract.setStartDate(LocalDate.of(2026, 1, 1));
        contract.setEndDate(LocalDate.of(2026, 12, 31));
        contract.setSellingPrice(new BigDecimal("900000"));
        contract.setCostPrice(new BigDecimal("800000"));
        contractMapper.insert(contract);

        WorkRecord wr = new WorkRecord();
        wr.setContractId(contract.getId());
        wr.setWorkMonth("2026-08");
        wr.setActualHours(new BigDecimal("160.00"));
        workRecordMapper.insert(wr);

        // BP支払作成
        bpPayment = new BpPayment();
        bpPayment.setWorkRecordId(wr.getId());
        bpPayment.setBpCompanyId(bpCompany.getId());
        bpPayment.setPayeeCompanyName(bpCompany.getLegalName());
        bpPayment.setAmount(new BigDecimal("800000"));
        bpPayment.setStatus("未払");
        bpPaymentMapper.insert(bpPayment);

        // マッピング登録・検証
        ExternalMapping partnerMap = new ExternalMapping();
        partnerMap.setConnectionId(connection.getId());
        partnerMap.setObjectType("BP_PARTNER");
        partnerMap.setInternalCode("BP-" + bpCompany.getId());
        partnerMap.setExternalId("3001");
        partnerMap.setExternalCode("テストBP取引先");
        mappingService.saveOrUpdateMapping(partnerMap);
        ExternalMapping savedPartner = mappingService.getMapping(connection.getId(), "BP_PARTNER", "BP-" + bpCompany.getId());
        mappingService.verifyMapping(savedPartner.getId(), "{\"verified\":true}");

        ExternalMapping purchaseMap = new ExternalMapping();
        purchaseMap.setConnectionId(connection.getId());
        purchaseMap.setObjectType("ACCOUNT_PURCHASE");
        purchaseMap.setInternalCode("PURCHASE_DEFAULT");
        purchaseMap.setExternalId("4001");
        purchaseMap.setExternalCode("外注費");
        mappingService.saveOrUpdateMapping(purchaseMap);
        ExternalMapping savedPurchase = mappingService.getMapping(connection.getId(), "ACCOUNT_PURCHASE", "PURCHASE_DEFAULT");
        mappingService.verifyMapping(savedPurchase.getId(), "{\"verified\":true}");

        ExternalMapping taxMap = new ExternalMapping();
        taxMap.setConnectionId(connection.getId());
        taxMap.setObjectType("TAX_PURCHASE_10");
        taxMap.setInternalCode("TAX_PURCHASE_10");
        taxMap.setExternalId("41");
        taxMap.setExternalCode("課税仕入10%");
        mappingService.saveOrUpdateMapping(taxMap);
        ExternalMapping savedTax = mappingService.getMapping(connection.getId(), "TAX_PURCHASE_10", "TAX_PURCHASE_10");
        mappingService.verifyMapping(savedTax.getId(), "{\"verified\":true}");
    }

    @Test
    @DisplayName("口座変更未承認ガード: 口座変更申請がPENDINGのBP会社への支払連携は拒否される")
    void triggerBpPurchaseSync_pendingBankAccountChange_rejected() {
        // PENDINGの口座変更申請を追加
        BpBankAccount pendingAccount = BpBankAccount.builder()
                .tenantId(1L)
                .bpCompanyId(bpCompany.getId())
                .bankName("三菱UFJ銀行")
                .branchName("本店")
                .accountType("ORDINARY")
                .encryptedAccountNumber("enc-9999999")
                .maskedLabel("三菱UFJ銀行 ***9999")
                .accountHolder("テストBP新口座")
                .validFrom(LocalDate.of(2026, 8, 1))
                .approvalStatus("PENDING")
                .build();
        bpBankAccountMapper.insert(pendingAccount);

        assertThatThrownBy(() -> purchaseIntegrationService.triggerBpPurchaseSync(bpPayment.getId(), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("銀行口座変更が承認待ち");
    }

    @Test
    @DisplayName("BP仕入連携成功: Worker実行でfreee APIへ送信されSUCCEEDEDとexternalIdが記録される")
    void processBpPurchaseJob_success() {
        IntegrationJob job = purchaseIntegrationService.triggerBpPurchaseSync(bpPayment.getId(), 1L);
        assertThat(job.getStatus()).isEqualTo("PENDING");

        String responseJson = "{\"deal\": {\"id\": 88888, \"company_id\": 99001, \"amount\": 800000, \"status\": \"unsettled\"}}";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Freee-Request-ID", "req-bp-success-001");

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON).headers(headers));

        purchaseIntegrationService.processBpPurchaseJob(job.getId());

        mockServer.verify();
        IntegrationJob updated = jobService.getById(job.getId());
        assertThat(updated.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(updated.getExternalId()).isEqualTo("88888");
        assertThat(updated.getProviderRequestId()).isEqualTo("req-bp-success-001");
    }

    @Test
    @DisplayName("支払実績同期成功: 外部決済情報の金額・日付が一致した場合に内部BpPaymentが支払済に更新される")
    void processPaymentSyncJob_matchingAmount_updatesPaid() {
        // 先に仕入連携完了Jobを記録
        IntegrationJob purchaseJob = purchaseIntegrationService.triggerBpPurchaseSync(bpPayment.getId(), 1L);
        jobService.claimJob(purchaseJob.getId());
        jobService.markSucceeded(purchaseJob.getId(), "88899", "req-bp-99", "仕入登録完了");

        // 決済情報同期トリガー
        IntegrationJob syncJob = purchaseIntegrationService.triggerPaymentSync(bpPayment.getId(), 1L);

        // freee決済情報 (金額800,000円, 決済日2026-08-25)
        String paymentsResponseJson = "{\"deal\": {\"id\": 88899, \"payments\": [{\"id\": 5501, \"date\": \"2026-08-25\", \"amount\": 800000, \"from_walletable_id\": 101}]}}";

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals/88899?company_id=99001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(paymentsResponseJson, MediaType.APPLICATION_JSON));

        purchaseIntegrationService.processPaymentSyncJob(syncJob.getId());

        mockServer.verify();
        IntegrationJob updatedSync = jobService.getById(syncJob.getId());
        assertThat(updatedSync.getStatus()).isEqualTo("SUCCEEDED");

        // 内部 BpPayment のステータスが「支払済」かつ paidDate がセットされていること
        BpPayment updatedPayment = bpPaymentMapper.selectById(bpPayment.getId());
        assertThat(updatedPayment.getStatus()).isEqualTo("支払済");
        assertThat(updatedPayment.getPaidDate()).isEqualTo(LocalDate.of(2026, 8, 25));
    }

    @Test
    @DisplayName("支払実績同期ガード: 外部決済金額が不一致の場合は内部paidへ更新せずFAILED")
    void processPaymentSyncJob_amountMismatch_rejected() {
        IntegrationJob purchaseJob = purchaseIntegrationService.triggerBpPurchaseSync(bpPayment.getId(), 1L);
        jobService.claimJob(purchaseJob.getId());
        jobService.markSucceeded(purchaseJob.getId(), "88900", "req-bp-100", "仕入登録完了");

        IntegrationJob syncJob = purchaseIntegrationService.triggerPaymentSync(bpPayment.getId(), 1L);

        // 外部決済金額が 750,000 円 (内部は 800,000 円)
        String paymentsResponseJson = "{\"deal\": {\"id\": 88900, \"payments\": [{\"id\": 5502, \"date\": \"2026-08-25\", \"amount\": 750000, \"from_walletable_id\": 101}]}}";

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals/88900?company_id=99001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(paymentsResponseJson, MediaType.APPLICATION_JSON));

        purchaseIntegrationService.processPaymentSyncJob(syncJob.getId());

        mockServer.verify();
        IntegrationJob updatedSync = jobService.getById(syncJob.getId());
        assertThat(updatedSync.getStatus()).isEqualTo("FAILED");
        assertThat(updatedSync.getErrorCode()).isIn("PAYMENT_AMOUNT_MISMATCH", "AMOUNT_MISMATCH");

        // 内部 BpPayment は「未払」のままであること
        BpPayment notUpdated = bpPaymentMapper.selectById(bpPayment.getId());
        assertThat(notUpdated.getStatus()).isEqualTo("未払");
        assertThat(notUpdated.getPaidDate()).isNull();
    }

    @Test
    @DisplayName("BP支払適格性ガード: 未承認・下書き状態のBP支払レコードは連携拒否される")
    void triggerBpPurchaseSync_unapprovedStatus_rejected() {
        WorkRecord unapprovedWr = new WorkRecord();
        unapprovedWr.setContractId(1L);
        unapprovedWr.setWorkMonth("2026-08");
        unapprovedWr.setActualHours(new BigDecimal("160.00"));
        workRecordMapper.insert(unapprovedWr);

        BpPayment unapproved = new BpPayment();
        unapproved.setBpCompanyId(bpCompany.getId());
        unapproved.setWorkRecordId(unapprovedWr.getId());
        unapproved.setAmount(new BigDecimal("500000"));
        unapproved.setStatus("下書き"); // 未承認
        bpPaymentMapper.insert(unapproved);

        assertThatThrownBy(() -> purchaseIntegrationService.triggerBpPurchaseSync(unapproved.getId(), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未払または承認済のBP支払レコードのみ連携可能");
    }

    @Test
    @DisplayName("支払実績同期ガード: 外部取引ID (dealId) が不一致の場合は内部paidへ更新せずFAILED")
    void processPaymentSyncJob_dealIdMismatch_rejected() {
        IntegrationJob purchaseJob = purchaseIntegrationService.triggerBpPurchaseSync(bpPayment.getId(), 1L);
        jobService.claimJob(purchaseJob.getId());
        jobService.markSucceeded(purchaseJob.getId(), "88900", "req-bp-100", "仕入登録完了");

        IntegrationJob syncJob = purchaseIntegrationService.triggerPaymentSync(bpPayment.getId(), 1L);

        // 異なる dealId (99999 != 88900)
        String paymentsResponseJson = "{\"deal\": {\"id\": 99999, \"payments\": [{\"id\": 5503, \"date\": \"2026-08-25\", \"amount\": 800000}]}}";

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals/88900?company_id=99001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(paymentsResponseJson, MediaType.APPLICATION_JSON));

        purchaseIntegrationService.processPaymentSyncJob(syncJob.getId());

        mockServer.verify();
        IntegrationJob updatedSync = jobService.getById(syncJob.getId());
        assertThat(updatedSync.getStatus()).isEqualTo("FAILED");
        assertThat(updatedSync.getErrorCode()).isEqualTo("DEAL_ID_MISMATCH");
    }

    @Test
    @DisplayName("要員経費連携: 承認済経費申請がfreeeへ送信され、成功時にステータスが会計連携済に更新される")
    void triggerExpenseSync_and_processExpenseJob_success() {
        // マッピング登録
        ExternalMapping expAccount = new ExternalMapping();
        expAccount.setConnectionId(connection.getId());
        expAccount.setObjectType("ACCOUNT_EXPENSE");
        expAccount.setInternalCode("EXPENSE_DEFAULT");
        expAccount.setExternalId("2201");
        mappingService.saveOrUpdateMapping(expAccount);
        mappingService.verifyMapping(mappingService.getMapping(connection.getId(), "ACCOUNT_EXPENSE", "EXPENSE_DEFAULT").getId(), "{\"verified\":true}");

        ExternalMapping taxMap = new ExternalMapping();
        taxMap.setConnectionId(connection.getId());
        taxMap.setObjectType("TAX_PURCHASE_10");
        taxMap.setInternalCode("TAX_PURCHASE_10");
        taxMap.setExternalId("108");
        mappingService.saveOrUpdateMapping(taxMap);
        mappingService.verifyMapping(mappingService.getMapping(connection.getId(), "TAX_PURCHASE_10", "TAX_PURCHASE_10").getId(), "{\"verified\":true}");

        ExpenseRequest exp = new ExpenseRequest();
        exp.setEngineerId(1001L);
        exp.setExpenseNo("EX-202608-999");
        exp.setExpenseDate(LocalDate.of(2026, 8, 10));
        exp.setCategory("交通費");
        exp.setAmount(new BigDecimal("12500"));
        exp.setDescription("顧客訪問交通費");
        exp.setStatus("承認済");
        expenseRequestMapper.insert(exp);

        IntegrationJob job = purchaseIntegrationService.triggerExpenseSync(exp.getId(), 1L);
        assertThat(job).isNotNull();
        assertThat(job.getJobType()).isEqualTo("EXPENSE_DEAL_SYNC");

        String responseJson = "{\"deal\": {\"id\": 77711, \"amount\": 12500, \"status\": \"unsettled\"}}";
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        purchaseIntegrationService.processExpenseJob(job.getId());

        mockServer.verify();
        IntegrationJob updatedJob = jobService.getById(job.getId());
        assertThat(updatedJob.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(updatedJob.getExternalId()).isEqualTo("77711");

        ExpenseRequest updatedExp = expenseRequestMapper.selectById(exp.getId());
        assertThat(updatedExp.getStatus()).isEqualTo("会計連携済");
    }

    @Test
    @DisplayName("月次締めガード: 締め済み月の工数に紐づくBP支払の連携トリガーは拒否される")
    void triggerBpPurchaseSync_closedMonth_rejected() {
        Contract closedContract = new Contract();
        closedContract.setContractNo("CON-CLOSED-" + UUID.randomUUID().toString().substring(0, 8));
        closedContract.setEngineerId(1L);
        closedContract.setProjectId(1L);
        closedContract.setCustomerId(1L);
        closedContract.setContractType("準委任");
        closedContract.setStatus("稼動中");
        closedContract.setStartDate(LocalDate.of(2025, 1, 1));
        closedContract.setEndDate(LocalDate.of(2025, 12, 31));
        closedContract.setSellingPrice(new BigDecimal("700000"));
        closedContract.setCostPrice(new BigDecimal("600000"));
        contractMapper.insert(closedContract);

        WorkRecord closedWr = new WorkRecord();
        closedWr.setContractId(closedContract.getId());
        closedWr.setWorkMonth("2025-01");
        closedWr.setActualHours(new BigDecimal("160.00"));
        workRecordMapper.insert(closedWr);

        BpPayment closedPayment = new BpPayment();
        closedPayment.setBpCompanyId(bpCompany.getId());
        closedPayment.setWorkRecordId(closedWr.getId());
        closedPayment.setAmount(new BigDecimal("600000"));
        closedPayment.setStatus("未払");
        bpPaymentMapper.insert(closedPayment);

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

        assertThatThrownBy(() -> purchaseIntegrationService.triggerBpPurchaseSync(closedPayment.getId(), 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("BP仕入決定論的日付と経費CAS検証 (R1-P1-08 / design §2.2, §3.2)")
    void bpPurchase_deterministicBusinessDate_and_expenseCas() {
        // 1. BP仕入の決定論的日付: 対象月 (2026-08) の末日 (2026-08-31) に固定されること
        IntegrationJob bpJob = purchaseIntegrationService.triggerBpPurchaseSync(bpPayment.getId(), 1L);
        assertThat(bpJob.getPayloadSnapshot()).contains("\"issueDate\":\"2026-08-31\"");

        // 2. 経費申請の CAS 検証
        ExpenseRequest expense = new ExpenseRequest();
        expense.setExpenseNo("EX-CAS-001");
        expense.setEngineerId(1L);
        expense.setCategory("交通費");
        expense.setAmount(new BigDecimal("15000"));
        expense.setExpenseDate(LocalDate.of(2026, 8, 10));
        expense.setDescription("出張旅費");
        expense.setStatus("承認済");
        expenseRequestMapper.insert(expense);

        // マッピング登録
        ExternalMapping expAccount = new ExternalMapping();
        expAccount.setConnectionId(connection.getId());
        expAccount.setObjectType("ACCOUNT_EXPENSE");
        expAccount.setInternalCode("EXPENSE_DEFAULT");
        expAccount.setExternalId("2003");
        expAccount.setExternalCode("旅費交通費");
        mappingService.saveOrUpdateMapping(expAccount);
        ExternalMapping savedExpAccount = mappingService.getMapping(connection.getId(), "ACCOUNT_EXPENSE", "EXPENSE_DEFAULT");
        mappingService.verifyMapping(savedExpAccount.getId(), "{\"verified\":true}");

        IntegrationJob expJob = purchaseIntegrationService.triggerExpenseSync(expense.getId(), 1L);

        // 外部 API 成功をモック
        String responseJson = "{\"deal\": {\"id\": 889900, \"company_id\": 99001, \"amount\": 15000, \"status\": \"unsettled\"}}";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Freee-Request-ID", "req-exp-cas-001");
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON).headers(headers));

        // シミュレーション: Worker 実行直前に経費ステータスが "下書き" へ変更された (CAS 不一致)
        expense.setStatus("下書き");
        expenseRequestMapper.updateById(expense);

        // Worker 実行
        purchaseIntegrationService.processExpenseJob(expJob.getId());

        // CAS 失敗検知 (CAS_CONFLICT)
        IntegrationJob failedJob = jobService.getById(expJob.getId());
        assertThat(failedJob.getStatus()).isEqualTo("FAILED");
        assertThat(failedJob.getErrorCode()).isEqualTo("CAS_CONFLICT");
    }

    @Test
    @DisplayName("Snapshot必須・ハッシュ検証: NULL snapshot / 改変 snapshot は外部送信せず fail-closed (R1-P1-07)")
    void worker_snapshotRequiredAndHashVerified() {
        // NULL snapshot (レガシージョブ相当) -> 送信せず FAILED (Worker が内部で claim する)
        IntegrationJob legacy = jobService.createJob(connection.getId(), "BP_PURCHASE_SYNC", "BP_PAYMENT",
                bpPayment.getId(), "BP-LEGACY-SNAP", "hash-legacy");
        purchaseIntegrationService.processBpPurchaseJob(legacy.getId());
        IntegrationJob legacyAfter = jobService.getById(legacy.getId());
        assertThat(legacyAfter.getStatus()).isEqualTo("FAILED");
        assertThat(legacyAfter.getErrorCode()).isEqualTo("LEGACY_SNAPSHOT_MISSING");

        // 改変 snapshot (SHA-256 不一致) -> 送信せず FAILED
        IntegrationJob tampered = jobService.createJob(connection.getId(), "BP_PURCHASE_SYNC", "BP_PAYMENT",
                bpPayment.getId(), "BP-TAMPER-SNAP", "hash-tampered",
                "{\"bpPaymentId\":999,\"amount\":1}", connection.getTenantId(), connection.getLegalEntityId(), null);
        purchaseIntegrationService.processBpPurchaseJob(tampered.getId());
        IntegrationJob tamperedAfter = jobService.getById(tampered.getId());
        assertThat(tamperedAfter.getStatus()).isEqualTo("FAILED");
        assertThat(tamperedAfter.getErrorCode()).isEqualTo("PAYLOAD_HASH_MISMATCH");

        // 経費 Worker も NULL snapshot は fail-closed
        IntegrationJob legacyExp = jobService.createJob(connection.getId(), "EXPENSE_DEAL_SYNC", "EXPENSE_REQUEST",
                999999L, "EX-LEGACY-SNAP", "hash-exp-legacy");
        purchaseIntegrationService.processExpenseJob(legacyExp.getId());
        IntegrationJob legacyExpAfter = jobService.getById(legacyExp.getId());
        assertThat(legacyExpAfter.getStatus()).isEqualTo("FAILED");
        assertThat(legacyExpAfter.getErrorCode()).isEqualTo("LEGACY_SNAPSHOT_MISSING");

        // 外部 API は一切呼ばれていないこと
        mockServer.verify();
    }

    @Test
    @DisplayName("支払Workerはsnapshotのみから外部取引IDと金額を解決する (最新purchase job再読込の全廃) (R1-P1-07)")
    void paymentWorker_usesSnapshotOnly_notLatestJob() throws Exception {
        // 先行する仕入連携ジョブは dealId=88899 で SUCCEEDED 済み (Workerが再読込するならこちらを使う)
        IntegrationJob purchaseJob = purchaseIntegrationService.triggerBpPurchaseSync(bpPayment.getId(), 1L);
        jobService.claimJob(purchaseJob.getId());
        jobService.markSucceeded(purchaseJob.getId(), "88899", "req-bp-snap", "仕入登録完了");

        // snapshot にのみ dealId=77777 / expectedAmount=800000 を保持する PAYMENT_SYNC ジョブ
        String payload = "{\"bpPaymentId\":" + bpPayment.getId() + ",\"externalDealId\":\"77777\",\"expectedAmount\":800000}";
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        IntegrationJob syncJob = jobService.createJob(connection.getId(), "PAYMENT_SYNC", "BP_PAYMENT",
                bpPayment.getId(), "PAY-SNAP-ONLY", hash, payload,
                connection.getTenantId(), connection.getLegalEntityId(), null);

        // snapshot の dealId (77777) のみ応答する。最新purchase job (88899) への参照が残っていれば verify で失敗する
        String paymentsResponseJson = "{\"deal\": {\"id\": 77777, \"payments\": [{\"id\": 5503, \"date\": \"2026-08-25\", \"amount\": 800000}]}}";
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals/77777?company_id=99001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(paymentsResponseJson, MediaType.APPLICATION_JSON));

        purchaseIntegrationService.processPaymentSyncJob(syncJob.getId());

        mockServer.verify();
        IntegrationJob updatedSync = jobService.getById(syncJob.getId());
        assertThat(updatedSync.getStatus()).isEqualTo("SUCCEEDED");
        BpPayment updatedPayment = bpPaymentMapper.selectById(bpPayment.getId());
        assertThat(updatedPayment.getStatus()).isEqualTo("支払済");
    }

    @Test
    @DisplayName("実決済日欠落: payments[].dateが無い場合はissue_date/現在日付へ代用せずPAYMENT_DATE_MISSINGで拒否 (R1-P1-08)")
    void paymentWorker_missingPaymentDate_rejected() throws Exception {
        String payload = "{\"bpPaymentId\":" + bpPayment.getId() + ",\"externalDealId\":\"66611\",\"expectedAmount\":800000}";
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        IntegrationJob syncJob = jobService.createJob(connection.getId(), "PAYMENT_SYNC", "BP_PAYMENT",
                bpPayment.getId(), "PAY-PDATE-NULL-1", hash, payload,
                connection.getTenantId(), connection.getLegalEntityId(), null);

        // ケース1: payments[] に date が無い (deal.issue_date はあるが代用しない)
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals/66611?company_id=99001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"deal\": {\"id\": 66611, \"issue_date\": \"2026-08-25\", \"status\": \"settled\", \"payments\": [{\"id\": 6601, \"amount\": 800000}]}}", MediaType.APPLICATION_JSON));
        purchaseIntegrationService.processPaymentSyncJob(syncJob.getId());
        IntegrationJob after = jobService.getById(syncJob.getId());
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getErrorCode()).isEqualTo("PAYMENT_DATE_MISSING");
        assertThat(bpPaymentMapper.selectById(bpPayment.getId()).getStatus()).isEqualTo("未払");

        // ケース2: payments[] 自体が無い (deal.status=settled でも date 不明)
        mockServer.reset();
        String payload2 = "{\"bpPaymentId\":" + bpPayment.getId() + ",\"externalDealId\":\"66612\",\"expectedAmount\":800000}";
        String hash2 = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payload2.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        IntegrationJob syncJob2 = jobService.createJob(connection.getId(), "PAYMENT_SYNC", "BP_PAYMENT",
                bpPayment.getId(), "PAY-PDATE-NULL-2", hash2, payload2,
                connection.getTenantId(), connection.getLegalEntityId(), null);
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals/66612?company_id=99001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"deal\": {\"id\": 66612, \"amount\": 800000, \"status\": \"settled\"}}", MediaType.APPLICATION_JSON));
        purchaseIntegrationService.processPaymentSyncJob(syncJob2.getId());
        IntegrationJob after2 = jobService.getById(syncJob2.getId());
        assertThat(after2.getStatus()).isEqualTo("FAILED");
        assertThat(after2.getErrorCode()).isEqualTo("PAYMENT_DATE_MISSING");
        assertThat(bpPaymentMapper.selectById(bpPayment.getId()).getStatus()).isEqualTo("未払");

        // ケース3: 正常な決済日 (payments[].date あり) -> SUCCEEDED で 支払済
        mockServer.reset();
        String payload3 = "{\"bpPaymentId\":" + bpPayment.getId() + ",\"externalDealId\":\"66613\",\"expectedAmount\":800000}";
        String hash3 = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payload3.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        IntegrationJob syncJob3 = jobService.createJob(connection.getId(), "PAYMENT_SYNC", "BP_PAYMENT",
                bpPayment.getId(), "PAY-PDATE-OK-3", hash3, payload3,
                connection.getTenantId(), connection.getLegalEntityId(), null);
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals/66613?company_id=99001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"deal\": {\"id\": 66613, \"status\": \"settled\", \"payments\": [{\"id\": 6603, \"date\": \"2026-08-25\", \"amount\": 800000}]}}", MediaType.APPLICATION_JSON));
        purchaseIntegrationService.processPaymentSyncJob(syncJob3.getId());
        IntegrationJob after3 = jobService.getById(syncJob3.getId());
        assertThat(after3.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(bpPaymentMapper.selectById(bpPayment.getId()).getStatus()).isEqualTo("支払済");
    }
    @Test
    @DisplayName("テナント別タイムゾーン解決とWorkerコンテキスト解除・NULL金額/日付のfail-closed (R1-P1-08)")
    void tenantTimezoneResolution_contextClearedAfterWorker_nullRejected() {
        // 1. m_system_config のテナント別タイムゾーン解決
        com.ses.entity.SystemConfig nyConfig = new com.ses.entity.SystemConfig();
        nyConfig.setConfigKey("accounting.timezone.ny-tenant");
        nyConfig.setConfigValue("America/New_York");
        systemConfigMapper.insert(nyConfig);

        assertThat(timezoneResolver.resolve("ny-tenant")).isEqualTo(java.time.ZoneId.of("America/New_York"));
        assertThat(timezoneResolver.resolve("tokyo-tenant")).isEqualTo(java.time.ZoneId.of("Asia/Tokyo"));

        // 2. 非 default テナントの BP ジョブを Worker 実行しても ThreadLocal コンテキストがリークしない
        IntegrationJob src = purchaseIntegrationService.triggerBpPurchaseSync(bpPayment.getId(), 1L);
        IntegrationJob nyJob = jobService.createJob(connection.getId(), "BP_PURCHASE_SYNC", "BP_PAYMENT",
                bpPayment.getId(), "BP-TENANT-NY-1", src.getPayloadHash(), src.getPayloadSnapshot(),
                "ny-tenant", null, null);
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"deal\": {\"id\": 654321, \"amount\": 800000}}", MediaType.APPLICATION_JSON));
        purchaseIntegrationService.processBpPurchaseJob(nyJob.getId());
        mockServer.verify();

        // Worker 完了後は tenant/zone とも既定へ戻っている (スレッドプール再利用時のリーク防止)
        assertThat(AccountingTenantContextHolder.getCurrentTenantId()).isEqualTo("default");
        assertThat(AccountingTenantContextHolder.getZoneId()).isEqualTo(java.time.ZoneId.of("Asia/Tokyo"));

        // 外側コンテキストがある場合はネスト復帰する
        AccountingTenantContextHolder.runWithTenant("outer-tenant", java.time.ZoneId.of("Europe/London"), () -> {
            assertThat(AccountingTenantContextHolder.getCurrentTenantId()).isEqualTo("outer-tenant");
        });
        assertThat(AccountingTenantContextHolder.getCurrentTenantId()).isEqualTo("default");

        // 3. NULL 金額 BP / NULL 経費日付は enqueue 時に fail-closed (カラムNOT NULLのため、直接INSERTできない行をspyで検証)
        //    -> 専用テストクラス AccountingNullGuardTest で検証 (DB制約上、NULL行は本来存在しない防御的ガード)

        // 4. BP 連携は冪等キーにより同一ジョブへ収束し、payload_hash が再実行で変動しない (翌日 retry でも不変)
        IntegrationJob again = purchaseIntegrationService.triggerBpPurchaseSync(bpPayment.getId(), 1L);
        assertThat(again.getId()).isEqualTo(src.getId());
        assertThat(again.getPayloadHash()).isEqualTo(src.getPayloadHash());
        assertThat(again.getPayloadSnapshot()).contains("\"issueDate\":\"2026-08-31\"");
    }
}
