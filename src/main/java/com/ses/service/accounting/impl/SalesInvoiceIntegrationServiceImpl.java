package com.ses.service.accounting.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.AccountingSendPreviewDto;
import com.ses.dto.accounting.canonical.CanonicalDealResult;
import com.ses.dto.accounting.canonical.CanonicalSalesInvoice;
import com.ses.entity.Customer;
import com.ses.entity.ExternalMapping;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.entity.Invoice;
import com.ses.service.CustomerService;
import com.ses.service.InvoiceService;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingJobLegalEntityResolver;
import com.ses.service.accounting.AccountingOrganizationResolver;
import com.ses.service.accounting.AccountingProvider;
import com.ses.service.accounting.AccountingProviderFactory;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.accounting.SalesInvoiceIntegrationService;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.ExternalMappingService.MappingCheck;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 売上請求書・取引連携サービス実装 (B1 / design §4, §6.1, §6.3)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesInvoiceIntegrationServiceImpl implements SalesInvoiceIntegrationService {

    private final InvoiceService invoiceService;
    private final CustomerService customerService;
    private final MonthlyClosingService monthlyClosingService;
    private final IntegrationConnectionService connectionService;
    private final ExternalMappingService mappingService;
    private final IntegrationJobService jobService;
    private final AccountingProviderFactory providerFactory;
    private final AccountingOrganizationResolver organizationResolver;
    private final AccountingTimezoneResolver timezoneResolver;
    private final ObjectMapper objectMapper;
    private final AccountingJobLegalEntityResolver legalEntityResolver;
    private final com.ses.service.accounting.AccountingSendKeyResolver sendKeyResolver;
    private final com.ses.mapper.InvoiceItemMapper invoiceItemMapper;

    /** freee取引の管理番号（ref_number）の上限文字数。 */
    private static final int FREEE_REF_NUMBER_MAX = 20;

    /** 会計連携できる請求書ステータス (P1-07)。 */
    private static final Set<String> SENDABLE_STATUSES = Set.of("送付済", "入金済", "一部入金");

    /** 売上送信に必要なマッピング（種別, 内部キー）。送信ガードとプレビューで同じ定義を使う。 */
    private static List<String[]> requiredMappings(Invoice invoice) {
        return List.of(
                new String[]{"CUSTOMER_PARTNER", customerCode(invoice)},
                new String[]{"ACCOUNT_SALES", "SALES_DEFAULT"},
                taxMapping(invoice));
    }

    /**
     * 請求書の適用税率に対応する税区分マッピング（種別, 内部キー）。税率は保存値（0.10形式）を正とし、
     * 未保存の旧請求書は従来どおり10%として扱う。対応していない税率は送らずに拒否する
     * （10%の税区分で送ると消費税額が請求書と食い違うため）。
     */
    static String[] taxMapping(Invoice invoice) {
        java.math.BigDecimal rate = invoice.getTaxRate() == null ? new java.math.BigDecimal("0.10") : invoice.getTaxRate();
        java.math.BigDecimal percent = rate.compareTo(java.math.BigDecimal.ONE) < 0
                ? rate.multiply(new java.math.BigDecimal("100")) : rate;
        percent = percent.stripTrailingZeros();
        if (percent.compareTo(new java.math.BigDecimal("10")) == 0) {
            return new String[]{"TAX_SALES_10", "TAX_10"};
        }
        if (percent.compareTo(new java.math.BigDecimal("8")) == 0) {
            return new String[]{"TAX_SALES_8", "TAX_8"};
        }
        if (percent.signum() == 0) {
            return new String[]{"TAX_SALES_0", "TAX_0"};
        }
        throw BusinessException.of(400, "error.accounting.taxRateUnsupported");
    }

    /** freeeの取引明細の備考の上限文字数。 */
    static final int FREEE_DETAIL_DESCRIPTION_MAX = 250;

    /**
     * 請求明細ごとのfreee取引明細。請求書の消費税額を明細の税抜金額で按分し（端数は金額の大きい明細へ寄せる）、
     * 明細の税込金額・消費税額の合計が請求書の合計・消費税額と一致するようにする。明細が1件以下、
     * 明細合計が小計と一致しない、0円以下・円未満を含む等で按分できない場合は、従来どおり請求書合計の1明細にする。
     */
    static List<CanonicalSalesInvoice.Detail> buildDetails(Invoice invoice, List<com.ses.entity.InvoiceItem> items,
                                                           String accountItemCode, String taxCode) {
        String fallback = invoice.getRemarks() != null ? invoice.getRemarks() : "SES請求: " + invoice.getInvoiceNo();
        List<CanonicalSalesInvoice.Detail> single = List.of(CanonicalSalesInvoice.Detail.builder()
                .description(truncate(fallback))
                .amount(invoice.getTotal())
                .accountItemCode(accountItemCode)
                .taxCode(taxCode)
                .build());
        java.math.BigDecimal subtotal = invoice.getSubtotal();
        java.math.BigDecimal tax = invoice.getTax();
        java.math.BigDecimal total = invoice.getTotal();
        if (items == null || items.size() < 2 || subtotal == null || tax == null || total == null
                || subtotal.signum() <= 0 || tax.signum() < 0 || !isWholeYen(tax)
                || subtotal.add(tax).compareTo(total) != 0) {
            return single;
        }
        java.math.BigDecimal itemSum = java.math.BigDecimal.ZERO;
        for (com.ses.entity.InvoiceItem item : items) {
            if (item.getAmount() == null || item.getAmount().signum() <= 0 || !isWholeYen(item.getAmount())) {
                return single;
            }
            itemSum = itemSum.add(item.getAmount());
        }
        if (itemSum.compareTo(subtotal) != 0) {
            return single;
        }
        long taxYen = tax.longValueExact();
        long subtotalYen = subtotal.longValueExact();
        long[] vat = new long[items.size()];
        long allocated = 0;
        int largest = 0;
        for (int i = 0; i < items.size(); i++) {
            long amount = items.get(i).getAmount().longValueExact();
            vat[i] = java.math.BigInteger.valueOf(amount).multiply(java.math.BigInteger.valueOf(taxYen))
                    .divide(java.math.BigInteger.valueOf(subtotalYen)).longValueExact();
            allocated += vat[i];
            if (amount > items.get(largest).getAmount().longValueExact()) {
                largest = i;
            }
        }
        vat[largest] += taxYen - allocated;
        List<CanonicalSalesInvoice.Detail> details = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            com.ses.entity.InvoiceItem item = items.get(i);
            String description = item.getDescription() == null || item.getDescription().isBlank()
                    ? fallback : item.getDescription();
            details.add(CanonicalSalesInvoice.Detail.builder()
                    .description(truncate(description))
                    .amount(item.getAmount().add(java.math.BigDecimal.valueOf(vat[i])))
                    .vat(java.math.BigDecimal.valueOf(vat[i]))
                    .accountItemCode(accountItemCode)
                    .taxCode(taxCode)
                    .build());
        }
        return details;
    }

    private static boolean isWholeYen(java.math.BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static String truncate(String text) {
        return text.length() <= FREEE_DETAIL_DESCRIPTION_MAX ? text : text.substring(0, FREEE_DETAIL_DESCRIPTION_MAX);
    }

    private static String customerCode(Invoice invoice) {
        return "CUST-" + invoice.getCustomerId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationJob triggerSalesSync(Long invoiceId, Long triggeredByUserId) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            throw BusinessException.of(404, "error.invoice.notFound");
        }

        // ステータス適格性ガード (送付済 / 入金済 / 一部入金 のみ許可) (P1-07)
        if (!SENDABLE_STATUSES.contains(invoice.getStatus())) {
            throw BusinessException.of(400, "error.accounting.invoiceNotSendable");
        }

        // freeeの管理番号（ref_number）は20文字以内。請求書番号をそのまま使うため送信前に拒否する
        if (invoice.getInvoiceNo() != null && invoice.getInvoiceNo().length() > FREEE_REF_NUMBER_MAX) {
            throw BusinessException.of(400, "error.accounting.refNumberTooLong");
        }

        // 月次締めチェック (締め済み月への更新拒否)
        if (invoice.getBillingMonth() != null) {
            monthlyClosingService.assertOpenForUpdate(invoice.getBillingMonth());
        }

        IntegrationConnection conn = resolveConnection(AccountingTenantContextHolder.requireTenantContext(), null, "freee", "accounting");

        // マッピング検証ガード (未登録・未検証は送信不可)
        for (String[] required : requiredMappings(invoice)) {
            mappingService.assertMappingVerified(conn.getId(), required[0], required[1]);
        }

        // NF09: ジョブの法人は請求書の法人。未設定・接続の法人と不一致ならジョブを作らない（Workerで無言失敗させない）
        Long legalEntityId = legalEntityResolver.require(conn, legalEntityResolver.ofInvoice(invoice));

        CanonicalSalesInvoice canonical = buildSyncCanonical(invoice, conn.getId());
        // 前の世代が外部へ送信した後に取消された場合は、作成前に同じ管理番号の取引がfreeeに無いことを確認させる
        // （今回の取引先・発生日に加えて、取消された世代の取引先・発生日も走査する）
        java.util.List<com.ses.dto.accounting.canonical.PriorDealScope> priorScopes =
                sendKeyResolver.priorDealScopes("INVOICE", invoice.getId(), "SALES_INVOICE_SYNC");
        if (!priorScopes.isEmpty()) {
            canonical.setVerifyNoExistingDeal(Boolean.TRUE);
            canonical.setPriorDealScopes(priorScopes);
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            throw new RuntimeException("Canonical JSON serialization failed", e);
        }

        String payloadHash = calculateSha256(payloadJson);
        // 取消後の再送信を可能にするため、キーは世代付き（世代0は従来形式）
        String idempotencyKey = sendKeyResolver.resolve("SALES_INVOICE:" + invoice.getId() + ":" + invoice.getInvoiceNo(),
                "INVOICE", invoice.getId(), "SALES_INVOICE_SYNC", "SALES_INVOICE_CANCEL", payloadHash);
        Long orgId = organizationResolver.resolveInvoiceOrganizationId(invoice);

        log.info("Enqueueing sales sync job for invoiceId={}, idempotencyKey={}, orgId={}", invoiceId, idempotencyKey, orgId);
        return jobService.createJob(
                conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", invoice.getId(), idempotencyKey, payloadHash,
                payloadJson, conn.getTenantId(), legalEntityId, orgId);
    }

    @Override
    public AccountingSendPreviewDto<CanonicalSalesInvoice> previewSalesSync(Long invoiceId) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            throw BusinessException.of(404, "error.invoice.notFound");
        }
        // プレビューは参照のみ: 接続は作成しない（未作成ならマッピングはすべて未登録扱い）
        IntegrationConnection conn = connectionService.getConnection(AccountingTenantContextHolder.requireTenantContext(), null, "freee", "accounting");
        Long connectionId = conn != null ? conn.getId() : null;
        List<MappingCheck> checks = requiredMappings(invoice).stream()
                .map(required -> mappingService.inspect(connectionId, required[0], required[1]))
                .toList();
        return AccountingSendPreviewDto.of("SALES", buildSyncCanonical(invoice, connectionId), checks,
                conn != null && "CONNECTED".equals(conn.getStatus()),
                legalEntityResolver.check(conn, legalEntityResolver.ofInvoice(invoice)));
    }

    /** 送信ジョブへ保存する標準DTO。照合済みマッピングの外部ID（取引先・勘定科目・税区分）を反映する。 */
    private CanonicalSalesInvoice buildSyncCanonical(Invoice invoice, Long connectionId) {
        Customer customer = customerService.getById(invoice.getCustomerId());
        String customerCode = customerCode(invoice);
        String customerName = customer != null ? customer.getCompanyName() : "顧客ID:" + invoice.getCustomerId();

        ExternalMapping partnerMapping = mappingService.getMapping(connectionId, "CUSTOMER_PARTNER", customerCode);
        ExternalMapping salesAccount = mappingService.getMapping(connectionId, "ACCOUNT_SALES", "SALES_DEFAULT");
        String[] taxKey = taxMapping(invoice);
        ExternalMapping taxMapping = mappingService.getMapping(connectionId, taxKey[0], taxKey[1]);
        List<com.ses.entity.InvoiceItem> items = invoiceItemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.InvoiceItem>()
                        .eq(com.ses.entity.InvoiceItem::getInvoiceId, invoice.getId())
                        .orderByAsc(com.ses.entity.InvoiceItem::getId));

        return CanonicalSalesInvoice.builder()
                .invoiceId(invoice.getId())
                .invoiceNo(invoice.getInvoiceNo())
                .customerId(invoice.getCustomerId())
                .customerCode(customerCode)
                .partnerExternalId(partnerMapping != null ? partnerMapping.getExternalId() : null)
                .customerName(customerName)
                .issueDate(invoice.getIssuedDate())
                .dueDate(invoice.getDueDate())
                .subtotal(invoice.getSubtotal())
                .tax(invoice.getTax())
                .total(invoice.getTotal())
                .taxRate(invoice.getTaxRate())
                .remarks(invoice.getRemarks())
                .details(buildDetails(invoice, items,
                        salesAccount != null ? salesAccount.getExternalId() : null,
                        taxMapping != null ? taxMapping.getExternalId() : null))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationJob triggerSalesCancel(Long invoiceId, String cancelReason, Long triggeredByUserId) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            throw BusinessException.of(404, "error.invoice.notFound");
        }

        if (invoice.getBillingMonth() != null) {
            monthlyClosingService.assertOpenForUpdate(invoice.getBillingMonth());
        }

        IntegrationConnection conn = resolveConnection(AccountingTenantContextHolder.requireTenantContext(), null, "freee", "accounting");
        IntegrationJob latestSync = jobService.getLatestJob("INVOICE", invoiceId, "SALES_INVOICE_SYNC");
        if (latestSync == null || !"SUCCEEDED".equals(latestSync.getStatus()) || latestSync.getExternalId() == null) {
            throw BusinessException.of(400, "error.accounting.syncedDealMissing");
        }

        // NF09: 取消は取引を作成したジョブと同じ法人で実行する（旧ジョブに法人が無ければ請求書の法人）
        Long legalEntityId = latestSync.getLegalEntityId() != null
                ? latestSync.getLegalEntityId()
                : legalEntityResolver.require(conn, legalEntityResolver.ofInvoice(invoice));

        // P1-11: 取消理由は機械可読コード (design §8.3) を保存・送信する。未知の入力は REASON_OTHER へ正規化
        String reason = normalizeCancelReason(cancelReason);
        String idempotencyKey = "SALES_CANCEL:" + invoice.getId() + ":" + latestSync.getExternalId();
        String payload = "{\"invoiceId\":" + invoiceId + ",\"externalDealId\":\"" + latestSync.getExternalId() + "\",\"reason\":\"" + reason + "\"}";
        String payloadHash = calculateSha256(payload);
        // 取消済み・取消処理中の取引は理由付きで返す（既存ジョブを黙って返すと「登録しました」と誤表示になる）
        IntegrationJob existingCancel = jobService.getByIdempotencyKey(idempotencyKey);
        if (existingCancel != null) {
            if ("SUCCEEDED".equals(existingCancel.getStatus())) {
                throw BusinessException.of(409, "error.accounting.alreadyCancelled");
            }
            if ("FAILED".equals(existingCancel.getStatus())) {
                throw BusinessException.of(409, "error.accounting.previousJobFailed");
            }
            if (!payloadHash.equals(existingCancel.getPayloadHash())) {
                throw BusinessException.of(409, "error.accounting.jobInProgress");
            }
        }
        Long orgId = organizationResolver.resolveInvoiceOrganizationId(invoice);

        log.info("Enqueueing sales cancel job for invoiceId={}, externalDealId={}, orgId={}", invoiceId, latestSync.getExternalId(), orgId);
        return jobService.createJob(
                conn.getId(), "SALES_INVOICE_CANCEL", "INVOICE", invoice.getId(), idempotencyKey, payloadHash,
                payload, conn.getTenantId(), legalEntityId, orgId);
    }

    private final SalesInvoiceTransactionCoordinator transactionCoordinator;

    @Override
    public void processSalesInvoiceJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        if (conn == null) {
            jobService.markFailed(jobId, "CONNECTION_NOT_FOUND", "外部連携接続情報が見つかりません");
            return;
        }

        // P1-07: Snapshot 必須・SHA-256 ハッシュ検証・業務テーブル再読込全廃
        if (job.getPayloadSnapshot() == null || job.getPayloadSnapshot().isBlank()) {
            jobService.markFailed(jobId, "LEGACY_SNAPSHOT_MISSING", "Snapshotが存在しないジョブは実行できません");
            return;
        }

        String calculatedHash = calculateSha256(job.getPayloadSnapshot());
        if (job.getPayloadHash() != null && !job.getPayloadHash().equals(calculatedHash)) {
            jobService.markFailed(jobId, "PAYLOAD_HASH_MISMATCH", "SnapshotのSHA-256ハッシュが一致しません");
            return;
        }

        AccountingTenantContextHolder.runWithTenant(job.getTenantId(), timezoneResolver.resolve(job.getTenantId()), () -> {
            try {
                CanonicalSalesInvoice canonical = objectMapper.readValue(job.getPayloadSnapshot(), CanonicalSalesInvoice.class);

                AccountingProvider provider = providerFactory.getProvider(conn);
                // 再試行（試行2回目以降）は前回の試行で作成済みの取引を確認してからPOSTする（再POSTで二重に作らない）
                CanonicalDealResult result = provider.upsertSalesInvoice(conn, canonical,
                        AccountingProvider.DealCreateGuard.of(canonical.getVerifyNoExistingDeal(), job.getAttemptCount()));

                // P1-02: トランザクションコーディネーター (Spring プロキシ経由) で原子反映
                transactionCoordinator.handleSalesInvoiceResult(jobId, job, conn, result);

            } catch (com.ses.common.exception.TokenRefreshInProgressException e) {
                log.warn("Token refresh in progress during sales invoice job {}: rescheduling retry in 5s", jobId);
                jobService.markRetryable(jobId, "TOKEN_REFRESH_IN_PROGRESS", "他ノードでトークン更新中のため再試行待ち", 5);
            } catch (Exception e) {
                log.error("Error executing sales invoice job: error_code=JOB_EXECUTION_EXCEPTION, jobId={}, jobType=SALES_INVOICE_SYNC", jobId);
                jobService.markRetryable(jobId, "JOB_EXECUTION_EXCEPTION", "売上連携処理中にシステムエラーが発生しました", 60);
            }
        });
    }

    @Override
    public void handleSalesInvoiceResult(Long jobId, IntegrationJob job, IntegrationConnection conn, CanonicalDealResult result) {
        transactionCoordinator.handleSalesInvoiceResult(jobId, job, conn, result);
    }

    @Override
    public void processSalesCancelJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        if (conn == null) {
            jobService.markFailed(jobId, "CONNECTION_NOT_FOUND", "外部連携接続情報が見つかりません");
            return;
        }

        // P1-07: 取消 Worker も Snapshot 必須・SHA-256 検証・最新sync job再検索全廃
        if (job.getPayloadSnapshot() == null || job.getPayloadSnapshot().isBlank()) {
            jobService.markFailed(jobId, "LEGACY_SNAPSHOT_MISSING", "Snapshotが存在しないジョブは実行できません");
            return;
        }

        String calculatedHash = calculateSha256(job.getPayloadSnapshot());
        if (job.getPayloadHash() != null && !job.getPayloadHash().equals(calculatedHash)) {
            jobService.markFailed(jobId, "PAYLOAD_HASH_MISMATCH", "SnapshotのSHA-256ハッシュが一致しません");
            return;
        }

        AccountingTenantContextHolder.runWithTenant(job.getTenantId(), timezoneResolver.resolve(job.getTenantId()), () -> {
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(job.getPayloadSnapshot());
                String externalDealId = node.has("externalDealId") && !node.get("externalDealId").isNull() ? node.get("externalDealId").asText() : null;
                String reason = node.has("reason") && !node.get("reason").isNull() ? node.get("reason").asText() : "REASON_CLIENT_CANCEL";

                if (externalDealId == null || externalDealId.isBlank()) {
                    jobService.markFailed(jobId, "DEAL_ID_MISSING_IN_SNAPSHOT", "Snapshotに対象の外部取引IDが含まれていません");
                    return;
                }

                AccountingProvider provider = providerFactory.getProvider(conn);
                CanonicalDealResult result = provider.cancelSalesInvoice(conn, externalDealId, reason);

                if (result.isSuccess()) {
                    jobService.markSucceeded(jobId, result.getExternalId(), result.getProviderRequestId(),
                            "freee取引取消完了: dealId=" + result.getExternalId());
                } else {
                    if (result.isRetryable()) {
                        jobService.markRetryable(jobId, result.getErrorCode(), result.getErrorMessageSafe(),
                                result.getRetryAfterSeconds());
                    } else {
                        jobService.markFailed(jobId, result.getErrorCode(), result.getErrorMessageSafe());
                    }
                }
            } catch (com.ses.common.exception.TokenRefreshInProgressException e) {
                log.warn("Token refresh in progress during sales cancel job {}: rescheduling retry in 5s", jobId);
                jobService.markRetryable(jobId, "TOKEN_REFRESH_IN_PROGRESS", "他ノードでトークン更新中のため再試行待ち", 5);
            } catch (Exception e) {
                log.error("Error executing sales cancel job: error_code=JOB_EXECUTION_EXCEPTION, jobId={}, jobType=SALES_INVOICE_CANCEL", jobId);
                jobService.markRetryable(jobId, "JOB_EXECUTION_EXCEPTION", "売上取引取消処理中にシステムエラーが発生しました", 60);
            }
        });
    }

    /** 入金消込の反映ジョブ種別（SESの入金消込 → freeeの口座明細で取引を消込）。 */
    static final String DEPOSIT_RECONCILE_JOB_TYPE = "DEPOSIT_RECONCILE_SYNC";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationJob enqueueDepositReconcile(Long depositId, String walletTxnId, Long invoiceId, Long paymentId,
                                                  java.math.BigDecimal amount) {
        if (depositId == null || invoiceId == null || walletTxnId == null || !walletTxnId.matches("\\d{1,19}")
                || amount == null || amount.signum() <= 0) {
            return null;
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        // 入金明細はfreeeの会計接続から取り込んだもの。接続が無ければ反映先も無い（ここで接続は作らない）
        IntegrationConnection conn = connectionService.getConnection(tenantId, null, "freee", "accounting");
        if (conn == null) {
            log.info("会計接続が無いため、freeeへの入金消込の反映を行いません: depositId={}", depositId);
            return null;
        }
        // 反映先はこの請求書をfreeeへ送信した取引。未送信・取消済みなら反映しない（月次照合で差異として残る）
        IntegrationJob latestSync = jobService.getLatestJob("INVOICE", invoiceId, "SALES_INVOICE_SYNC");
        if (latestSync == null || !"SUCCEEDED".equals(latestSync.getStatus())
                || latestSync.getExternalId() == null || latestSync.getExternalId().isBlank()) {
            log.info("請求書がfreeeへ未送信のため、freeeへの入金消込の反映を行いません: depositId={} invoiceId={}",
                    depositId, invoiceId);
            return null;
        }
        String dealId = latestSync.getExternalId();
        if (isDealCancelled(invoiceId, dealId)) {
            log.info("freeeの取引が取消済みのため、入金消込の反映を行いません: depositId={} invoiceId={} dealId={}",
                    depositId, invoiceId, dealId);
            return null;
        }
        Long legalEntityId = latestSync.getLegalEntityId() != null
                ? latestSync.getLegalEntityId()
                : legalEntityResolver.require(conn, legalEntityResolver.ofInvoice(invoiceService.getById(invoiceId)));

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("depositId", depositId);
        payload.put("walletTxnId", walletTxnId);
        payload.put("invoiceId", invoiceId);
        payload.put("paymentId", paymentId);
        payload.put("externalDealId", dealId);
        payload.put("amount", amount);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("入金消込の反映内容をシリアライズできません", e);
        }
        String idempotencyKey = "DEPOSIT_RECONCILE:" + depositId + ":" + dealId;
        log.info("freeeへの入金消込の反映ジョブを登録します: depositId={} invoiceId={} dealId={}", depositId, invoiceId, dealId);
        return jobService.createJob(conn.getId(), DEPOSIT_RECONCILE_JOB_TYPE, "BANK_DEPOSIT", depositId, idempotencyKey,
                calculateSha256(payloadJson), payloadJson, conn.getTenantId(), legalEntityId, latestSync.getOrganizationId());
    }

    /** この取引の取消（手動の取消・通信中取消の補償取消）が成功しているか。 */
    private boolean isDealCancelled(Long invoiceId, String dealId) {
        for (String key : List.of("SALES_CANCEL:" + invoiceId + ":" + dealId, "COMPENSATE_CANCEL:" + invoiceId + ":" + dealId)) {
            IntegrationJob cancel = jobService.getByIdempotencyKey(key);
            if (cancel != null && "SUCCEEDED".equals(cancel.getStatus())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void processDepositReconcileJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }
        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        if (conn == null) {
            jobService.markFailed(jobId, "CONNECTION_NOT_FOUND", "外部連携接続情報が見つかりません");
            return;
        }
        if (job.getPayloadSnapshot() == null || job.getPayloadSnapshot().isBlank()) {
            jobService.markFailed(jobId, "LEGACY_SNAPSHOT_MISSING", "Snapshotが存在しないジョブは実行できません");
            return;
        }
        if (job.getPayloadHash() != null && !job.getPayloadHash().equals(calculateSha256(job.getPayloadSnapshot()))) {
            jobService.markFailed(jobId, "PAYLOAD_HASH_MISMATCH", "SnapshotのSHA-256ハッシュが一致しません");
            return;
        }

        AccountingTenantContextHolder.runWithTenant(job.getTenantId(), timezoneResolver.resolve(job.getTenantId()), () -> {
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(job.getPayloadSnapshot());
                String walletTxnId = node.path("walletTxnId").asText(null);
                String dealId = node.path("externalDealId").asText(null);
                java.math.BigDecimal amount = node.hasNonNull("amount") ? node.get("amount").decimalValue() : null;
                if (walletTxnId == null || dealId == null || amount == null) {
                    jobService.markFailed(jobId, "INVALID_SNAPSHOT", "Snapshotに口座明細ID・取引ID・金額が含まれていません");
                    return;
                }
                AccountingProvider provider = providerFactory.getProvider(conn);
                CanonicalDealResult result = provider.reconcileDeposit(conn, walletTxnId, dealId, amount);
                if (result.isSuccess()) {
                    jobService.markSucceeded(jobId, dealId, result.getProviderRequestId(),
                            "freeeの口座明細で取引を消込: dealId=" + dealId);
                } else if (result.isRetryable()) {
                    jobService.markRetryable(jobId, result.getErrorCode(), result.getErrorMessageSafe(),
                            result.getRetryAfterSeconds());
                } else {
                    jobService.markFailed(jobId, result.getErrorCode(), result.getErrorMessageSafe());
                }
            } catch (com.ses.common.exception.TokenRefreshInProgressException e) {
                log.warn("Token refresh in progress during deposit reconcile job {}: rescheduling retry in 5s", jobId);
                jobService.markRetryable(jobId, "TOKEN_REFRESH_IN_PROGRESS", "他ノードでトークン更新中のため再試行待ち", 5);
            } catch (Exception e) {
                log.error("Error executing deposit reconcile job: error_code=JOB_EXECUTION_EXCEPTION, jobId={}, jobType={}",
                        jobId, DEPOSIT_RECONCILE_JOB_TYPE);
                jobService.markRetryable(jobId, "JOB_EXECUTION_EXCEPTION", "入金消込の反映中にシステムエラーが発生しました", 60);
            }
        });
    }

    @Override
    public CanonicalSalesInvoice buildCanonicalInvoice(Long invoiceId) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            throw BusinessException.of(404, "error.invoice.notFound");
        }
        Customer customer = customerService.getById(invoice.getCustomerId());
        String customerCode = "CUST-" + invoice.getCustomerId();
        String customerName = customer != null ? customer.getCompanyName() : "顧客ID:" + invoice.getCustomerId();

        return CanonicalSalesInvoice.builder()
                .invoiceId(invoice.getId())
                .invoiceNo(invoice.getInvoiceNo())
                .customerId(invoice.getCustomerId())
                .customerCode(customerCode)
                .customerName(customerName)
                .issueDate(invoice.getIssuedDate())
                .dueDate(invoice.getDueDate())
                .subtotal(invoice.getSubtotal())
                .tax(invoice.getTax())
                .total(invoice.getTotal())
                .taxRate(invoice.getTaxRate())
                .remarks(invoice.getRemarks())
                .details(List.of(CanonicalSalesInvoice.Detail.builder()
                        .description(invoice.getRemarks() != null ? invoice.getRemarks() : "SES請求: " + invoice.getInvoiceNo())
                        .amount(invoice.getTotal())
                        .build()))
                .build();
    }

    private IntegrationConnection resolveConnection(String tenantId, Long legalEntityId, String provider, String product) {
        return connectionService.getOrCreateConnection(tenantId, legalEntityId, provider, product);
    }

    /** 取消理由の機械可読コードへの正規化 (design §8.3)。5コードの完全 allow-list、それ以外は REASON_OTHER。 */
    static String normalizeCancelReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "REASON_CLIENT_CANCEL";
        }
        String trimmed = reason.trim();
        java.util.Set<String> allowed = java.util.Set.of(
                "REASON_CLIENT_CANCEL", "REASON_AMOUNT_CORRECTION", "REASON_DUPLICATE", "REASON_DISPUTE", "REASON_OTHER");
        return allowed.contains(trimmed) ? trimmed : "REASON_OTHER";
    }

    private String calculateSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }
}
