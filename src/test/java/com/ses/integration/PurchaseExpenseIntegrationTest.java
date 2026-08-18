package com.ses.integration;

import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.entity.*;
import com.ses.mapper.BpBankAccountMapper;
import com.ses.mapper.BpCompanyMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.mapper.WorkRecordMapper;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@ActiveProfiles("test")
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
    private SystemConfigMapper systemConfigMapper;

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
        assertThat(updatedSync.getErrorCode()).isEqualTo("PAYMENT_AMOUNT_MISMATCH");

        // 内部 BpPayment は「未払」のままであること
        BpPayment notUpdated = bpPaymentMapper.selectById(bpPayment.getId());
        assertThat(notUpdated.getStatus()).isEqualTo("未払");
        assertThat(notUpdated.getPaidDate()).isNull();
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
}
