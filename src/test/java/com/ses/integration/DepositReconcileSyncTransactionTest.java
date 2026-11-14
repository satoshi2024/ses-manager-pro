package com.ses.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.dto.invoice.InvoicePaymentDeleteResult;
import com.ses.entity.BankDeposit;
import com.ses.entity.Customer;
import com.ses.entity.ExternalMapping;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.entity.IntegrationJobEvent;
import com.ses.entity.Invoice;
import com.ses.mapper.BankDepositMapper;
import com.ses.service.CustomerService;
import com.ses.service.InvoiceService;
import com.ses.service.accounting.AccountingIntegrationWorker;
import com.ses.service.accounting.SalesInvoiceIntegrationService;
import com.ses.service.billing.PaymentReconciliationService;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 入金消込（FR-09）とfreeeへの反映（DEPOSIT_RECONCILE_SYNC）を、実サービス・実Springトランザクション（H2）で通す。
 *
 * <p>以前は Mockito の呼出し確認だけで、反映ジョブの登録失敗がSESの消込ごと巻き戻ること（入金を削除して同じ請求書へ
 * 消込し直すと冪等キーの衝突で409になる）や、消込の取消後に残った反映ジョブがfreeeへ送られることを検出できなかった。
 * 外側のトランザクションが rollback-only にされていないことは、スレッドに束縛された ConnectionHolder で確かめる
 * （テストのトランザクションは最後に必ず rollback されるため、例外が出ないことだけでは確かめられない）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DepositReconcileSyncTransactionTest {

    private static final BigDecimal INVOICE_TOTAL = new BigDecimal("1100000");

    @Autowired
    private PaymentReconciliationService reconciliationService;
    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private SalesInvoiceIntegrationService salesIntegrationService;
    @Autowired
    private IntegrationJobService jobService;
    @Autowired
    private IntegrationConnectionService connectionService;
    @Autowired
    private ExternalMappingService mappingService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private BankDepositMapper bankDepositMapper;
    @Autowired
    private AccountingIntegrationWorker worker;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockRestServiceServer mockServer;
    private IntegrationConnection connection;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        TenantTestSecurity.bindAs(1L, "deposit-reconcile-test", "default", "管理者");
        TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L, 1L);
        mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();

        connection = connectionService.getOrCreateConnection("default", null, "freee", "accounting");
        connectionService.saveTokens(connection.getId(), IntegrationTokensDto.builder()
                .accessToken("test-token-deposit-001")
                .refreshToken("test-refresh-deposit-001")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build(), 99001L, "入金消込テスト事業所", 1L);

        Customer customer = new Customer();
        customer.setCompanyName("入金消込反映テスト顧客");
        customerService.save(customer);

        invoice = new Invoice();
        invoice.setInvoiceNo("INV-DEP-" + UUID.randomUUID().toString().substring(0, 8));
        invoice.setCustomerId(customer.getId());
        invoice.setBillingMonth("2026-08");
        invoice.setIssuedDate(LocalDate.of(2026, 8, 1));
        invoice.setDueDate(LocalDate.of(2026, 8, 31));
        invoice.setSubtotal(new BigDecimal("1000000"));
        invoice.setTax(new BigDecimal("100000"));
        invoice.setTotal(INVOICE_TOTAL);
        invoice.setTaxRate(new BigDecimal("0.100"));
        invoice.setStatus("送付済");
        invoiceService.save(invoice);

        verifiedMapping("CUSTOMER_PARTNER", "CUST-" + customer.getId(), "1001");
        verifiedMapping("ACCOUNT_SALES", "SALES_DEFAULT", "2001");
        verifiedMapping("TAX_SALES_10", "TAX_10", "21");
    }

    @AfterEach
    void tearDown() {
        TenantTestSecurity.clear();
    }

    private void verifiedMapping(String objectType, String internalCode, String externalId) {
        ExternalMapping mapping = new ExternalMapping();
        mapping.setConnectionId(connection.getId());
        mapping.setObjectType(objectType);
        mapping.setInternalCode(internalCode);
        mapping.setExternalId(externalId);
        mapping.setExternalCode(objectType);
        mappingService.saveOrUpdateMapping(mapping);
        ExternalMapping saved = mappingService.getMapping(connection.getId(), objectType, internalCode);
        mappingService.verifyMapping(saved.getId(), "{\"verified\":true}");
    }

    /** 請求書をfreeeへ送信済みにする（freee取引IDを持つ送信成功ジョブ）。 */
    private void markInvoiceSentToFreee(String dealId) {
        IntegrationJob sync = salesIntegrationService.triggerSalesSync(invoice.getId(), 1L);
        jobService.claimJob(sync.getId());
        jobService.markSucceeded(sync.getId(), dealId, "req-sent", "売上登録完了");
    }

    /** freeeから取り込んだ未消込の入金明細（口座明細IDは他のテストと重ならない数字）。 */
    private BankDeposit insertDeposit() {
        BankDeposit deposit = new BankDeposit();
        deposit.setFreeeDepositId(String.valueOf(2_500_000_000L + Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        deposit.setDepositDate(LocalDate.of(2026, 9, 30));
        deposit.setAmount(INVOICE_TOTAL);
        deposit.setPayerName("ﾆﾕｳｷﾝｼﾖｳｺﾐﾃｽﾄ");
        deposit.setStatus("未消込");
        bankDepositMapper.insert(deposit);
        return deposit;
    }

    private BankDeposit reload(BankDeposit deposit) {
        return bankDepositMapper.selectById(deposit.getId());
    }

    private List<IntegrationJob> reconcileJobs(BankDeposit deposit) {
        return jobService.list(new LambdaQueryWrapper<IntegrationJob>()
                .eq(IntegrationJob::getJobType, "DEPOSIT_RECONCILE_SYNC")
                .eq(IntegrationJob::getTargetType, "BANK_DEPOSIT")
                .eq(IntegrationJob::getTargetId, deposit.getId())
                .orderByAsc(IntegrationJob::getId));
    }

    private String reconcileUrl(BankDeposit deposit) {
        return "https://api.freee.co.jp/api/1/wallet_txns/" + deposit.getFreeeDepositId() + "/reconcile";
    }

    /** スレッドに束縛された接続（テストのトランザクション）が rollback-only にされていないこと。 */
    private void assertOuterTransactionNotRollbackOnly() {
        List<ConnectionHolder> holders = TransactionSynchronizationManager.getResourceMap().values().stream()
                .filter(ConnectionHolder.class::isInstance)
                .map(ConnectionHolder.class::cast)
                .toList();
        assertThat(holders).as("テストのトランザクションに束縛された接続").isNotEmpty();
        assertThat(holders).noneMatch(ConnectionHolder::isRollbackOnly);
    }

    @Test
    @DisplayName("F1: 入金を削除して同じ請求書へ消込し直しても、SESの消込は成功し、有効な反映ジョブは新しい入金行の1件だけ")
    void 入金を削除して同じ請求書へ消込し直しても成功し有効な反映ジョブは1件だけ() {
        markInvoiceSentToFreee("668301");
        BankDeposit deposit = insertDeposit();

        reconciliationService.apply(deposit.getId(), invoice.getId());
        Long firstPaymentId = reload(deposit).getMatchedPaymentId();
        IntegrationJob first = reconcileJobs(deposit).get(0);
        assertThat(first.getIdempotencyKey())
                .isEqualTo("DEPOSIT_RECONCILE:" + deposit.getId() + ":" + firstPaymentId + ":668301");

        InvoicePaymentDeleteResult deleted = invoiceService.deletePayment(invoice.getId(), firstPaymentId);
        // まだfreeeへ送っていない反映ジョブは取消すだけで、freee側の対応は要らない
        assertThat(deleted.getWarnings()).isEmpty();
        assertThat(reload(deposit).getStatus()).isEqualTo("未消込");
        IntegrationJob cancelled = jobService.getById(first.getId());
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getErrorCode()).isEqualTo("DEPOSIT_RECONCILE_STALE");

        // 以前は冪等キーが入金明細×取引で固定され、ここで409になってSESの消込ごと巻き戻っていた
        reconciliationService.apply(deposit.getId(), invoice.getId());
        assertOuterTransactionNotRollbackOnly();

        BankDeposit reapplied = reload(deposit);
        assertThat(reapplied.getStatus()).isEqualTo("消込済");
        assertThat(reapplied.getMatchedPaymentId()).isNotEqualTo(firstPaymentId);
        List<IntegrationJob> active = reconcileJobs(deposit).stream()
                .filter(j -> !"CANCELLED".equals(j.getStatus()))
                .toList();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getIdempotencyKey())
                .isEqualTo("DEPOSIT_RECONCILE:" + deposit.getId() + ":" + reapplied.getMatchedPaymentId() + ":668301");
        assertThat(active.get(0).getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("F1: 反映ジョブの登録が例外で失敗しても、SESの消込は確定し、外側のトランザクションを rollback-only にしない（監査に残す）")
    void 反映ジョブの登録に失敗してもSESの消込は確定し外側のトランザクションを巻き戻さない() {
        markInvoiceSentToFreee("668302");
        BankDeposit deposit = insertDeposit();
        // 会計接続が一意に決まらない状態（登録処理の中で例外になる）
        jdbcTemplate.update("INSERT INTO m_integration_connection (tenant_id, provider, product, external_company_id, company_name, status) "
                + "VALUES ('default', 'freee', 'accounting', 99002, '別の事業所', 'CONNECTED')");

        reconciliationService.apply(deposit.getId(), invoice.getId());

        assertOuterTransactionNotRollbackOnly();
        BankDeposit applied = reload(deposit);
        assertThat(applied.getStatus()).isEqualTo("消込済");
        assertThat(applied.getMatchedPaymentId()).isNotNull();
        assertThat(invoiceService.listPayments(invoice.getId())).hasSize(1);
        assertThat(reconcileJobs(deposit)).isEmpty();
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_audit_log WHERE application_code = 'FREEE_RECONCILE_NOT_QUEUED' AND uri = ?",
                Integer.class, "/api/reconciliation/" + deposit.getId() + "/apply");
        assertThat(audits).isEqualTo(1);
    }

    @Test
    @DisplayName("F1: 同じ入金行・取引のジョブが別の内容で登録済みでも例外にせず登録しない。同じ内容なら既存ジョブへ統合する")
    void 同じキーで別内容の反映ジョブがあっても例外にせず二重に登録しない() {
        markInvoiceSentToFreee("668303");
        BankDeposit deposit = insertDeposit();
        reconciliationService.apply(deposit.getId(), invoice.getId());
        IntegrationJob job = reconcileJobs(deposit).get(0);

        // 送信成功時の再登録（F3）と同じ経路: 同じ内容なら既存ジョブを数えるだけで増やさない
        assertThat(salesIntegrationService.enqueueDepositReconcilesForInvoice(invoice.getId())).isEqualTo(1);
        assertThat(reconcileJobs(deposit)).hasSize(1);

        jobService.update(new UpdateWrapper<IntegrationJob>().eq("id", job.getId()).set("payload_hash", "tampered-hash"));
        assertThat(salesIntegrationService.enqueueDepositReconcilesForInvoice(invoice.getId())).isZero();
        assertOuterTransactionNotRollbackOnly();
        assertThat(reconcileJobs(deposit)).hasSize(1);
    }

    @Test
    @DisplayName("F2: 消込を取り消すと未完了の反映ジョブは取消され、Workerはfreeeを呼ばない")
    void 消込を取り消すと未完了の反映ジョブは取消されWorkerはfreeeを呼ばない() {
        markInvoiceSentToFreee("668304");
        BankDeposit deposit = insertDeposit();
        reconciliationService.apply(deposit.getId(), invoice.getId());
        IntegrationJob job = reconcileJobs(deposit).get(0);
        assertThat(job.getStatus()).isEqualTo("PENDING");

        InvoicePaymentDeleteResult deleted = invoiceService.deletePayment(invoice.getId(), reload(deposit).getMatchedPaymentId());

        assertThat(deleted.getWarnings()).isEmpty();
        IntegrationJob cancelled = jobService.getById(job.getId());
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getErrorMessageSafe()).isEqualTo("error.accounting.depositReconcileStale");
        // freeeへのリクエストは期待していない（送られれば MockRestServiceServer が AssertionError を投げる）
        worker.dispatchJob(jobService.getById(job.getId()));
        mockServer.verify();
        assertThat(jobService.getById(job.getId()).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("F2: 権限不足（403）で失敗した反映ジョブも消込の取消で取消され、権限を直して再試行してもfreeeへ送られない")
    void 権限不足で失敗した反映ジョブも消込の取消で取消され再試行できない() {
        markInvoiceSentToFreee("668305");
        BankDeposit deposit = insertDeposit();
        reconciliationService.apply(deposit.getId(), invoice.getId());
        IntegrationJob job = reconcileJobs(deposit).get(0);
        jobService.claimJob(job.getId());
        jobService.markFailed(job.getId(), "PLAN_LIMITATION", "error.accounting.freeeWalletTxnWriteForbidden");

        InvoicePaymentDeleteResult deleted = invoiceService.deletePayment(invoice.getId(), reload(deposit).getMatchedPaymentId());

        // freeeが確定的に拒否した試行だけなので、freee側の対応は要らない
        assertThat(deleted.getWarnings()).isEmpty();
        assertThat(jobService.getById(job.getId()).getStatus()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> jobService.resetForManualRetry(job.getId())).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("F2: freeeへ反映済みの消込を取り消すと、取消は成功させたうえで警告を返し、要対応の記録と監査を残す")
    void freeeへ反映済みの消込を取り消すと警告と要対応の記録と監査を残す() {
        markInvoiceSentToFreee("668306");
        BankDeposit deposit = insertDeposit();
        reconciliationService.apply(deposit.getId(), invoice.getId());
        IntegrationJob job = reconcileJobs(deposit).get(0);
        mockServer.expect(requestTo(reconcileUrl(deposit)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.deals[0].deal_id").value(668306))
                .andRespond(withSuccess("{\"wallet_txn_id\": " + deposit.getFreeeDepositId()
                        + ", \"deal_ids\": [668306], \"new_deal_id\": null}", MediaType.APPLICATION_JSON));
        worker.dispatchJob(jobService.getById(job.getId()));
        mockServer.verify();
        assertThat(jobService.getById(job.getId()).getStatus()).isEqualTo("SUCCEEDED");
        Long paymentId = reload(deposit).getMatchedPaymentId();

        InvoicePaymentDeleteResult deleted = invoiceService.deletePayment(invoice.getId(), paymentId);

        // 取消自体は拒否しない（SESの入金は削除され、入金明細は未消込へ戻る）
        assertThat(reload(deposit).getStatus()).isEqualTo("未消込");
        assertThat(invoiceService.listPayments(invoice.getId())).isEmpty();
        assertThat(deleted.getWarnings()).containsExactly("accounting.depositReconcile.releasedAfterSync");
        IntegrationJob flagged = jobService.getById(job.getId());
        assertThat(flagged.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(flagged.getErrorCode()).isEqualTo("DEPOSIT_RECONCILE_ACTION_REQUIRED");
        assertThat(flagged.getErrorMessageSafe()).isEqualTo("accounting.depositReconcile.releasedAfterSync");
        assertThat(jobService.listEvents(job.getId()))
                .extracting(IntegrationJobEvent::getToStatus)
                .contains("ACTION_REQUIRED");
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_audit_log WHERE application_code = 'FREEE_UNRECONCILE_REQUIRED' AND uri = ?",
                Integer.class, "/api/invoices/" + invoice.getId() + "/payments/" + paymentId);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    @DisplayName("F2: Workerは送る直前に、入金明細の消込がジョブの内容（請求書・入金行）と違えばfreeeを呼ばずに取消す")
    void Workerは消込がジョブの内容と違えばfreeeを呼ばずに取消す() {
        markInvoiceSentToFreee("668307");
        BankDeposit deposit = insertDeposit();
        reconciliationService.apply(deposit.getId(), invoice.getId());
        Long paymentId = reload(deposit).getMatchedPaymentId();

        // 入金行が違うジョブ（消込し直す前の古いジョブ等）
        IntegrationJob otherPayment = salesIntegrationService.enqueueDepositReconcile(deposit.getId(),
                deposit.getFreeeDepositId(), invoice.getId(), paymentId + 1000, INVOICE_TOTAL);
        worker.dispatchJob(jobService.getById(otherPayment.getId()));
        mockServer.verify();
        IntegrationJob staleByPayment = jobService.getById(otherPayment.getId());
        assertThat(staleByPayment.getStatus()).isEqualTo("CANCELLED");
        assertThat(staleByPayment.getErrorCode()).isEqualTo("DEPOSIT_RECONCILE_STALE");

        // 入金明細の消込だけが解除された（後始末が漏れた）場合も、送る直前に気付いて取消す
        IntegrationJob job = reconcileJobs(deposit).stream()
                .filter(j -> "PENDING".equals(j.getStatus()))
                .findFirst().orElseThrow();
        bankDepositMapper.update(null, new UpdateWrapper<BankDeposit>()
                .eq("id", deposit.getId())
                .set("status", "未消込")
                .set("matched_invoice_id", null)
                .set("matched_payment_id", null));
        worker.dispatchJob(jobService.getById(job.getId()));
        mockServer.verify();
        assertThat(jobService.getById(job.getId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(jobService.getById(job.getId()).getErrorCode()).isEqualTo("DEPOSIT_RECONCILE_STALE");
    }

    @Test
    @DisplayName("F3: 請求書の送信より先に消込した入金は、送信（取引作成）が成功した時点で反映ジョブを登録する")
    void 送信より先に消込した入金は送信成功時に反映ジョブを登録する() {
        BankDeposit deposit = insertDeposit();
        reconciliationService.apply(deposit.getId(), invoice.getId());
        // 消込の時点では反映先の取引が無いため登録されない
        assertThat(reconcileJobs(deposit)).isEmpty();

        IntegrationJob sync = salesIntegrationService.triggerSalesSync(invoice.getId(), 1L);
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"deal\": {\"id\": 668308, \"company_id\": 99001, \"amount\": 1100000, \"status\": \"unsettled\"}}",
                        MediaType.APPLICATION_JSON));
        salesIntegrationService.processSalesInvoiceJob(sync.getId());
        mockServer.verify();
        assertThat(jobService.getById(sync.getId()).getStatus()).isEqualTo("SUCCEEDED");

        List<IntegrationJob> jobs = reconcileJobs(deposit);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(jobs.get(0).getIdempotencyKey())
                .isEqualTo("DEPOSIT_RECONCILE:" + deposit.getId() + ":" + reload(deposit).getMatchedPaymentId() + ":668308");
        assertThat(jobs.get(0).getPayloadSnapshot()).contains("\"externalDealId\":\"668308\"");
    }
}
