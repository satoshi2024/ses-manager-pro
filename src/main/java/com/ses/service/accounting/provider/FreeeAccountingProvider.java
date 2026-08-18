package com.ses.service.accounting.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
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

    @Value("${freee.oauth.client-id:dummy-client-id}")
    private String clientId;

    @Value("${freee.oauth.client-secret:dummy-client-secret}")
    private String clientSecret;

    @Value("${freee.oauth.token-url:https://accounts.secure.freee.co.jp/public_api/token}")
    private String tokenUrl;

    @Override
    public String providerName() {
        return "freee";
    }

    @Override
    public CanonicalDealResult upsertSalesInvoice(IntegrationConnection connection, CanonicalSalesInvoice invoice) {
        log.info("Upserting sales invoice to freee: invoiceNo={}, customerCode={}, total={}",
                invoice.getInvoiceNo(), invoice.getCustomerCode(), invoice.getTotal());

        FreeeDealCreateRequest request = buildSalesDealRequest(connection, invoice);
        return executeDealCreationWithRecovery(connection, "/api/1/deals", request, invoice.getTotal(), invoice.getInvoiceNo());
    }

    @Override
    public CanonicalDealResult cancelSalesInvoice(IntegrationConnection connection, String externalDealId, String reason) {
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
        log.info("Upserting purchase deal to freee: bpPaymentId={}, bpCompanyCode={}, amount={}",
                purchase.getBpPaymentId(), purchase.getBpCompanyCode(), purchase.getAmount());

        FreeeDealCreateRequest request = buildPurchaseDealRequest(connection, purchase);
        String refNumber = "BP-" + purchase.getBpPaymentId();
        return executeDealCreationWithRecovery(connection, "/api/1/deals", request, purchase.getAmount(), refNumber);
    }

    @Override
    public CanonicalDealResult upsertExpenseDeal(IntegrationConnection connection, CanonicalExpenseDeal expense) {
        log.info("Upserting expense deal to freee: expenseNo={}, engineerCode={}, amount={}",
                expense.getExpenseNo(), expense.getEngineerCode(), expense.getAmount());

        FreeeDealCreateRequest request = buildExpenseDealRequest(connection, expense);
        return executeDealCreationWithRecovery(connection, "/api/1/deals", request, expense.getAmount(), expense.getExpenseNo());
    }

    @Override
    public List<CanonicalPaymentSync> fetchPayments(IntegrationConnection connection, LocalDate fromDate, LocalDate toDate) {
        log.info("Fetching payments from freee: companyId={}, fromDate={}, toDate={}",
                connection.getExternalCompanyId(), fromDate, toDate);

        String url = apiBaseUrl + "/api/1/deals?company_id=" + connection.getExternalCompanyId()
                + "&start_issue_date=" + fromDate
                + "&end_issue_date=" + toDate
                + "&status=settled&limit=100";

        try {
            ResponseEntity<String> response = executeWith401Recovery(connection, headers -> {
                HttpEntity<?> entity = new HttpEntity<>(headers);
                return restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            });

            if (response.getBody() == null || response.getBody().isBlank()) {
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode deals = root.get("deals");
            if (deals == null || !deals.isArray()) {
                return Collections.emptyList();
            }

            List<CanonicalPaymentSync> result = new ArrayList<>();
            for (JsonNode d : deals) {
                String dealId = d.has("id") ? String.valueOf(d.get("id").asLong()) : null;
                BigDecimal amount = d.has("amount") ? BigDecimal.valueOf(d.get("amount").asLong()) : BigDecimal.ZERO;
                String refNumber = d.has("ref_number") && !d.get("ref_number").isNull() ? d.get("ref_number").asText() : null;
                String issueDateStr = d.has("issue_date") ? d.get("issue_date").asText() : null;
                LocalDate paymentDate = issueDateStr != null ? LocalDate.parse(issueDateStr) : null;
                boolean settled = d.has("status") && "settled".equalsIgnoreCase(d.get("status").asText());

                result.add(CanonicalPaymentSync.builder()
                        .dealId(dealId)
                        .amount(amount)
                        .paymentDate(paymentDate)
                        .settled(settled)
                        .refNumber(refNumber)
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch payments from freee: {}", e.getMessage());
            throw new RuntimeException("freee決済実績取得エラー: " + e.getMessage(), e);
        }
    }

    @Override
    public CanonicalPaymentSync fetchDealPayment(IntegrationConnection connection, String externalDealId) {
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
                if (firstP.has("date") && !firstP.get("date").isNull()) {
                    paymentDate = LocalDate.parse(firstP.get("date").asText());
                }
            }

            if (paymentDate == null && deal.has("issue_date") && !deal.get("issue_date").isNull()) {
                paymentDate = LocalDate.parse(deal.get("issue_date").asText());
            }

            return CanonicalPaymentSync.builder()
                    .dealId(dealId)
                    .amount(amount)
                    .paymentDate(paymentDate != null ? paymentDate : LocalDate.now())
                    .settled(settled)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to fetch deal payment for dealId={}: {}", externalDealId, e.getMessage(), e);
            return null;
        }
    }
    @Override
    public boolean validateConnection(IntegrationConnection connection) {
        try {
            String url = apiBaseUrl + "/api/1/users/me";
            ResponseEntity<String> response = executeWith401Recovery(connection, headers -> {
                HttpEntity<?> entity = new HttpEntity<>(headers);
                return restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            });
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("freee connection validation failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean verifyMaster(IntegrationConnection connection, String objectType, String externalId, String externalCode) {
        if (externalId == null || externalId.isBlank() || objectType == null) return false;
        Long companyId = connection.getExternalCompanyId();
        if (companyId == null) companyId = 1L;

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
            case "TAX_SALES_10", "TAX_PURCHASE_10", "TAX_EXPENSE_10" -> {
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
                case "TAX_SALES_10", "TAX_PURCHASE_10", "TAX_EXPENSE_10" -> {
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
            log.warn("freee master verification error: objectType={}, externalId={}, msg={}", objectType, externalId, e.getMessage());
            return false;
        }
    }

    // === 401 Recovery & Unknown-Outcome Pipeline ===

    private CanonicalDealResult executeDealCreationWithRecovery(IntegrationConnection connection,
                                                                 String path,
                                                                 FreeeDealCreateRequest request,
                                                                 BigDecimal expectedTotal,
                                                                 String refNumber) {
        String url = apiBaseUrl + path;
        try {
            ResponseEntity<FreeeDealCreateResponse> response = executeWith401Recovery(connection, headers -> {
                HttpEntity<FreeeDealCreateRequest> entity = new HttpEntity<>(request, headers);
                return restTemplate.exchange(url, HttpMethod.POST, entity, FreeeDealCreateResponse.class);
            });

            String reqId = extractRequestId(response.getHeaders());
            FreeeDealCreateResponse body = response.getBody();
            if (body != null && body.getDeal() != null) {
                Long dealId = body.getDeal().getId();
                Long actualAmount = body.getDeal().getAmount();

                // 金額整合性チェック
                if (expectedTotal != null && actualAmount != null
                        && expectedTotal.compareTo(BigDecimal.valueOf(actualAmount)) != 0) {
                    log.error("freee response amount mismatch: expected={}, actual={}", expectedTotal, actualAmount);
                    return CanonicalDealResult.builder()
                            .success(false)
                            .externalId(String.valueOf(dealId))
                            .providerRequestId(reqId)
                            .errorCode("AMOUNT_MISMATCH")
                            .errorMessageSafe("freee 応答金額不一致 (期待: " + expectedTotal + "円, freee: " + actualAmount + "円)")
                            .retryable(false)
                            .build();
                }

                return CanonicalDealResult.builder()
                        .success(true)
                        .externalId(String.valueOf(dealId))
                        .providerRequestId(reqId)
                        .responseTotal(actualAmount != null ? BigDecimal.valueOf(actualAmount) : expectedTotal)
                        .build();
            }

            return CanonicalDealResult.builder()
                    .success(false)
                    .providerRequestId(reqId)
                    .errorCode("EMPTY_RESPONSE")
                    .errorMessageSafe("freee からの応答が空です")
                    .retryable(false)
                    .build();

        } catch (Exception e) {
            // タイムアウトまたはネットワーク切断時の未知結果照合 (P1-03)
            if ((e instanceof ResourceAccessException || e.getCause() instanceof java.net.SocketTimeoutException)
                    && refNumber != null && !refNumber.isBlank()) {
                log.warn("Timeout on deal creation for refNumber={}, querying freee to check if deal was created", refNumber);
                CanonicalDealResult verified = verifyDealCreatedByRefNumber(connection, refNumber, expectedTotal);
                if (verified != null) {
                    return verified;
                }
            }
            return handleApiException(e, "POST " + path);
        }
    }

    private CanonicalDealResult verifyDealCreatedByRefNumber(IntegrationConnection connection,
                                                             String refNumber,
                                                             BigDecimal expectedTotal) {
        int limit = 100;
        int maxPages = 50;
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
                                Long dealId = d.get("id").asLong();
                                Long amount = d.has("amount") ? d.get("amount").asLong() : null;
                                log.info("Found existing deal for refNumber={} in freee after timeout (page={}): dealId={}", refNumber, page, dealId);
                                return CanonicalDealResult.builder()
                                        .success(true)
                                        .externalId(String.valueOf(dealId))
                                        .responseTotal(amount != null ? BigDecimal.valueOf(amount) : expectedTotal)
                                        .errorMessageSafe("タイムアウト後に外部照合により取引作成を確認 (dealId=" + dealId + ")")
                                        .build();
                            }
                        }
                        if (deals.size() < limit) {
                            break;
                        }
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            } catch (Exception ex) {
                log.warn("Failed to check existing deal by refNumber after timeout on page {}: {}", page, ex.getMessage());
                break;
            }
        }
        return null;
    }

    private <T> ResponseEntity<T> executeWith401Recovery(IntegrationConnection connection,
                                                         RestExecution<T> execution) {
        HttpHeaders headers = buildAuthHeaders(connection);
        try {
            return execution.execute(headers);
        } catch (HttpClientErrorException httpEx) {
            if (httpEx.getStatusCode().value() == 401) {
                log.warn("Received 401 from freee API, attempting forced token refresh and single replay");
                try {
                    // トークン強制リフレッシュ (P1-03)
                    IntegrationTokensDto refreshed = connectionService.forceRefreshToken(
                            connection.getId(),
                            this::refreshFreeeTokens);
                    if (refreshed != null && refreshed.getAccessToken() != null) {
                        HttpHeaders newHeaders = new HttpHeaders();
                        newHeaders.setContentType(MediaType.APPLICATION_JSON);
                        newHeaders.setBearerAuth(refreshed.getAccessToken());
                        // 最大1回のリプレイ実行
                        return execution.execute(newHeaders);
                    }
                } catch (Exception refreshEx) {
                    log.error("Token refresh or replay failed on 401: {}", refreshEx.getMessage());
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
        ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, Map.class);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Map<?, ?> body = response.getBody();
            String newAccessToken = (String) body.get("access_token");
            String newRefreshToken = (String) body.get("refresh_token");
            Number expiresIn = (Number) body.get("expires_in");

            return IntegrationTokensDto.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(newRefreshToken != null ? newRefreshToken : currentTokens.getRefreshToken())
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
                        .errorMessageSafe("認証エラー: トークンが無効または失効しています (再認証が必要です)")
                        .retryable(false)
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
        private Integer accountItemId;

        @JsonProperty("amount")
        private Long amount;

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
