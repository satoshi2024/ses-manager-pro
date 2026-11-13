package com.ses.service.accounting.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.dto.accounting.canonical.*;
import com.ses.entity.IntegrationConnection;
import com.ses.service.accounting.AccountingProvider;
import com.ses.service.integration.IntegrationConnectionService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * freee 会計 API アダプター (design §2, §6.3, platform-invariants §7)。
 * <p>
 * - Official API Schema DTO を分離し、内部業務モデルへの漏洩を防ぐ。
 * - 200/400/401/403/429/500/Timeout を厳密に分類。
 * - 401 受信時はトークン強制リフレッシュ後に最大1回リプレイ (P1-03)。
 * - タイムアウト時の未知結果照合 (ref_number による重複防止) を実装 (P1-03)。
 * - エラー本文の PII / 秘密情報サニタイズを徹底 (P1-10)。
 * - 外部マスタ (取引先/勘定科目/税区分/部門) の存在照合 (P1-05)。
 * </p>
 */
@Slf4j
@Component("freeeAccountingProvider")
@RequiredArgsConstructor
public class FreeeAccountingProvider implements AccountingProvider {

    private static final Pattern PII_REDACT_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+[a-zA-Z0-9_\\-\\.~]+|[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}|\\b\\d{2,4}-\\d{2,4}-\\d{4}\\b)");

    private final RestTemplate restTemplate;
    private final IntegrationConnectionService connectionService;
    private final ObjectMapper objectMapper;

    @Value("${freee.api.base-url:https://api.freee.co.jp}")
    private String apiBaseUrl;

    /** payroll と同じキー。dummy 既定は禁止（prod は PostConstruct で fail-closed）。 */
    @Value("${freee.client-id:}")
    private String clientId;

    @Value("${freee.client-secret:}")
    private String clientSecret;

    @Value("${freee.oauth-base-url:https://accounts.secure.freee.co.jp/public_api}")
    private String oauthBaseUrl;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @jakarta.annotation.PostConstruct
    void rejectDummyOrMissingCredentialsInProd() {
        boolean prod = activeProfiles != null
                && java.util.Arrays.stream(activeProfiles.split(","))
                .map(String::trim)
                .anyMatch("prod"::equalsIgnoreCase);
        if (!prod) {
            return;
        }
        if (clientId == null || clientId.isBlank() || "dummy-client-id".equals(clientId)
                || clientSecret == null || clientSecret.isBlank() || "dummy-client-secret".equals(clientSecret)) {
            throw new IllegalStateException(
                    "Fail-fast: freee.client-id / freee.client-secret (FREEE_CLIENT_ID/SECRET) must be set in prod; dummy defaults are forbidden");
        }
    }

    private String tokenUrl() {
        String base = oauthBaseUrl == null ? "" : oauthBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/token";
    }

    /**
     * 呼出し側の接続オブジェクトは古いスナップショットになり得るため、常にDBの正本を再読込する。
     * freeeのcompany_idが欠落・不正な接続では、誤法人へのHTTP呼出しを防ぐため直ちに拒否する。
     */
    private IntegrationConnection requireAuthoritativeConnection(IntegrationConnection supplied) {
        if (supplied == null || supplied.getId() == null) {
            throw new IllegalStateException("freee接続情報が存在しません");
        }
        IntegrationConnection authoritative = connectionService.getById(supplied.getId());
        Long companyId = authoritative == null ? null : authoritative.getExternalCompanyId();
        if (authoritative == null || companyId == null || companyId <= 0) {
            throw new IllegalStateException("freee外部事業所IDが未設定または不正です (connectionId="
                    + supplied.getId() + ")");
        }
        return authoritative;
    }

    @Override
    public String providerName() {
        return "freee";
    }

    @Override
    public CanonicalDealResult upsertSalesInvoice(IntegrationConnection connection, CanonicalSalesInvoice invoice) {
        return upsertSalesInvoice(connection, invoice, DealCreateGuard.of(invoice.getVerifyNoExistingDeal(), 1));
    }

    @Override
    public CanonicalDealResult upsertSalesInvoice(IntegrationConnection connection, CanonicalSalesInvoice invoice,
                                                  DealCreateGuard guard) {
        connection = requireAuthoritativeConnection(connection);
        log.info("Upserting sales invoice to freee: invoiceNo={}, customerCode={}, total={}",
                invoice.getInvoiceNo(), invoice.getCustomerCode(), invoice.getTotal());

        FreeeDealCreateRequest request = buildSalesDealRequest(connection, invoice);
        return executeDealCreationWithRecovery(connection, "/api/1/deals", request, invoice.getTotal(), invoice.getInvoiceNo(), guard);
    }

    @Override
    public CanonicalDealResult cancelSalesInvoice(IntegrationConnection connection, String externalDealId, String reason) {
        connection = requireAuthoritativeConnection(connection);
        log.info("Cancelling sales deal in freee: externalDealId={}, reason={}", externalDealId, reason);

        String url = apiBaseUrl + "/api/1/deals/" + externalDealId + "?company_id=" + connection.getExternalCompanyId();

        // 1. まず取引の存在と状態を確認 (404 判定・冪等性保証)
        try {
            executeWith401Recovery(connection, headers -> {
                HttpEntity<?> entity = new HttpEntity<>(headers);
                return restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            });
        } catch (HttpClientErrorException httpEx) {
            if (httpEx.getStatusCode().value() == 404) {
                log.info("Deal externalDealId={} is already absent in freee (404), treating cancel as successful", externalDealId);
                return CanonicalDealResult.builder()
                        .success(true)
                        .externalId(externalDealId)
                        .providerRequestId(extractRequestId(httpEx.getResponseHeaders()))
                        .errorMessageSafe("外部取引は既に削除・取消されています (404 冪等完了)")
                        .build();
            }
        } catch (Exception ignored) {
            // 状態確認エラー時は削除 API 自体へ進む
        }

        // 2. 取引削除 API 実行
        try {
            ResponseEntity<String> response = executeWith401Recovery(connection, headers -> {
                HttpEntity<?> entity = new HttpEntity<>(headers);
                return restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
            });

            String reqId = extractRequestId(response.getHeaders());
            return CanonicalDealResult.builder()
                    .success(true)
                    .externalId(externalDealId)
                    .providerRequestId(reqId)
                    .errorMessageSafe("freee 取引取消完了")
                    .build();
        } catch (HttpClientErrorException httpEx) {
            if (httpEx.getStatusCode().value() == 404) {
                return CanonicalDealResult.builder()
                        .success(true)
                        .externalId(externalDealId)
                        .providerRequestId(extractRequestId(httpEx.getResponseHeaders()))
                        .errorMessageSafe("外部取引は既に削除済です (404)")
                        .build();
            }
            return handleApiException(httpEx, "DELETE /api/1/deals/" + externalDealId);
        } catch (Exception e) {
            return handleApiException(e, "DELETE /api/1/deals/" + externalDealId);
        }
    }

    @Override
    public CanonicalDealResult upsertPurchaseDeal(IntegrationConnection connection, CanonicalPurchaseDeal purchase) {
        return upsertPurchaseDeal(connection, purchase, DealCreateGuard.of(purchase.getVerifyNoExistingDeal(), 1));
    }

    @Override
    public CanonicalDealResult upsertPurchaseDeal(IntegrationConnection connection, CanonicalPurchaseDeal purchase,
                                                  DealCreateGuard guard) {
        connection = requireAuthoritativeConnection(connection);
        log.info("Upserting purchase deal to freee: bpPaymentId={}, bpCompanyCode={}, amount={}",
                purchase.getBpPaymentId(), purchase.getBpCompanyCode(), purchase.getAmount());

        FreeeDealCreateRequest request = buildPurchaseDealRequest(connection, purchase);
        String refNumber = "BP-" + purchase.getBpPaymentId();
        return executeDealCreationWithRecovery(connection, "/api/1/deals", request, purchase.getAmount(), refNumber, guard);
    }

    @Override
    public CanonicalDealResult upsertExpenseDeal(IntegrationConnection connection, CanonicalExpenseDeal expense) {
        return upsertExpenseDeal(connection, expense, DealCreateGuard.of(expense.getVerifyNoExistingDeal(), 1));
    }

    @Override
    public CanonicalDealResult upsertExpenseDeal(IntegrationConnection connection, CanonicalExpenseDeal expense,
                                                 DealCreateGuard guard) {
        connection = requireAuthoritativeConnection(connection);
        log.info("Upserting expense deal to freee: expenseNo={}, engineerCode={}, amount={}",
                expense.getExpenseNo(), expense.getEngineerCode(), expense.getAmount());

        FreeeDealCreateRequest request = buildExpenseDealRequest(connection, expense);
        return executeDealCreationWithRecovery(connection, "/api/1/deals", request, expense.getAmount(), expense.getExpenseNo(), guard);
    }

    @Override
    public com.ses.dto.accounting.PaymentFetchResult fetchPayments(IntegrationConnection connection, LocalDate fromDate, LocalDate toDate) {
        connection = requireAuthoritativeConnection(connection);
        log.info("Fetching payments from freee: companyId={}, fromDate={}, toDate={}",
                connection.getExternalCompanyId(), fromDate, toDate);

        List<CanonicalPaymentSync> deals = new ArrayList<>();
        List<CanonicalPaymentSync> payments = new ArrayList<>();
        Set<String> seenDealIds = new HashSet<>();
        Set<String> seenPaymentKeys = new HashSet<>();
        int limit = 100;
        int maxPages = 50;
        boolean pageCapReached = false;
        boolean duplicateDealId = false;
        boolean duplicatePaymentId = false;
        boolean fetchFailed = false;
        String errorCode = null;

        // 1回目: 決済済み取引（発生日で絞らない）。入金母集団は payments[].date 基準で、発生日の固定期間で
        //   絞ると支払サイトを欠落させる（R4-R4）。deals母集団だけを後段で対象月に絞る。
        // 2回目: 対象月に発生した未決済取引。freeeで直接作られた未入金の取引もEXTERNAL_ONLYとして
        //   検出するため（決済済みだけでは未入金の取引が母集団に入らない）。一部入金があれば入金母集団にも入る。
        String[] passes = fromDate == null || toDate == null
                ? new String[]{"&status=settled"}
                : new String[]{"&status=settled",
                        "&status=unsettled&start_issue_date=" + fromDate + "&end_issue_date=" + toDate};
        passLoop:
        for (String pass : passes) {
        // 重複IDの判定は取得ごと（ページ跨ぎ）に行う。2回の取得の間に決済済みの取引が未決済へ戻ると（入金の削除等）
        // 両方に現れるが、それは外部データの不整合ではないため、先に取得した決済済み側を採用して読み飛ばす。
        // 逆に未決済の取引が2回の取得の間に決済済みになると、どちらにも現れない（freeeの一覧に時点指定が無いため）。
        // SESが送信した取引は一覧に無くても照合側が1件ずつ照会するため欠落しない。freeeで直接作られた取引の
        // 外部のみ検出だけが次回の照合へ持ち越しになる。
        Set<String> seenInPass = new HashSet<>();
        for (int page = 0; page < maxPages; page++) {
            int offset = page * limit;
            String url = apiBaseUrl + "/api/1/deals?company_id=" + connection.getExternalCompanyId()
                    + pass + "&limit=" + limit
                    + "&offset=" + offset;

            try {
                ResponseEntity<String> response = executeWith401Recovery(connection, headers -> {
                    HttpEntity<?> entity = new HttpEntity<>(headers);
                    return restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                });

                if (response.getBody() == null || response.getBody().isBlank()) {
                    break;
                }

                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode dealsNode = root.get("deals");
                if (dealsNode == null || !dealsNode.isArray() || dealsNode.isEmpty()) {
                    break;
                }

                for (JsonNode d : dealsNode) {
                    String dealId = d.has("id") ? String.valueOf(d.get("id").asLong()) : null;
                    if (dealId != null && !seenInPass.add(dealId)) {
                        // R1-P1-09: 同じ取得内のページ跨ぎ重複IDは外部データ不整合として fail-closed
                        duplicateDealId = true;
                        log.warn("Duplicate dealId={} detected while fetching payments (fail-closed)", dealId);
                        continue;
                    }
                    if (dealId != null && !seenDealIds.add(dealId)) {
                        log.info("dealId={} が決済済み・未決済の両方の取得に現れたため（取得の間に未決済へ戻った）、決済済み側を採用します", dealId);
                        continue;
                    }
                    BigDecimal dealAmount = d.has("amount") ? BigDecimal.valueOf(d.get("amount").asLong()) : BigDecimal.ZERO;
                    String refNumber = d.has("ref_number") && !d.get("ref_number").isNull() ? d.get("ref_number").asText() : null;
                    String issueDateStr = d.has("issue_date") && !d.get("issue_date").isNull() ? d.get("issue_date").asText() : null;
                    LocalDate issueDate = issueDateStr != null ? LocalDate.parse(issueDateStr) : null;
                    boolean settled = d.has("status") && "settled".equalsIgnoreCase(d.get("status").asText());
                    // R1-P1-09: deals母集団は発生日が対象月内のもののみ (月跨ぎの前後月発行dealは deals へ含めない)
                    boolean dealInMonth = issueDate == null || (!issueDate.isBefore(fromDate) && !issueDate.isAfter(toDate));

                    // 1. deal単位エントリ (売上/仕入/経費の母集団照合・EXTERNAL_ONLY表示用)
                    if (dealInMonth) {
                        deals.add(CanonicalPaymentSync.builder()
                                .dealId(dealId)
                                .amount(dealAmount)
                                .issueDate(issueDate)
                                .settled(settled)
                                .refNumber(refNumber)
                                .build());
                    }

                    // 2. payment単位エントリ (入金 1:1 消込用): freee payments[] を展開する (R1-P1-09)
                    JsonNode paymentsNode = d.get("payments");
                    if (paymentsNode != null && paymentsNode.isArray()) {
                        for (JsonNode p : paymentsNode) {
                            String paymentId = p.has("id") ? String.valueOf(p.get("id").asLong()) : null;
                            if (paymentId == null) {
                                log.warn("Payment without id under dealId={} (fail-closed for reconciliation)", dealId);
                                continue;
                            }
                            String paymentKey = dealId + ":" + paymentId;
                            if (!seenPaymentKeys.add(paymentKey)) {
                                duplicatePaymentId = true;
                                log.warn("Duplicate payment key={} detected while fetching payments (fail-closed)", paymentKey);
                                continue;
                            }
                            BigDecimal paymentAmount = p.has("amount") && !p.get("amount").isNull()
                                    ? BigDecimal.valueOf(p.get("amount").asLong()) : null;
                            LocalDate paymentDate = p.has("date") && !p.get("date").isNull()
                                    ? LocalDate.parse(p.get("date").asText()) : null;
                            // R1-P1-09: 入金母集団は決済日が対象月内のもののみ (月跨ぎ決済を取得)
                            if (paymentDate == null || paymentDate.isBefore(fromDate) || paymentDate.isAfter(toDate)) {
                                continue;
                            }

                            payments.add(CanonicalPaymentSync.builder()
                                    .dealId(dealId)
                                    .paymentId(paymentId)
                                    .amount(paymentAmount)
                                    .paymentDate(paymentDate)
                                    .issueDate(issueDate)
                                    .settled(settled)
                                    .refNumber(refNumber)
                                    .build());
                        }
                    }
                }

                if (dealsNode.size() < limit) {
                    break;
                }
                if (page == maxPages - 1) {
                    // 最終ページまで全件返却された -> 次のページが存在し得る
                    pageCapReached = true;
                }
            } catch (Exception e) {
                log.error("Failed to fetch payments from freee on page {}: error_code=EXTERNAL_API_ERROR", page);
                fetchFailed = true;
                errorCode = "EXTERNAL_API_ERROR";
                break passLoop;
            }
        }
        }
        return com.ses.dto.accounting.PaymentFetchResult.builder()
                .deals(deals)
                .payments(payments)
                .pageCapReached(pageCapReached)
                .duplicateDealId(duplicateDealId)
                .duplicatePaymentId(duplicatePaymentId)
                .fetchFailed(fetchFailed)
                .errorCode(errorCode)
                .build();
    }

    /** FR-09: 入金明細の取得ページ上限（100件×50ページ）。超過分は次回の期間指定で取得する。 */
    private static final int DEPOSIT_PAGE_LIMIT = 100;
    private static final int DEPOSIT_MAX_PAGES = 50;

    /**
     * FR-09 入金消込: 口座明細（freee会計 wallet_txns）の入金を取得する。
     *
     * <p>以前は売上取引（deals?type=income）を「入金」として取得していたが、それは請求（売掛）であって着金ではなく、
     * 取引に振込名義も無い。SESが送信した売上取引がそのまま「入金」に見えてしまうため、口座の入金明細を使う。
     * 振込名義は明細の摘要（description）。取得失敗は部分結果で消込を進めず例外にする。</p>
     */
    public List<com.ses.dto.reconciliation.BankDepositDto> fetchBankDeposits(IntegrationConnection connection,
                                                                            LocalDate fromDate, LocalDate toDate) {
        connection = requireAuthoritativeConnection(connection);
        Long companyId = connection.getExternalCompanyId();
        List<com.ses.dto.reconciliation.BankDepositDto> deposits = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (int page = 0; page < DEPOSIT_MAX_PAGES; page++) {
            String url = apiBaseUrl + "/api/1/wallet_txns?company_id=" + companyId
                    + "&walletable_type=bank_account&entry_side=income"
                    + "&start_date=" + fromDate + "&end_date=" + toDate
                    + "&limit=" + DEPOSIT_PAGE_LIMIT + "&offset=" + (page * DEPOSIT_PAGE_LIMIT);
            JsonNode txns;
            try {
                ResponseEntity<String> response = executeWith401Recovery(connection, headers -> {
                    HttpEntity<?> entity = new HttpEntity<>(headers);
                    return restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                });
                txns = response.getBody() == null || response.getBody().isBlank()
                        ? null : objectMapper.readTree(response.getBody()).get("wallet_txns");
            } catch (Exception e) {
                log.warn("freee入金明細の取得に失敗しました: page={} error_code=EXTERNAL_API_ERROR exceptionClass={}",
                        page, e.getClass().getSimpleName());
                throw com.ses.common.exception.BusinessException.of(502, "error.reconciliation.fetchFailed");
            }
            if (txns == null || !txns.isArray() || txns.isEmpty()) {
                break;
            }
            for (JsonNode t : txns) {
                if (!t.hasNonNull("id") || !t.hasNonNull("date") || !t.hasNonNull("amount")) {
                    continue; // 識別できない明細は消込対象にしない
                }
                String id = String.valueOf(t.get("id").asLong());
                if (!seenIds.add(id)) {
                    continue; // ページ跨ぎの重複は1件として扱う（t_bank_deposit側もfreee_deposit_idで一意）
                }
                com.ses.dto.reconciliation.BankDepositDto d = new com.ses.dto.reconciliation.BankDepositDto();
                d.setFreeeDepositId(id);
                d.setDepositDate(LocalDate.parse(t.get("date").asText()));
                d.setAmount(BigDecimal.valueOf(t.get("amount").asLong()));
                d.setPayerName(t.path("description").asText(""));
                deposits.add(d);
            }
            if (txns.size() < DEPOSIT_PAGE_LIMIT) {
                break;
            }
        }
        log.info("freee入金明細を取得しました: companyId={} from={} to={} count={}", companyId, fromDate, toDate, deposits.size());
        return deposits;
    }

    @Override
    public DealLookup lookupDeal(IntegrationConnection connection, String externalDealId) {
        if (externalDealId == null || !externalDealId.matches("\\d{1,19}")) {
            return DealLookup.notFound(); // 数値でないID（旧seedの仮IDなど）はfreeeに存在し得ない
        }
        try {
            IntegrationConnection authoritative = requireAuthoritativeConnection(connection);
            String url = apiBaseUrl + "/api/1/deals/" + externalDealId + "?company_id=" + authoritative.getExternalCompanyId();
            ResponseEntity<String> response = executeWith401Recovery(authoritative, headers -> {
                HttpEntity<?> entity = new HttpEntity<>(headers);
                return restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            });
            JsonNode deal = response.getBody() == null ? null : objectMapper.readTree(response.getBody()).get("deal");
            if (deal == null || !deal.hasNonNull("id")) {
                return DealLookup.unknown();
            }
            return DealLookup.found(deal.hasNonNull("amount") ? BigDecimal.valueOf(deal.get("amount").asLong()) : null);
        } catch (HttpClientErrorException.NotFound e) {
            return DealLookup.notFound();
        } catch (Exception e) {
            log.warn("freee取引の照会に失敗しました: error_code=EXTERNAL_API_ERROR dealId={} exceptionClass={}",
                    externalDealId, e.getClass().getSimpleName());
            return DealLookup.unknown();
        }
    }

    @Override
    public CanonicalPaymentSync fetchDealPayment(IntegrationConnection connection, String externalDealId) {
        connection = requireAuthoritativeConnection(connection);
        String url = apiBaseUrl + "/api/1/deals/" + externalDealId + "?company_id=" + connection.getExternalCompanyId();

        try {
            ResponseEntity<String> response = executeWith401Recovery(connection, headers -> {
                HttpEntity<?> entity = new HttpEntity<>(headers);
                return restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            });

            if (response.getBody() == null) return null;
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode deal = root.get("deal");
            if (deal == null) return null;

            String dealId = deal.has("id") ? String.valueOf(deal.get("id").asLong()) : externalDealId;
            BigDecimal amount = deal.has("amount") && !deal.get("amount").isNull() ? BigDecimal.valueOf(deal.get("amount").asLong()) : null;
            boolean settled = deal.has("status") && "settled".equalsIgnoreCase(deal.get("status").asText());
            LocalDate paymentDate = null;

            JsonNode payments = deal.get("payments");
            if (payments != null && payments.isArray() && !payments.isEmpty()) {
                settled = true;
                JsonNode firstP = payments.get(0);
                if (firstP.has("amount") && !firstP.get("amount").isNull()) {
                    amount = BigDecimal.valueOf(firstP.get("amount").asLong());
                }
                // R1-P1-08: 決済日は payments[].date のみから取得する。
                // 欠落時は issue_date / 現在日付へ代用せず NULL のまま返し、Worker の PAYMENT_DATE_MISSING で拒否させる。
                if (firstP.has("date") && !firstP.get("date").isNull()) {
                    paymentDate = LocalDate.parse(firstP.get("date").asText());
                }
            }

            return CanonicalPaymentSync.builder()
                    .dealId(dealId)
                    .amount(amount)
                    .paymentDate(paymentDate)
                    .settled(settled)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to fetch deal payment: error_code=EXTERNAL_API_ERROR (dealId={})", externalDealId);
            return null;
        }
    }
    @Override
    public boolean validateConnection(IntegrationConnection connection) {
        try {
            connection = requireAuthoritativeConnection(connection);
            String url = apiBaseUrl + "/api/1/users/me";
            ResponseEntity<String> response = executeWith401Recovery(connection, headers -> {
                HttpEntity<?> entity = new HttpEntity<>(headers);
                return restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            });
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("freee connection validation failed: error_code=EXTERNAL_API_ERROR");
            return false;
        }
    }

    @Override
    public boolean verifyMaster(IntegrationConnection connection, String objectType, String externalId, String externalCode) {
        if (externalId == null || externalId.isBlank() || objectType == null) return false;
        try {
            connection = requireAuthoritativeConnection(connection);
        } catch (IllegalStateException e) {
            log.warn("freee master verification skipped: error_code=CONNECTION_COMPANY_ID_INVALID");
            return false;
        }
        Long companyId = connection.getExternalCompanyId();

        String path;
        boolean isListSearch = false;
        switch (objectType) {
            case "CUSTOMER_PARTNER", "BP_PARTNER" -> {
                path = "/api/1/partners/" + externalId + "?company_id=" + companyId;
            }
            case "ACCOUNT_SALES", "ACCOUNT_PURCHASE", "ACCOUNT_EXPENSE", "ACCOUNT_OUTSOURCING" -> {
                path = "/api/1/account_items?company_id=" + companyId;
                isListSearch = true;
            }
            case "TAX_SALES_10", "TAX_SALES_8", "TAX_SALES_0", "TAX_PURCHASE_10", "TAX_EXPENSE_10" -> {
                path = "/api/1/taxes/companies/" + companyId;
                isListSearch = true;
            }
            case "SECTION", "COST_CENTER" -> {
                path = "/api/1/sections?company_id=" + companyId;
                isListSearch = true;
            }
            default -> {
                log.warn("Unknown mapping objectType={}, fail-closed", objectType);
                return false; // fail-closed
            }
        }

        try {
            String url = apiBaseUrl + path;
            ResponseEntity<String> res = executeWith401Recovery(connection, headers -> {
                HttpEntity<?> entity = new HttpEntity<>(headers);
                return restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            });
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                return false;
            }

            if (!isListSearch) {
                // Partner single object response
                JsonNode root = objectMapper.readTree(res.getBody());
                JsonNode partner = root.get("partner");
                if (partner != null && partner.has("id")) {
                    return String.valueOf(partner.get("id").asLong()).equals(externalId);
                }
                return false;
            }

            JsonNode root = objectMapper.readTree(res.getBody());
            switch (objectType) {
                case "ACCOUNT_SALES", "ACCOUNT_PURCHASE", "ACCOUNT_EXPENSE", "ACCOUNT_OUTSOURCING" -> {
                    JsonNode items = root.get("account_items");
                    if (items != null && items.isArray()) {
                        for (JsonNode it : items) {
                            if (it.has("id") && String.valueOf(it.get("id").asLong()).equals(externalId)) {
                                return true;
                            }
                        }
                    }
                    return false;
                }
                case "TAX_SALES_10", "TAX_SALES_8", "TAX_SALES_0", "TAX_PURCHASE_10", "TAX_EXPENSE_10" -> {
                    JsonNode taxes = root.get("taxes");
                    if (taxes != null && taxes.isArray()) {
                        for (JsonNode t : taxes) {
                            if (t.has("code") && String.valueOf(t.get("code").asInt()).equals(externalId)) {
                                return true;
                            }
                        }
                    }
                    return false;
                }
                case "SECTION", "COST_CENTER" -> {
                    JsonNode sections = root.get("sections");
                    if (sections != null && sections.isArray()) {
                        for (JsonNode s : sections) {
                            if (s.has("id") && String.valueOf(s.get("id").asLong()).equals(externalId)) {
                                return true;
                            }
                        }
                    }
                    return false;
                }
                default -> {
                    return false;
                }
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("freee master not found: objectType={}, externalId={}", objectType, externalId);
            return false;
        } catch (Exception e) {
            log.warn("freee master verification error: error_code=EXTERNAL_API_ERROR (objectType={}, externalId={})", objectType, externalId);
            return false;
        }
    }

    // === 401 Recovery & Unknown-Outcome Pipeline ===

    private CanonicalDealResult executeDealCreationWithRecovery(IntegrationConnection connection,
                                                                 String path,
                                                                 FreeeDealCreateRequest request,
                                                                 BigDecimal expectedTotal,
                                                                 String refNumber,
                                                                 DealCreateGuard guard) {
        String url = apiBaseUrl + path;
        if (guard != null && guard != DealCreateGuard.NONE) {
            // 送信するのと同じ管理番号で確認する（経費番号が無い場合などは request 側の値）
            String scanRef = request != null && request.getRefNumber() != null ? request.getRefNumber() : refNumber;
            CanonicalDealResult guarded = guardDealCreation(connection, request, scanRef, expectedTotal, guard);
            if (guarded != null) {
                return guarded;
            }
        }
        try {
            ResponseEntity<JsonNode> response = executeWith401Recovery(connection, headers -> {
                HttpEntity<FreeeDealCreateRequest> entity = new HttpEntity<>(request, headers);
                return restTemplate.exchange(url, HttpMethod.POST, entity, JsonNode.class);
            });

            String reqId = extractRequestId(response.getHeaders());
            JsonNode root = response.getBody();
            JsonNode deal = root == null ? null : root.get("deal");
            Long dealId = deal == null || !deal.isObject() ? null : parsePositiveLong(deal.get("id"));
            if (dealId == null) {
                return CanonicalDealResult.builder()
                        .success(false)
                        .externalId(null)
                        .providerRequestId(reqId)
                        .errorCode("INVALID_DEAL_ID")
                        .errorMessageSafe("error.accounting.freeeInvalidResponse")
                        .retryable(false)
                        .build();
            }

            FreeeDealDto parsedDeal = objectMapper.treeToValue(deal, FreeeDealDto.class);
            Long actualAmount = parsedDeal.getAmount();

            // 金額整合性チェック
            if (expectedTotal != null && actualAmount != null
                    && expectedTotal.compareTo(BigDecimal.valueOf(actualAmount)) != 0) {
                log.error("freee response amount mismatch: expected={}, actual={}", expectedTotal, actualAmount);
                return CanonicalDealResult.builder()
                        .success(false)
                        .externalId(String.valueOf(dealId))
                        .providerRequestId(reqId)
                        .errorCode("AMOUNT_MISMATCH")
                        .errorMessageSafe("error.accounting.freeeAmountMismatch")
                        .retryable(false)
                        .build();
            }

            return CanonicalDealResult.builder()
                    .success(true)
                    .externalId(String.valueOf(dealId))
                    .providerRequestId(reqId)
                    .responseTotal(actualAmount != null ? BigDecimal.valueOf(actualAmount) : expectedTotal)
                    .build();

        } catch (Exception e) {
            // タイムアウトまたはネットワーク切断時の未知結果照合 (P1-03)
            if ((e instanceof ResourceAccessException || e.getCause() instanceof java.net.SocketTimeoutException)
                    && refNumber != null && !refNumber.isBlank()) {
                log.warn("Timeout on deal creation for refNumber={}, querying freee to check if deal was created", refNumber);
                CanonicalDealResult verified = verifyDealCreatedByRefNumber(connection, refNumber, expectedTotal, request != null ? request.getCompanyId() : null);
                if (verified != null) {
                    return verified;
                }
            }
            return handleApiException(e, "POST " + path);
        }
    }

    /**
     * 取引作成前の既存取引確認（{@link DealCreateGuard}）。
     *
     * <ul>
     *   <li>REFUSE_IF_EXISTS: 前の世代が送信後に取消された再送信。同じ管理番号の取引があれば作成しない</li>
     *   <li>ADOPT_OWN_OR_REFUSE: 同じジョブの再試行。前回の試行で作成済みの取引（管理番号・金額・事業所が一致する1件）は
     *       採用して成功を返し、それ以外の同じ管理番号の取引があれば作成しない（再POSTで二重に作らない）</li>
     * </ul>
     * 確認できない（照会失敗・応答形式の異常）は再試行待ち、件数を確定できない（ページ上限）は作成せずに失敗させる。
     *
     * @return 作成を止める（または採用した）結果。作成してよければ null
     */
    private CanonicalDealResult guardDealCreation(IntegrationConnection connection, FreeeDealCreateRequest request,
                                                  String refNumber, BigDecimal expectedTotal, DealCreateGuard guard) {
        DealScan scan = refNumber == null || refNumber.isBlank()
                ? DealScan.unconfirmed() : scanDealsByRefNumber(connection, request, refNumber);
        if (scan.status() == DealScan.Status.UNCONFIRMED) {
            log.warn("既存取引を確認できないため作成を保留します: refNumber={} guard={} error_code=EXISTING_DEAL_CHECK_FAILED",
                    refNumber, guard);
            return CanonicalDealResult.builder()
                    .success(false)
                    .errorCode("EXISTING_DEAL_CHECK_FAILED")
                    .errorMessageSafe("error.accounting.existingDealCheckFailed")
                    .retryable(true)
                    .retryAfterSeconds(300)
                    .build();
        }
        if (scan.status() == DealScan.Status.LIMIT) {
            // 待っても件数は確定しない（再試行しても同じ）。作成せずに失敗させ、理由を画面に出す
            log.warn("既存取引の確認が件数上限に達したため作成しません: refNumber={} guard={} error_code=EXISTING_DEAL_CHECK_LIMIT",
                    refNumber, guard);
            return CanonicalDealResult.builder()
                    .success(false)
                    .errorCode("EXISTING_DEAL_CHECK_LIMIT")
                    .errorMessageSafe("error.accounting.existingDealCheckLimit")
                    .retryable(false)
                    .build();
        }
        List<JsonNode> matches = scan.matches();
        if (matches.isEmpty()) {
            return null;
        }
        if (guard == DealCreateGuard.ADOPT_OWN_OR_REFUSE && matches.size() == 1) {
            JsonNode d = matches.get(0);
            Long dealId = parsePositiveLong(d.get("id"));
            Long amount = d.hasNonNull("amount") ? d.get("amount").asLong() : null;
            Long companyId = d.hasNonNull("company_id") ? d.get("company_id").asLong() : null;
            boolean sameAmount = expectedTotal != null && amount != null
                    && expectedTotal.compareTo(BigDecimal.valueOf(amount)) == 0;
            if (dealId != null && sameAmount && java.util.Objects.equals(companyId, connection.getExternalCompanyId())) {
                log.info("再試行: 前回の試行で作成済みの取引を採用します（再POSTしない）: refNumber={} dealId={}", refNumber, dealId);
                return CanonicalDealResult.builder()
                        .success(true)
                        .externalId(String.valueOf(dealId))
                        .responseTotal(expectedTotal)
                        .build();
            }
        }
        log.warn("同じ管理番号の取引がfreeeに既にあるため作成しません: refNumber={} count={} guard={} error_code=EXISTING_DEAL_FOUND",
                refNumber, matches.size(), guard);
        return CanonicalDealResult.builder()
                .success(false)
                .errorCode("EXISTING_DEAL_FOUND")
                .errorMessageSafe("error.accounting.existingDealFound")
                .retryable(false)
                .build();
    }

    /** 管理番号での既存取引の走査結果。 */
    record DealScan(Status status, List<JsonNode> matches) {
        enum Status { CONFIRMED, UNCONFIRMED, LIMIT }

        static DealScan unconfirmed() {
            return new DealScan(Status.UNCONFIRMED, List.of());
        }
    }

    /** 1回の確認で読む最大ページ数（100件×50ページ）。超えると件数を確定できない。 */
    static final int DEAL_SCAN_MAX_PAGES = 50;
    /** 取引先で絞れるときの発生日の幅（取消後の訂正で請求書の発生日が変わっても見つけられるように）。 */
    static final int DEAL_SCAN_PARTNER_DATE_RANGE_DAYS = 366;

    /**
     * 管理番号（ref_number）と事業所が一致するfreee取引を探す。freeeの取引一覧は管理番号で絞れないため、
     * 作成する取引と同じ収支区分で、取引先があれば取引先と発生日の前後1年、無ければ同じ発生日に絞って走査する。
     * 照会失敗と応答形式の異常（deals が配列でない等）は確認できない扱い（作成しない側へ倒す）。
     */
    DealScan scanDealsByRefNumber(IntegrationConnection connection, FreeeDealCreateRequest request, String refNumber) {
        Long expectedCompanyId = connection.getExternalCompanyId();
        String filter = dealScanFilter(request);
        int limit = 100;
        List<JsonNode> matches = new ArrayList<>();
        for (int page = 0; page < DEAL_SCAN_MAX_PAGES; page++) {
            String checkUrl = apiBaseUrl + "/api/1/deals?company_id=" + expectedCompanyId + filter
                    + "&limit=" + limit + "&offset=" + (page * limit);
            try {
                ResponseEntity<String> res = executeWith401Recovery(connection, headers -> {
                    HttpEntity<?> entity = new HttpEntity<>(headers);
                    return restTemplate.exchange(checkUrl, HttpMethod.GET, entity, String.class);
                });
                JsonNode deals = res.getBody() == null || res.getBody().isBlank()
                        ? null : objectMapper.readTree(res.getBody()).get("deals");
                if (deals == null || !deals.isArray()) {
                    log.warn("既存取引の確認で想定外の応答形式: page={} error_code=INVALID_RESPONSE", page);
                    return DealScan.unconfirmed();
                }
                for (JsonNode d : deals) {
                    if (!d.hasNonNull("ref_number") || !refNumber.equals(d.get("ref_number").asText())) {
                        continue;
                    }
                    Long companyId = d.hasNonNull("company_id") ? d.get("company_id").asLong() : null;
                    if (companyId == null || java.util.Objects.equals(companyId, expectedCompanyId)) {
                        matches.add(d); // 事業所不明の行も安全側（既存あり）に数える
                    }
                }
                if (deals.size() < limit) {
                    return new DealScan(DealScan.Status.CONFIRMED, matches);
                }
            } catch (Exception ex) {
                log.warn("既存取引の確認に失敗: page={} error_code=UNKNOWN_ERROR", page);
                return DealScan.unconfirmed();
            }
        }
        return new DealScan(DealScan.Status.LIMIT, matches); // 全ページ満杯: 続きがあり得るため確定できない
    }

    /** 既存取引の走査条件（収支区分・取引先・発生日）。作成する取引の内容から決める。 */
    static String dealScanFilter(FreeeDealCreateRequest request) {
        if (request == null) {
            return "";
        }
        StringBuilder filter = new StringBuilder();
        if (request.getType() != null && !request.getType().isBlank()) {
            filter.append("&type=").append(request.getType());
        }
        LocalDate issueDate;
        try {
            issueDate = request.getIssueDate() == null ? null : LocalDate.parse(request.getIssueDate());
        } catch (java.time.format.DateTimeParseException e) {
            issueDate = null;
        }
        if (request.getPartnerId() != null) {
            filter.append("&partner_id=").append(request.getPartnerId());
            if (issueDate != null) {
                filter.append("&start_issue_date=").append(issueDate.minusDays(DEAL_SCAN_PARTNER_DATE_RANGE_DAYS))
                        .append("&end_issue_date=").append(issueDate.plusDays(DEAL_SCAN_PARTNER_DATE_RANGE_DAYS));
            }
        } else if (issueDate != null) {
            filter.append("&start_issue_date=").append(issueDate).append("&end_issue_date=").append(issueDate);
        }
        return filter.toString();
    }

    @Override
    public Optional<String> findDealIdByRefNumber(IntegrationConnection connection, String refNumber) {
        if (connection == null || refNumber == null || refNumber.isBlank()) {
            return Optional.empty();
        }
        try {
            connection = requireAuthoritativeConnection(connection);
        } catch (IllegalStateException e) {
            log.warn("freee deal lookup skipped: error_code=CONNECTION_COMPANY_ID_INVALID");
            return Optional.empty();
        }
        Long expectedCompanyId = connection.getExternalCompanyId();
        List<String> matched = new ArrayList<>();
        int limit = 100;
        int maxPages = 50;
        boolean complete = false;
        for (int page = 0; page < maxPages; page++) {
            int offset = page * limit;
            try {
                String checkUrl = apiBaseUrl + "/api/1/deals?company_id=" + connection.getExternalCompanyId()
                        + "&limit=" + limit + "&offset=" + offset;
                ResponseEntity<String> res = executeWith401Recovery(connection, headers -> {
                    HttpEntity<?> entity = new HttpEntity<>(headers);
                    return restTemplate.exchange(checkUrl, HttpMethod.GET, entity, String.class);
                });
                JsonNode deals = res.getBody() == null || res.getBody().isBlank()
                        ? null : objectMapper.readTree(res.getBody()).get("deals");
                if (deals == null || !deals.isArray()) {
                    // 応答形式の異常は「無い」と確定できない: 採用しない（再試行時の事前確認に任せる）
                    log.warn("findDealIdByRefNumber got an unexpected response: error_code=INVALID_RESPONSE");
                    return Optional.empty();
                }
                if (deals.isEmpty()) {
                    complete = true;
                    break;
                }
                for (JsonNode d : deals) {
                    if (!d.has("ref_number") || !refNumber.equals(d.get("ref_number").asText())) {
                        continue;
                    }
                    Long companyId = d.has("company_id") ? d.get("company_id").asLong() : null;
                    if (companyId == null || !java.util.Objects.equals(companyId, expectedCompanyId)) {
                        continue;
                    }
                    Long dealId = parsePositiveLong(d.get("id"));
                    if (dealId == null) {
                        log.warn("findDealIdByRefNumber skipped a deal without a valid id: error_code=INVALID_DEAL_ID");
                        continue;
                    }
                    matched.add(String.valueOf(dealId));
                }
                if (deals.size() < limit) {
                    complete = true;
                    break;
                }
            } catch (Exception ex) {
                log.warn("findDealIdByRefNumber failed for refNumber={}: error_code=UNKNOWN_ERROR", refNumber);
                return Optional.empty();
            }
        }
        if (!complete) {
            // 50ページすべて満杯: 続きに同じ管理番号の取引があり得るため一意と判断しない（fail-closed）
            log.warn("findDealIdByRefNumber reached the page cap for refNumber={} (matched={}), refuse SUCCEEDED",
                    refNumber, matched.size());
            return Optional.empty();
        }
        if (matched.size() == 1) {
            return Optional.of(matched.get(0));
        }
        if (matched.size() > 1) {
            log.warn("Ambiguous deals for refNumber={} (count={}), refuse SUCCEEDED to avoid wrong binding",
                    refNumber, matched.size());
        }
        return Optional.empty();
    }

    private CanonicalDealResult verifyDealCreatedByRefNumber(IntegrationConnection connection,
                                                             String refNumber,
                                                             BigDecimal expectedTotal,
                                                             Long expectedCompanyId) {
        // R1-P1-03: 全ページを走査し、3項目 (ref_number + amount + company_id) 完全一致の件数を一意に特定する。
        // 不一致行があっても走査を継続し、完全一致が1件だけなら成功、複数なら曖昧として fail-closed (再POST抑止)。
        int limit = 100;
        int maxPages = 50;
        List<String> strictlyMatchedDealIds = new ArrayList<>();
        boolean pageCapReached = false;

        for (int page = 0; page < maxPages; page++) {
            int offset = page * limit;
            try {
                String checkUrl = apiBaseUrl + "/api/1/deals?company_id=" + connection.getExternalCompanyId()
                        + "&limit=" + limit + "&offset=" + offset;
                ResponseEntity<String> res = executeWith401Recovery(connection, headers -> {
                    HttpEntity<?> entity = new HttpEntity<>(headers);
                    return restTemplate.exchange(checkUrl, HttpMethod.GET, entity, String.class);
                });
                if (res.getBody() != null) {
                    JsonNode root = objectMapper.readTree(res.getBody());
                    JsonNode deals = root.get("deals");
                    if (deals != null && deals.isArray() && !deals.isEmpty()) {
                        for (JsonNode d : deals) {
                            if (d.has("ref_number") && refNumber.equals(d.get("ref_number").asText())) {
                                Long dealId = parsePositiveLong(d.get("id"));
                                Long amount = d.has("amount") ? d.get("amount").asLong() : null;
                                Long companyId = d.has("company_id") ? d.get("company_id").asLong() : null;

                                if (dealId == null) {
                                    log.warn("Deal verification skipped a row without a valid id: error_code=INVALID_DEAL_ID");
                                    continue;
                                }

                                boolean strictAmountMatch = expectedTotal != null && amount != null
                                        && expectedTotal.compareTo(BigDecimal.valueOf(amount)) == 0;
                                boolean strictCompanyMatch = expectedCompanyId != null && companyId != null
                                        && java.util.Objects.equals(companyId, expectedCompanyId);

                                if (strictAmountMatch && strictCompanyMatch) {
                                    strictlyMatchedDealIds.add(String.valueOf(dealId));
                                    log.info("Found strictly matching deal for refNumber={}, amount={}, companyId={} (page={}): dealId={}",
                                            refNumber, amount, companyId, page, dealId);
                                } else {
                                    log.warn("Deal found with refNumber={} but strict match failed (amount expected={}, actual={}; company expected={}, actual={}), continuing scan",
                                            refNumber, expectedTotal, amount, expectedCompanyId, companyId);
                                }
                            }
                        }
                        if (deals.size() < limit) {
                            break;
                        }
                        if (page == maxPages - 1) {
                            // R1-P1-03: 50ページ目も full なら次ページ以降が存在し得る -> page cap (一意性未確定)
                            pageCapReached = true;
                        }
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            } catch (Exception ex) {
                log.warn("Failed to check existing deal by refNumber after timeout on page {}: error_code=UNKNOWN_ERROR", page);
                return null;
            }
        }

        // R1-P1-03: 50ページ上限に到達した場合、完全一致数にかかわらず一意性を確定できないため fail-closed (RETRYABLE)
        if (pageCapReached) {
            log.warn("Reached 50-page cap while verifying deal by refNumber={} after timeout (matched={}), page-cap fail-closed",
                    refNumber, strictlyMatchedDealIds.size());
            return null;
        }

        // 全ページ走査後の一意性判定 (R1-P1-03)
        if (strictlyMatchedDealIds.size() == 1) {
            String dealId = strictlyMatchedDealIds.get(0);
            return CanonicalDealResult.builder()
                    .success(true)
                    .externalId(dealId)
                    .responseTotal(expectedTotal)
                    .errorMessageSafe("タイムアウト後に外部照合により取引作成を確認 (dealId=" + dealId + ")")
                    .build();
        }
        if (strictlyMatchedDealIds.size() > 1) {
            log.warn("Multiple strictly matching deals found for refNumber={} after timeout (dealIds={}), ambiguous fail-closed",
                    refNumber, strictlyMatchedDealIds);
            return null; // 曖昧: 二重登録防止のため成功扱いしない
        }
        log.warn("No strictly matching deal found for refNumber={} after timeout, fail-closed (retryable)", refNumber);
        return null;
    }

    private Long parsePositiveLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            long value;
            if (node.isIntegralNumber()) {
                BigInteger integerValue = node.bigIntegerValue();
                if (integerValue.compareTo(BigInteger.ONE) < 0
                        || integerValue.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                    return null;
                }
                value = integerValue.longValue();
            } else if (node.isTextual()) {
                value = Long.parseLong(node.textValue());
            } else {
                return null;
            }
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private <T> ResponseEntity<T> executeWith401Recovery(IntegrationConnection connection,
                                                         RestExecution<T> execution) {
        com.ses.dto.accounting.TokenSnapshot tokenSnapshot = connectionService.getTokenSnapshot(connection.getId());
        if (tokenSnapshot == null || tokenSnapshot.getAccessToken() == null || tokenSnapshot.getAccessToken().isBlank()) {
            throw new IllegalStateException("連携トークンが設定されていません (connectionId=" + connection.getId() + ")");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(tokenSnapshot.getAccessToken());
        Integer observedVersion = tokenSnapshot.getTokenVersion();

        try {
            return execution.execute(headers);
        } catch (HttpClientErrorException httpEx) {
            if (httpEx.getStatusCode().value() == 401) {
                log.warn("Received 401 from freee API, attempting forced token refresh with observedVersion={} and single replay", observedVersion);
                try {
                    // トークン強制リフレッシュ (P1-03: 実際にヘッダーで使用した token_version を渡す)
                    IntegrationTokensDto refreshed = connectionService.forceRefreshToken(
                            connection.getId(),
                            observedVersion,
                            this::refreshFreeeTokens);
                    if (refreshed != null && refreshed.getAccessToken() != null) {
                        HttpHeaders newHeaders = new HttpHeaders();
                        newHeaders.setContentType(MediaType.APPLICATION_JSON);
                        newHeaders.setBearerAuth(refreshed.getAccessToken());
                        // 最大1回のリプレイ実行
                        return execution.execute(newHeaders);
                    }
                } catch (Exception refreshEx) {
                    log.error("Token refresh or replay failed on 401: error_code=UNAUTHORIZED");
                    throw httpEx;
                }
            }
            throw httpEx;
        }
    }

    private IntegrationTokensDto refreshFreeeTokens(IntegrationTokensDto currentTokens) {
        if (currentTokens == null || currentTokens.getRefreshToken() == null || currentTokens.getRefreshToken().isBlank()) {
            throw new IllegalStateException("リフレッシュトークンが存在しません");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", currentTokens.getRefreshToken());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        ResponseEntity<Map> response = restTemplate.exchange(tokenUrl(), HttpMethod.POST, request, Map.class);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Map<?, ?> body = response.getBody();
            String newAccessToken = (String) body.get("access_token");
            String newRefreshToken = (String) body.get("refresh_token");
            Number expiresIn = (Number) body.get("expires_in");

            // S15-P1-02: refresh_token欠落時は旧tokenへフォールバックせずfail-closed（REAUTH誘発）
            if (newAccessToken == null || newAccessToken.isBlank()
                    || newRefreshToken == null || newRefreshToken.isBlank()) {
                throw new IllegalStateException(
                        "invalid_grant: freee token response missing access_token/refresh_token (fail-closed)");
            }

            return IntegrationTokensDto.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(newRefreshToken)
                    .expiresIn(expiresIn != null ? expiresIn.longValue() : 3600L)
                    .build();
        }
        throw new RuntimeException("freee token refresh failed with status: " + response.getStatusCode());
    }

    @FunctionalInterface
    private interface RestExecution<T> {
        ResponseEntity<T> execute(HttpHeaders headers);
    }

    private CanonicalDealResult handleApiException(Exception e, String apiPath) {
        if (e instanceof HttpClientErrorException httpEx) {
            int statusCode = httpEx.getStatusCode().value();
            String reqId = extractRequestId(httpEx.getResponseHeaders());

            // R1-P1-10: 外部応答本文・例外メッセージは一切ログ・DTO・jobへ渡さない (固定文言のみ)
            if (statusCode == 400 || statusCode == 422) {
                log.warn("freee validation error: status_code={}, error_code=VALIDATION_ERROR", statusCode);
                return CanonicalDealResult.builder()
                        .success(false)
                        .providerRequestId(reqId)
                        .errorCode("VALIDATION_ERROR")
                        .errorMessageSafe("error.accounting.freeeValidation")
                        .retryable(false) // 400/422はリトライしない (design §6.3)
                        .build();
            } else if (statusCode == 401) {
                log.warn("freee unauthorized: status_code=401, error_code=UNAUTHORIZED");
                return CanonicalDealResult.builder()
                        .success(false)
                        .providerRequestId(reqId)
                        .errorCode("UNAUTHORIZED")
                        .errorMessageSafe("error.accounting.freeeUnauthorized")
                        .retryable(false)
                        .build();
            } else if (statusCode == 403) {
                log.warn("freee forbidden / plan limitation: status_code=403, error_code=PLAN_LIMITATION");
                return CanonicalDealResult.builder()
                        .success(false)
                        .providerRequestId(reqId)
                        .errorCode("PLAN_LIMITATION")
                        .errorMessageSafe("error.accounting.freeeForbidden")
                        .retryable(false) // プラン制限はリトライしない
                        .build();
            } else if (statusCode == 429) {
                int retryAfter = 60;
                List<String> retryHeaders = httpEx.getResponseHeaders() != null ?
                        httpEx.getResponseHeaders().get("Retry-After") : null;
                if (retryHeaders != null && !retryHeaders.isEmpty()) {
                    try {
                        retryAfter = Integer.parseInt(retryHeaders.get(0));
                    } catch (NumberFormatException ignored) {
                    }
                }
                log.warn("freee rate limited: status_code=429, retryAfter={}", retryAfter);
                return CanonicalDealResult.builder()
                        .success(false)
                        .providerRequestId(reqId)
                        .errorCode("RATE_LIMITED")
                        .errorMessageSafe("error.accounting.freeeRateLimited")
                        .retryable(true)
                        .retryAfterSeconds(retryAfter)
                        .build();
            } else {
                log.warn("freee http client error: status_code={}, error_code=HTTP_CLIENT_ERROR", statusCode);
                return CanonicalDealResult.builder()
                        .success(false)
                        .providerRequestId(reqId)
                        .errorCode("HTTP_CLIENT_ERROR")
                        .errorMessageSafe("error.accounting.freeeHttpError")
                        .retryable(false)
                        .build();
            }
        } else if (e instanceof HttpServerErrorException serverEx) {
            String reqId = extractRequestId(serverEx.getResponseHeaders());
            log.error("freee server error: status_code={}, error_code=SERVER_ERROR", serverEx.getStatusCode());
            return CanonicalDealResult.builder()
                    .success(false)
                    .providerRequestId(reqId)
                    .errorCode("SERVER_ERROR")
                    .errorMessageSafe("error.accounting.freeeServerError")
                    .retryable(true)
                    .retryAfterSeconds(30)
                    .build();
        } else if (e instanceof ResourceAccessException resourceEx) {
            log.error("freee API connection timeout / network error: error_code=TIMEOUT");
            return CanonicalDealResult.builder()
                    .success(false)
                    .errorCode("TIMEOUT")
                    .errorMessageSafe("error.accounting.freeeTimeout")
                    .retryable(true)
                    .retryAfterSeconds(30)
                    .build();
        }

        log.error("Unexpected error calling freee API: api_path={}, error_code=UNKNOWN_ERROR", apiPath);
        return CanonicalDealResult.builder()
                .success(false)
                .errorCode("UNKNOWN_ERROR")
                .errorMessageSafe("予期せぬエラーが発生しました")
                .retryable(false)
                .build();
    }

    private HttpHeaders buildAuthHeaders(IntegrationConnection connection) {
        IntegrationTokensDto tokens = connectionService.getDecryptedTokens(connection.getId());
        if (tokens == null || tokens.getAccessToken() == null || tokens.getAccessToken().isBlank()) {
            throw new IllegalStateException("連携トークンが設定されていません (connectionId=" + connection.getId() + ")");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(tokens.getAccessToken());
        return headers;
    }

    private String extractRequestId(HttpHeaders headers) {
        if (headers == null) return null;
        List<String> vals = headers.get("X-Freee-Request-ID");
        return (vals != null && !vals.isEmpty()) ? vals.get(0) : null;
    }

    /**
     * エラーレスポンスの JSON Allow-list 抽出および PII・機密情報サニタイズ (P1-10)。
     */
    public String sanitizeErrorResponse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return "";

        StringBuilder sanitized = new StringBuilder();
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (root.has("errors") && root.get("errors").isArray()) {
                for (JsonNode err : root.get("errors")) {
                    if (err.has("messages") && err.get("messages").isArray()) {
                        for (JsonNode m : err.get("messages")) {
                            if (sanitized.length() > 0) sanitized.append("; ");
                            sanitized.append(m.asText());
                        }
                    } else if (err.has("message")) {
                        if (sanitized.length() > 0) sanitized.append("; ");
                        sanitized.append(err.get("message").asText());
                    }
                }
            } else if (root.has("message")) {
                sanitized.append(root.get("message").asText());
            } else if (root.has("error_description")) {
                sanitized.append(root.get("error_description").asText());
            } else if (root.has("error")) {
                sanitized.append(root.get("error").asText());
            }
        } catch (Exception ignored) {
            // JSON パース失敗時は英数記号のみ抽出
        }

        String result = sanitized.length() > 0 ? sanitized.toString() : "入力値またはリクエスト内容に問題があります";
        // PII / トークン相当の正規表現除去
        result = PII_REDACT_PATTERN.matcher(result).replaceAll("[REDACTED]");
        if (result.length() > 200) {
            result = result.substring(0, 200) + "...";
        }
        return result;
    }

    private FreeeDealCreateRequest buildSalesDealRequest(IntegrationConnection connection, CanonicalSalesInvoice invoice) {
        List<FreeeDealDetailDto> details = new ArrayList<>();
        if (invoice.getDetails() != null && !invoice.getDetails().isEmpty()) {
            for (CanonicalSalesInvoice.Detail d : invoice.getDetails()) {
                details.add(FreeeDealDetailDto.builder()
                        .taxCode(parseInteger(d.getTaxCode(), 21)) // デフォルト課税売上10%
                        .accountItemId(parseFreeeId(d.getAccountItemCode()))
                        .amount(d.getAmount() != null ? d.getAmount().longValue() : 0L)
                        .vat(d.getVat() != null ? d.getVat().longValue() : null)
                        .description(d.getDescription())
                        .build());
            }
        } else {
            details.add(FreeeDealDetailDto.builder()
                    .taxCode(21)
                    .amount(invoice.getTotal() != null ? invoice.getTotal().longValue() : 0L)
                    .description(invoice.getRemarks() != null ? invoice.getRemarks() : "SES請求: " + invoice.getInvoiceNo())
                    .build());
        }

        return FreeeDealCreateRequest.builder()
                .companyId(connection.getExternalCompanyId())
                .issueDate(invoice.getIssueDate() != null ? invoice.getIssueDate().toString() : LocalDate.now().toString())
                .dueDate(invoice.getDueDate() != null ? invoice.getDueDate().toString() : null)
                .type("income")
                .partnerId(parseFreeeId(invoice.getPartnerExternalId()))
                // 旧snapshot（partnerExternalId導入前に登録されたjob）だけ従来どおり取引先コードで送る
                .partnerCode(invoice.getPartnerExternalId() == null ? invoice.getCustomerCode() : null)
                .refNumber(invoice.getInvoiceNo())
                .details(details)
                .build();
    }

    private FreeeDealCreateRequest buildPurchaseDealRequest(IntegrationConnection connection, CanonicalPurchaseDeal purchase) {
        List<FreeeDealDetailDto> details = List.of(FreeeDealDetailDto.builder()
                .taxCode(parseInteger(purchase.getTaxCode(), 108)) // 課税仕入10%
                .accountItemId(parseFreeeId(purchase.getAccountItemCode()))
                .amount(purchase.getAmount() != null ? purchase.getAmount().longValue() : 0L)
                .description(purchase.getRemarks() != null ? purchase.getRemarks() : "BP外注費: " + purchase.getBpCompanyName())
                .build());

        return FreeeDealCreateRequest.builder()
                .companyId(connection.getExternalCompanyId())
                .issueDate(purchase.getIssueDate() != null ? purchase.getIssueDate().toString() : LocalDate.now().toString())
                .dueDate(purchase.getDueDate() != null ? purchase.getDueDate().toString() : null)
                .type("expense")
                .partnerId(parseFreeeId(purchase.getPartnerExternalId()))
                .partnerCode(purchase.getPartnerExternalId() == null ? purchase.getBpCompanyCode() : null)
                .refNumber("BP-" + purchase.getBpPaymentId())
                .details(details)
                .build();
    }

    private FreeeDealCreateRequest buildExpenseDealRequest(IntegrationConnection connection, CanonicalExpenseDeal expense) {
        List<FreeeDealDetailDto> details = List.of(FreeeDealDetailDto.builder()
                .taxCode(parseInteger(expense.getTaxCode(), 108))
                .accountItemId(parseFreeeId(expense.getAccountItemCode()))
                .amount(expense.getAmount() != null ? expense.getAmount().longValue() : 0L)
                .description(expense.getDescription() != null ? expense.getDescription() : "経費: " + expense.getCategory())
                .build());

        return FreeeDealCreateRequest.builder()
                .companyId(connection.getExternalCompanyId())
                .issueDate(expense.getExpenseDate() != null ? expense.getExpenseDate().toString() : LocalDate.now().toString())
                .type("expense")
                // 要員はfreee取引先として管理しないため取引先は指定しない。要員コードを取引先コードとして送ると、
                // 取引先コード無効の事業所では400、有効でも該当取引先が無ければ400になる。
                .refNumber(expense.getExpenseNo())
                .details(details)
                .build();
    }

    /** マッピングのfreee ID（取引先・勘定科目）を数値へ変換する。int範囲を超えるIDも扱えるようLongにする。数値でない値は送らない。 */
    static Long parseFreeeId(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(externalId.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInteger(String str, Integer defaultVal) {
        if (str == null || str.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // === Official API Schema DTOs ===

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public static class FreeeDealCreateRequest {
        @JsonProperty("company_id")
        private Long companyId;

        @JsonProperty("issue_date")
        private String issueDate;

        @JsonProperty("due_date")
        private String dueDate;

        @JsonProperty("type")
        private String type;

        /** freee取引先ID（照合済みマッピングの外部ID）。partner_code とは同時に送らない。 */
        @JsonProperty("partner_id")
        private Long partnerId;

        /** 旧snapshot互換のみ。事業所設定で取引先コードが有効な場合に限りfreee側で解決される。 */
        @JsonProperty("partner_code")
        private String partnerCode;

        @JsonProperty("ref_number")
        private String refNumber;

        @JsonProperty("details")
        private List<FreeeDealDetailDto> details;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FreeeDealDetailDto {
        @JsonProperty("tax_code")
        private Integer taxCode;

        @JsonProperty("account_item_id")
        private Long accountItemId;

        @JsonProperty("amount")
        private Long amount;

        /** 明細の消費税額。nullなら送らない（freeeが税込金額から計算する）。 */
        @JsonProperty("vat")
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Long vat;

        @JsonProperty("description")
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FreeeDealCreateResponse {
        @JsonProperty("deal")
        private FreeeDealDto deal;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FreeeDealDto {
        @JsonProperty("id")
        private Long id;

        @JsonProperty("amount")
        private Long amount;

        @JsonProperty("status")
        private String status;

        @JsonProperty("issue_date")
        private String issueDate;
    }
}
