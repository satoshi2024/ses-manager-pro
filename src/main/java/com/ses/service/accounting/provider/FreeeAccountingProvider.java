package com.ses.service.accounting.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * freee 会計 Public API Provider (design §2, §6.3)。
 * Official API schema DTO を分離し、raw Map を業務へ漏らさない。
 * 秘密情報 (Token等) はログ出力しない。
 */
@Slf4j
@Component("freeeAccountingProvider")
@RequiredArgsConstructor
public class FreeeAccountingProvider implements AccountingProvider {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final IntegrationConnectionService connectionService;

    @Value("${freee.api-base-url:https://api.freee.co.jp}")
    private String apiBaseUrl;

    @Override
    public String providerName() {
        return "freee";
    }

    @Override
    public CanonicalDealResult upsertSalesInvoice(IntegrationConnection connection, CanonicalSalesInvoice invoice) {
        log.info("Upserting sales invoice to freee: invoiceNo={}, customerCode={}, total={}",
                invoice.getInvoiceNo(), invoice.getCustomerCode(), invoice.getTotal());

        FreeeDealCreateRequest req = buildSalesDealRequest(connection, invoice);
        return executeDealPost(connection, req, invoice.getTotal());
    }

    @Override
    public CanonicalDealResult cancelSalesInvoice(IntegrationConnection connection, String externalDealId, String reason) {
        log.info("Cancelling sales deal in freee: externalDealId={}, reason={}", externalDealId, reason);
        if (externalDealId == null || externalDealId.isBlank()) {
            return CanonicalDealResult.builder()
                    .success(false)
                    .errorCode("INVALID_EXTERNAL_ID")
                    .errorMessageSafe("外部取引IDが指定されていません")
                    .retryable(false)
                    .build();
        }

        String url = apiBaseUrl + "/api/1/deals/" + externalDealId + "?company_id=" + connection.getExternalCompanyId();
        try {
            HttpHeaders headers = buildAuthHeaders(connection);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);

            String reqId = extractRequestId(response.getHeaders());
            return CanonicalDealResult.builder()
                    .success(true)
                    .externalId(externalDealId)
                    .providerRequestId(reqId)
                    .build();
        } catch (Exception e) {
            return handleApiException(e, "DELETE /api/1/deals/" + externalDealId);
        }
    }

    @Override
    public CanonicalDealResult upsertPurchaseDeal(IntegrationConnection connection, CanonicalPurchaseDeal purchase) {
        log.info("Upserting purchase deal to freee: bpPaymentId={}, bpCompanyCode={}, amount={}",
                purchase.getBpPaymentId(), purchase.getBpCompanyCode(), purchase.getAmount());

        FreeeDealCreateRequest req = buildPurchaseDealRequest(connection, purchase);
        return executeDealPost(connection, req, purchase.getAmount());
    }

    @Override
    public CanonicalDealResult upsertExpenseDeal(IntegrationConnection connection, CanonicalExpenseDeal expense) {
        log.info("Upserting expense deal to freee: expenseNo={}, engineerCode={}, amount={}",
                expense.getExpenseNo(), expense.getEngineerCode(), expense.getAmount());

        FreeeDealCreateRequest req = buildExpenseDealRequest(connection, expense);
        return executeDealPost(connection, req, expense.getAmount());
    }

    @Override
    public List<CanonicalPaymentSync> fetchPayments(IntegrationConnection connection, LocalDate fromDate, LocalDate toDate) {
        log.info("Fetching payments from freee: companyId={}, fromDate={}, toDate={}",
                connection.getExternalCompanyId(), fromDate, toDate);

        String url = String.format("%s/api/1/deals?company_id=%d&start_issue_date=%s&end_issue_date=%s&status=settled",
                apiBaseUrl, connection.getExternalCompanyId(), fromDate, toDate);

        try {
            HttpHeaders headers = buildAuthHeaders(connection);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<FreeeDealsListResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, FreeeDealsListResponse.class);

            List<CanonicalPaymentSync> result = new ArrayList<>();
            if (response.getBody() != null && response.getBody().getDeals() != null) {
                for (FreeeDealDto deal : response.getBody().getDeals()) {
                    if (deal.getPayments() != null) {
                        for (FreeePaymentDto payment : deal.getPayments()) {
                            result.add(CanonicalPaymentSync.builder()
                                    .externalId(String.valueOf(payment.getId()))
                                    .dealId(String.valueOf(deal.getId()))
                                    .paymentDate(payment.getDate())
                                    .amount(payment.getAmount())
                                    .status(deal.getStatus())
                                    .referenceNo(deal.getRefNumber())
                                    .build());
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch payments from freee", e);
            return Collections.emptyList();
        }
    }

    @Override
    public CanonicalPaymentSync fetchDealPayment(IntegrationConnection connection, String externalDealId) {
        if (connection == null || connection.getExternalCompanyId() == null || externalDealId == null) {
            return null;
        }

        String url = String.format("%s/api/1/deals/%s?company_id=%d",
                apiBaseUrl, externalDealId, connection.getExternalCompanyId());

        try {
            HttpHeaders headers = buildAuthHeaders(connection);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<FreeeDealSingleResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, FreeeDealSingleResponse.class);

            if (response.getBody() != null && response.getBody().getDeal() != null) {
                FreeeDealDto deal = response.getBody().getDeal();
                if (deal.getPayments() != null && !deal.getPayments().isEmpty()) {
                    FreeePaymentDto payment = deal.getPayments().get(0);
                    return CanonicalPaymentSync.builder()
                            .externalId(String.valueOf(payment.getId()))
                            .dealId(String.valueOf(deal.getId()))
                            .paymentDate(payment.getDate())
                            .amount(payment.getAmount())
                            .status(deal.getStatus())
                            .referenceNo(deal.getRefNumber())
                            .build();
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to fetch deal payment from freee for dealId={}", externalDealId, e);
            return null;
        }
    }

    @Override
    public boolean validateConnection(IntegrationConnection connection) {
        if (connection == null || connection.getExternalCompanyId() == null) {
            return false;
        }
        String url = apiBaseUrl + "/api/1/users/me";
        try {
            HttpHeaders headers = buildAuthHeaders(connection);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return res.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("freee connection validation failed for connectionId={}", connection.getId());
            return false;
        }
    }

    // === 内部リクエスト送信 & エラー分類 ===

    private CanonicalDealResult executeDealPost(IntegrationConnection connection, FreeeDealCreateRequest request,
                                               BigDecimal expectedAmount) {
        String url = apiBaseUrl + "/api/1/deals";
        try {
            HttpHeaders headers = buildAuthHeaders(connection);
            HttpEntity<FreeeDealCreateRequest> entity = new HttpEntity<>(request, headers);
            ResponseEntity<FreeeDealSingleResponse> response = restTemplate.postForEntity(
                    url, entity, FreeeDealSingleResponse.class);

            String reqId = extractRequestId(response.getHeaders());
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().getDeal() != null) {
                FreeeDealDto deal = response.getBody().getDeal();
                BigDecimal responseAmount = deal.getAmount();

                // 金額照合 (design §4: 不一致時は succeeded にしない)
                if (expectedAmount != null && responseAmount != null && expectedAmount.compareTo(responseAmount) != 0) {
                    log.error("freee response amount mismatch: expected={}, actual={}", expectedAmount, responseAmount);
                    return CanonicalDealResult.builder()
                            .success(false)
                            .externalId(String.valueOf(deal.getId()))
                            .providerRequestId(reqId)
                            .errorCode("AMOUNT_MISMATCH")
                            .errorMessageSafe(String.format("送信金額(%s)とfreee登録金額(%s)が一致しません", expectedAmount, responseAmount))
                            .retryable(false)
                            .responseTotal(responseAmount)
                            .build();
                }

                return CanonicalDealResult.builder()
                        .success(true)
                        .externalId(String.valueOf(deal.getId()))
                        .providerRequestId(reqId)
                        .responseTotal(responseAmount)
                        .build();
            }

            return CanonicalDealResult.builder()
                    .success(false)
                    .errorCode("EMPTY_RESPONSE")
                    .errorMessageSafe("freee からの応答が空です")
                    .retryable(false)
                    .build();

        } catch (Exception e) {
            return handleApiException(e, "POST /api/1/deals");
        }
    }

    private CanonicalDealResult handleApiException(Exception e, String apiPath) {
        if (e instanceof HttpClientErrorException httpEx) {
            int statusCode = httpEx.getStatusCode().value();
            String reqId = extractRequestId(httpEx.getResponseHeaders());
            String safeBody = sanitizeErrorResponse(httpEx.getResponseBodyAsString());

            if (statusCode == 400 || statusCode == 422) {
                log.warn("freee validation error [{}]: {}", statusCode, safeBody);
                return CanonicalDealResult.builder()
                        .success(false)
                        .providerRequestId(reqId)
                        .errorCode("VALIDATION_ERROR")
                        .errorMessageSafe("freee API入力検証エラー (" + statusCode + "): " + safeBody)
                        .retryable(false) // 400/422はリトライしない (design §6.3)
                        .build();
            } else if (statusCode == 401) {
                log.warn("freee unauthorized (401)");
                return CanonicalDealResult.builder()
                        .success(false)
                        .providerRequestId(reqId)
                        .errorCode("UNAUTHORIZED")
                        .errorMessageSafe("認証エラー: トークンの有効期限切れまたは無効")
                        .retryable(true) // 1回リフレッシュ可能
                        .build();
            } else if (statusCode == 403) {
                log.warn("freee forbidden / plan limitation (403): {}", safeBody);
                return CanonicalDealResult.builder()
                        .success(false)
                        .providerRequestId(reqId)
                        .errorCode("PLAN_LIMITATION")
                        .errorMessageSafe("freee 契約プラン制限またはアクセス権限不足です。CSV出力をご利用ください。")
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
                log.warn("freee rate limited (429), retryAfter={}", retryAfter);
                return CanonicalDealResult.builder()
                        .success(false)
                        .providerRequestId(reqId)
                        .errorCode("RATE_LIMITED")
                        .errorMessageSafe("APIレートリミットに達しました。次回再試行します。")
                        .retryable(true)
                        .retryAfterSeconds(retryAfter)
                        .build();
            } else {
                return CanonicalDealResult.builder()
                        .success(false)
                        .providerRequestId(reqId)
                        .errorCode("HTTP_CLIENT_ERROR")
                        .errorMessageSafe("HTTP " + statusCode + " エラー: " + safeBody)
                        .retryable(false)
                        .build();
            }
        } else if (e instanceof HttpServerErrorException serverEx) {
            String reqId = extractRequestId(serverEx.getResponseHeaders());
            log.error("freee server error: {}", serverEx.getStatusCode());
            return CanonicalDealResult.builder()
                    .success(false)
                    .providerRequestId(reqId)
                    .errorCode("SERVER_ERROR")
                    .errorMessageSafe("freee サーバー一時エラー (" + serverEx.getStatusCode() + ")")
                    .retryable(true)
                    .retryAfterSeconds(30)
                    .build();
        } else if (e instanceof ResourceAccessException resourceEx) {
            log.error("freee API connection timeout / network error", resourceEx);
            return CanonicalDealResult.builder()
                    .success(false)
                    .errorCode("TIMEOUT")
                    .errorMessageSafe("freee APIへの接続タイムアウトまたはネットワークエラー")
                    .retryable(true)
                    .retryAfterSeconds(30)
                    .build();
        }

        log.error("Unexpected error calling freee API: {}", apiPath, e);
        return CanonicalDealResult.builder()
                .success(false)
                .errorCode("UNKNOWN_ERROR")
                .errorMessageSafe("予期せぬエラー: " + e.getMessage())
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

    private String sanitizeErrorResponse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return "";
        if (rawBody.length() > 300) {
            return rawBody.substring(0, 300) + "...";
        }
        return rawBody;
    }

    private FreeeDealCreateRequest buildSalesDealRequest(IntegrationConnection connection, CanonicalSalesInvoice invoice) {
        List<FreeeDealDetailDto> details = new ArrayList<>();
        if (invoice.getDetails() != null && !invoice.getDetails().isEmpty()) {
            for (CanonicalSalesInvoice.Detail d : invoice.getDetails()) {
                details.add(FreeeDealDetailDto.builder()
                        .taxCode(parseInteger(d.getTaxCode(), 21)) // デフォルト課税売上10%
                        .accountItemId(parseInteger(d.getAccountItemCode(), null))
                        .amount(d.getAmount() != null ? d.getAmount().longValue() : 0L)
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
                .partnerCode(invoice.getCustomerCode())
                .refNumber(invoice.getInvoiceNo())
                .details(details)
                .build();
    }

    private FreeeDealCreateRequest buildPurchaseDealRequest(IntegrationConnection connection, CanonicalPurchaseDeal purchase) {
        List<FreeeDealDetailDto> details = List.of(FreeeDealDetailDto.builder()
                .taxCode(parseInteger(purchase.getTaxCode(), 108)) // 課税仕入10%
                .accountItemId(parseInteger(purchase.getAccountItemCode(), null))
                .amount(purchase.getAmount() != null ? purchase.getAmount().longValue() : 0L)
                .description(purchase.getRemarks() != null ? purchase.getRemarks() : "BP外注費: " + purchase.getBpCompanyName())
                .build());

        return FreeeDealCreateRequest.builder()
                .companyId(connection.getExternalCompanyId())
                .issueDate(purchase.getIssueDate() != null ? purchase.getIssueDate().toString() : LocalDate.now().toString())
                .dueDate(purchase.getDueDate() != null ? purchase.getDueDate().toString() : null)
                .type("expense")
                .partnerCode(purchase.getBpCompanyCode())
                .refNumber("BP-" + purchase.getBpPaymentId())
                .details(details)
                .build();
    }

    private FreeeDealCreateRequest buildExpenseDealRequest(IntegrationConnection connection, CanonicalExpenseDeal expense) {
        List<FreeeDealDetailDto> details = List.of(FreeeDealDetailDto.builder()
                .taxCode(parseInteger(expense.getTaxCode(), 108))
                .accountItemId(parseInteger(expense.getAccountItemCode(), null))
                .amount(expense.getAmount() != null ? expense.getAmount().longValue() : 0L)
                .description(expense.getDescription() != null ? expense.getDescription() : "経費: " + expense.getCategory())
                .build());

        return FreeeDealCreateRequest.builder()
                .companyId(connection.getExternalCompanyId())
                .issueDate(expense.getExpenseDate() != null ? expense.getExpenseDate().toString() : LocalDate.now().toString())
                .type("expense")
                .partnerCode(expense.getEngineerCode())
                .refNumber(expense.getExpenseNo())
                .details(details)
                .build();
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
    public static class FreeeDealCreateRequest {
        @JsonProperty("company_id")
        private Long companyId;
        @JsonProperty("issue_date")
        private String issueDate;
        @JsonProperty("due_date")
        private String dueDate;
        @JsonProperty("type")
        private String type;
        @JsonProperty("partner_id")
        private Long partnerId;
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FreeeDealDetailDto {
        @JsonProperty("tax_code")
        private Integer taxCode;
        @JsonProperty("account_item_id")
        private Integer accountItemId;
        @JsonProperty("amount")
        private Long amount;
        @JsonProperty("item_id")
        private Long itemId;
        @JsonProperty("section_id")
        private Long sectionId;
        @JsonProperty("description")
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FreeeDealSingleResponse {
        @JsonProperty("deal")
        private FreeeDealDto deal;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FreeeDealsListResponse {
        @JsonProperty("deals")
        private List<FreeeDealDto> deals;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FreeeDealDto {
        @JsonProperty("id")
        private Long id;
        @JsonProperty("company_id")
        private Long companyId;
        @JsonProperty("issue_date")
        private LocalDate issueDate;
        @JsonProperty("due_date")
        private LocalDate dueDate;
        @JsonProperty("amount")
        private BigDecimal amount;
        @JsonProperty("status")
        private String status;
        @JsonProperty("type")
        private String type;
        @JsonProperty("ref_number")
        private String refNumber;
        @JsonProperty("payments")
        private List<FreeePaymentDto> payments;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FreeePaymentDto {
        @JsonProperty("id")
        private Long id;
        @JsonProperty("date")
        private LocalDate date;
        @JsonProperty("amount")
        private BigDecimal amount;
        @JsonProperty("from_walletable_type")
        private String fromWalletableType;
    }
}
