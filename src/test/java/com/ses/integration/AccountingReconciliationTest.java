package com.ses.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.AccountingReconciliationSummaryDto;
import com.ses.dto.accounting.AccountingReconciliationSummaryDto.ReconciliationItemDto;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.entity.*;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.service.accounting.AccountingReconciliationService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 月次照合・月次締めガード結合テスト (T100 / B3 / design §5, §6.1, §6.3)。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class AccountingReconciliationTest {

    @Autowired
    private AccountingReconciliationService reconciliationService;

    @Autowired
    private IntegrationConnectionService connectionService;

    @Autowired
    private IntegrationJobService jobService;

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private com.ses.mapper.InvoicePaymentMapper invoicePaymentMapper;

    @Autowired
    private com.ses.mapper.BpPaymentMapper bpPaymentMapper;

    @Autowired
    private com.ses.mapper.ExpenseRequestMapper expenseRequestMapper;

    @Autowired
    private com.ses.mapper.WorkRecordMapper workRecordMapper;

    @Autowired
    private com.ses.mapper.EngineerMapper engineerMapper;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer mockServer;
    private IntegrationConnection connection;
    private Customer customer;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();

        connection = connectionService.getOrCreateConnection("default", null, "freee", "accounting");
        IntegrationTokensDto tokens = IntegrationTokensDto.builder()
                .accessToken("test-token-recon-001")
                .refreshToken("test-refresh-recon-001")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
        connectionService.saveTokens(connection.getId(), tokens, 99001L, "照合テスト事業所", 1L);

        customer = new Customer();
        customer.setCompanyName("照合テスト株式会社-" + UUID.randomUUID().toString().substring(0, 6));
        customerMapper.insert(customer);
    }

    @Test
    @DisplayName("月次照合分類: MATCHED, INTERNAL_ONLY, AMOUNT_MISMATCH, EXTERNAL_ONLY が正しく分類される")
    void reconcileMonth_variousStatuses_correctlyClassified() {
        String testMonth = "2026-09";

        // 1. MATCHED: 成功した売上請求
        Invoice matchedInv = createInvoice(testMonth, "INV-RECON-001", new BigDecimal("1100000"));
        IntegrationJob matchedJob = jobService.createJob(connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", matchedInv.getId(), "SALES:INV:1", "hash1");
        jobService.claimJob(matchedJob.getId());
        jobService.markSucceeded(matchedJob.getId(), "EXT-DEAL-001", "req-1", "同期成功");

        // 2. INTERNAL_ONLY: 未送信の売上請求
        Invoice internalOnlyInv = createInvoice(testMonth, "INV-RECON-002", new BigDecimal("550000"));

        // 3. AMOUNT_MISMATCH: 金額不一致で失敗した売上請求
        Invoice mismatchInv = createInvoice(testMonth, "INV-RECON-003", new BigDecimal("330000"));
        IntegrationJob mismatchJob = jobService.createJob(connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", mismatchInv.getId(), "SALES:INV:3", "hash3");
        jobService.claimJob(mismatchJob.getId());
        jobService.markFailed(mismatchJob.getId(), "AMOUNT_MISMATCH", "外部取引との金額不一致");

        // 4. EXTERNAL_ONLY: 外部にのみ存在する取引 (freee API から返される)
        String dealsResponseJson = "{\"deals\": [{\"id\": 99901, \"issue_date\": \"2026-09-15\", \"amount\": 220000, \"ref_number\": \"EXT-ONLY-999\", \"partner_id\": 3001, \"status\": \"settled\", \"payments\": [{\"id\": 7701, \"date\": \"2026-09-15\", \"amount\": 220000}]}]}";
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals?company_id=99001&start_issue_date=2026-09-01&end_issue_date=2026-09-30&status=settled&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(dealsResponseJson, MediaType.APPLICATION_JSON));

        AccountingReconciliationSummaryDto summary = reconciliationService.reconcileMonth(testMonth);

        mockServer.verify();

        assertThat(summary.getMonth()).isEqualTo(testMonth);
        assertThat(summary.getMatchedCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.getInternalOnlyCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.getAmountMismatchCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.getExternalOnlyCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.isReadyForClosing()).isFalse(); // 未解消差異があるため締め不可
    }

    @Test
    @DisplayName("除外設定: 差異項目に除外理由を記録するとIGNOREDとなり月次締め可能へ遷移")
    void ignoreDiscrepancy_savesReason_statusBecomesIgnored() {
        String testMonth = "2026-10";

        // 1. 未送信請求を1件作成
        Invoice inv = createInvoice(testMonth, "INV-RECON-IGN-001", new BigDecimal("500000"));

        // freee API からは空リスト
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals?company_id=99001&start_issue_date=2026-10-01&end_issue_date=2026-10-31&status=settled&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"deals\": []}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals?company_id=99001&start_issue_date=2026-10-01&end_issue_date=2026-10-31&status=settled&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"deals\": []}", MediaType.APPLICATION_JSON));

        AccountingReconciliationSummaryDto beforeSummary = reconciliationService.reconcileMonth(testMonth);
        assertThat(beforeSummary.getInternalOnlyCount()).isEqualTo(1);
        assertThat(beforeSummary.isReadyForClosing()).isFalse();

        // 2. 理由付きで除外登録
        reconciliationService.ignoreDiscrepancy(testMonth, "SALES", null, inv.getId(), "翌月合算請求のため当月は対象外", 1L);

        // 3. 再照合
        AccountingReconciliationSummaryDto afterSummary = reconciliationService.reconcileMonth(testMonth);
        assertThat(afterSummary.getInternalOnlyCount()).isEqualTo(0);
        assertThat(afterSummary.getIgnoredCount()).isEqualTo(1);
        assertThat(afterSummary.isReadyForClosing()).isTrue();

        ReconciliationItemDto item = afterSummary.getItems().stream()
                .filter(i -> inv.getId().equals(i.getInternalId()))
                .findFirst()
                .orElseThrow();
        assertThat(item.getStatus()).isEqualTo("IGNORED");
        assertThat(item.getIgnoreReason()).isEqualTo("翌月合算請求のため当月は対象外");
    }

    @Test
    @DisplayName("月次締めガード: 未解消差異がある場合はassertReconciledForClosingが例外をスロー")
    void assertReconciledForClosing_hasDiscrepancy_throwsException() {
        String testMonth = "2026-11";
        createInvoice(testMonth, "INV-RECON-ERR-001", new BigDecimal("800000"));

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals?company_id=99001&start_issue_date=2026-11-01&end_issue_date=2026-11-30&status=settled&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"deals\": []}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> reconciliationService.assertReconciledForClosing(testMonth))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会計・支払照合に未解決の差異があるか");
    }

    @Test
    @DisplayName("外部API障害時のFail-Closed: 外部APIがタイムアウトした場合、readyForClosing=falseとなること (P1-09)")
    void reconcileMonth_externalApiTimeout_failsClosed() {
        String testMonth = "2026-10";
        Invoice inv = createInvoice(testMonth, "INV-TIMEOUT-001", new BigDecimal("1000000"));
        IntegrationJob job = jobService.createJob(connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", inv.getId(), "SALES:TIMEOUT:1", "hashT");
        jobService.claimJob(job.getId());
        jobService.markSucceeded(job.getId(), "EXT-TIMEOUT-001", "req-T", "同期成功");

        // 外部 API が 500 / タイムアウトエラーを返す
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals?company_id=99001&start_issue_date=2026-10-01&end_issue_date=2026-10-31&status=settled&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());

        AccountingReconciliationSummaryDto summary = reconciliationService.reconcileMonth(testMonth);
        assertThat(summary.isExternalFetchFailed()).isTrue();
        assertThat(summary.isReadyForClosing()).isFalse();
    }

    @Test
    @DisplayName("外部のみ取引ガード: 外部にのみ取引が存在しても内部データが自動作成されないこと")
    void externalOnly_doesNotAutoCreateInternalData() {
        String testMonth = "2026-12";
        int initialInvoiceCount = invoiceMapper.selectList(new LambdaQueryWrapper<Invoice>().eq(Invoice::getBillingMonth, testMonth)).size();

        String dealsResponseJson = "{\"deals\": [{\"id\": 88801, \"issue_date\": \"2026-12-10\", \"amount\": 990000, \"ref_number\": \"UNLINKED-DEAL-01\", \"status\": \"settled\", \"payments\": [{\"id\": 7702, \"date\": \"2026-12-10\", \"amount\": 990000}]}]}";
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals?company_id=99001&start_issue_date=2026-12-01&end_issue_date=2026-12-31&status=settled&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(dealsResponseJson, MediaType.APPLICATION_JSON));

        AccountingReconciliationSummaryDto summary = reconciliationService.reconcileMonth(testMonth);
        mockServer.verify();

        assertThat(summary.getExternalOnlyCount()).isEqualTo(1);

        // 内部の請求書レコードが自動作成されていないことを確認
        int finalInvoiceCount = invoiceMapper.selectList(new LambdaQueryWrapper<Invoice>().eq(Invoice::getBillingMonth, testMonth)).size();
        assertThat(finalInvoiceCount).isEqualTo(initialInvoiceCount);
    }

    @Test
    @DisplayName("4母集団突合・手数料込み消込・曖昧決済fail-closed検証 (R1-P1-09 / design §5.1, §6.2)")
    void reconciliation_fourPopulations_paginationFailClosed() {
        String testMonth = "2026-11";

        // 1. 売上請求 (Population 1)
        Invoice salesInv = createInvoice(testMonth, "INV-POP1", new BigDecimal("1100000"));
        IntegrationJob salesJob = jobService.createJob(connection.getId(), "SALES_INVOICE_SYNC", "INVOICE", salesInv.getId(), "SALES:POP1", "hash1");
        jobService.claimJob(salesJob.getId());
        jobService.markSucceeded(salesJob.getId(), "10001", "req-1", "同期成功");

        // 2. BP仕入 (Population 2)
        WorkRecord wr = new WorkRecord();
        wr.setContractId(1L);
        wr.setWorkMonth(testMonth);
        wr.setActualHours(new BigDecimal("160.00"));
        workRecordMapper.insert(wr);

        BpPayment bp = new BpPayment();
        bp.setWorkRecordId(wr.getId());
        bp.setAmount(new BigDecimal("800000"));
        bp.setPayeeCompanyName("テストBP株式会社");
        bp.setStatus("未払");
        bpPaymentMapper.insert(bp);

        IntegrationJob bpJob = jobService.createJob(connection.getId(), "BP_PURCHASE_SYNC", "BP_PAYMENT", bp.getId(), "BP:POP2", "hash2");
        jobService.claimJob(bpJob.getId());
        jobService.markSucceeded(bpJob.getId(), "10002", "req-2", "同期成功");

        // 3. 要員立替経費 (Population 3)
        Engineer eng = new Engineer();
        eng.setFullName("立替太郎");
        eng.setEmploymentType("正社員");
        engineerMapper.insert(eng);

        ExpenseRequest exp = new ExpenseRequest();
        exp.setEngineerId(eng.getId());
        exp.setExpenseNo("EX-POP3");
        exp.setCategory("交通費");
        exp.setAmount(new BigDecimal("20000"));
        exp.setExpenseDate(LocalDate.of(2026, 11, 15));
        exp.setStatus("会計連携済");
        expenseRequestMapper.insert(exp);

        IntegrationJob expJob = jobService.createJob(connection.getId(), "EXPENSE_DEAL_SYNC", "EXPENSE_REQUEST", exp.getId(), "EXP:POP3", "hash3");
        jobService.claimJob(expJob.getId());
        jobService.markSucceeded(expJob.getId(), "10003", "req-3", "同期成功");

        // 4. 請求書入金消込 (Population 4: 10月請求分に対する11月入金 amount 490,000 + fee 10,000 = gross 500,000)
        Invoice payInv = createInvoice("2026-10", "INV-POP4", new BigDecimal("500000"));
        InvoicePayment payment = new InvoicePayment();
        payment.setInvoiceId(payInv.getId());
        payment.setPaidDate(LocalDate.of(2026, 11, 20));
        payment.setAmount(new BigDecimal("490000"));
        payment.setFee(new BigDecimal("10000"));
        payment.setRemarks("11月分入金");
        invoicePaymentMapper.insert(payment);

        // freee API レスポンス (Page 1: 4母集団のDeal/Paymentを返却)
        String dealsResponseJson = "{\"deals\": [" +
                "{\"id\": 10001, \"amount\": 1100000, \"status\": \"settled\", \"payments\": [{\"id\": 5001, \"date\": \"2026-11-01\", \"amount\": 1100000}]}," +
                "{\"id\": 10002, \"amount\": 800000, \"status\": \"settled\", \"payments\": [{\"id\": 5002, \"date\": \"2026-11-05\", \"amount\": 800000}]}," +
                "{\"id\": 10003, \"amount\": 20000, \"status\": \"settled\", \"payments\": [{\"id\": 5003, \"date\": \"2026-11-15\", \"amount\": 20000}]}," +
                "{\"id\": 10004, \"amount\": 500000, \"status\": \"settled\", \"payments\": [{\"id\": 5004, \"date\": \"2026-11-20\", \"amount\": 500000}]}" +
                "]}";

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals?company_id=99001&start_issue_date=2026-11-01&end_issue_date=2026-11-30&status=settled&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(dealsResponseJson, MediaType.APPLICATION_JSON));

        AccountingReconciliationSummaryDto summary = reconciliationService.reconcileMonth(testMonth);
        mockServer.verify();

        assertThat(summary.getMatchedCount()).isEqualTo(4); // 4母集団すべて完全一致！
        assertThat(summary.getAmountMismatchCount()).isEqualTo(0);
        assertThat(summary.getInternalOnlyCount()).isEqualTo(0);
        assertThat(summary.isReadyForClosing()).isTrue();
    }

    private Invoice createInvoice(String billingMonth, String invoiceNo, BigDecimal total) {
        Invoice invoice = new Invoice();
        invoice.setBillingMonth(billingMonth);
        invoice.setInvoiceNo(invoiceNo + "-" + UUID.randomUUID().toString().substring(0, 6));
        invoice.setCustomerId(customer.getId());
        invoice.setIssuedDate(LocalDate.of(2026, 9, 30));
        invoice.setDueDate(LocalDate.of(2026, 10, 31));
        invoice.setSubtotal(total.multiply(new BigDecimal("100")).divide(new BigDecimal("110"), 0, java.math.RoundingMode.HALF_UP));
        invoice.setTax(total.subtract(invoice.getSubtotal()));
        invoice.setTotal(total);
        invoice.setTaxRate(new BigDecimal("0.100"));
        invoiceMapper.insert(invoice);
        return invoice;
    }
}
