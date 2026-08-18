package com.ses.service.accounting;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.dto.accounting.canonical.*;
import com.ses.entity.IntegrationConnection;
import com.ses.service.accounting.provider.CsvAccountingExportProvider;
import com.ses.service.accounting.provider.FreeeAccountingProvider;
import com.ses.service.integration.IntegrationConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@SpringBootTest
@ActiveProfiles("test")
class FreeeAccountingProviderTest {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationConnectionService connectionService;

    @Autowired
    private FreeeAccountingProvider freeeProvider;

    @Autowired
    private CsvAccountingExportProvider csvProvider;

    private MockRestServiceServer mockServer;
    private IntegrationConnection testConnection;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();

        testConnection = connectionService.getOrCreateConnection("test-tenant", 1L, "freee", "accounting");
        IntegrationTokensDto tokens = IntegrationTokensDto.builder()
                .accessToken("secret-token-abcdef123456")
                .refreshToken("secret-refresh-987654321")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
        connectionService.saveTokens(testConnection.getId(), tokens, 99999L, "テスト事業所", 1L);
    }

    @Test
    @DisplayName("200 OK: 売上取引送信成功と金額照合")
    void upsertSalesInvoice_success200() {
        CanonicalSalesInvoice invoice = CanonicalSalesInvoice.builder()
                .invoiceId(101L)
                .invoiceNo("INV-202608-001")
                .customerCode("CUST-001")
                .customerName("テスト顧客株式会社")
                .issueDate(LocalDate.of(2026, 8, 1))
                .dueDate(LocalDate.of(2026, 8, 31))
                .subtotal(new BigDecimal("1000000"))
                .tax(new BigDecimal("100000"))
                .total(new BigDecimal("1100000"))
                .build();

        String responseJson = "{\"deal\": {\"id\": 55555, \"company_id\": 99999, \"amount\": 1100000, \"status\": \"unsettled\"}}";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Freee-Request-ID", "req-freee-uuid-001");

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-token-abcdef123456"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON).headers(headers));

        CanonicalDealResult result = freeeProvider.upsertSalesInvoice(testConnection, invoice);

        mockServer.verify();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExternalId()).isEqualTo("55555");
        assertThat(result.getProviderRequestId()).isEqualTo("req-freee-uuid-001");
        assertThat(result.getResponseTotal()).isEqualByComparingTo("1100000");
    }

    @Test
    @DisplayName("200 OK 金額不一致: 送信金額とfreee登録金額が不一致の場合はSUCCEEDEDにしない (design §4)")
    void upsertSalesInvoice_amountMismatch() {
        CanonicalSalesInvoice invoice = CanonicalSalesInvoice.builder()
                .invoiceId(102L)
                .invoiceNo("INV-202608-002")
                .customerCode("CUST-002")
                .total(new BigDecimal("1100000"))
                .build();

        // freee側の金額が 1,000,000 円で返ってきた場合（10万円の乖離）
        String responseJson = "{\"deal\": {\"id\": 55556, \"company_id\": 99999, \"amount\": 1000000, \"status\": \"unsettled\"}}";

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        CanonicalDealResult result = freeeProvider.upsertSalesInvoice(testConnection, invoice);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getErrorMessageSafe()).contains("不一致");
    }

    @Test
    @DisplayName("400 / 422 Validation Error: リトライ不可として分類されること (design §6.3)")
    void validationError_notRetryable() {
        CanonicalSalesInvoice invoice = CanonicalSalesInvoice.builder()
                .invoiceId(103L)
                .invoiceNo("INV-202608-003")
                .total(new BigDecimal("500000"))
                .build();

        String errorJson = "{\"errors\": [{\"type\": \"validation\", \"messages\": [\"勘定科目IDが存在しません\"]}]}";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Freee-Request-ID", "req-err-422");

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(errorJson)
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(headers));

        CanonicalDealResult result = freeeProvider.upsertSalesInvoice(testConnection, invoice);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getProviderRequestId()).isEqualTo("req-err-422");
    }

    @Test
    @DisplayName("401 Unauthorized: リフレッシュ失敗時に UNAUTHORIZED / retryable=false として分類されること")
    void unauthorized401_retryable() {
        CanonicalSalesInvoice invoice = CanonicalSalesInvoice.builder()
                .invoiceId(104L)
                .invoiceNo("INV-202608-004")
                .total(new BigDecimal("300000"))
                .build();

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"message\": \"OAuth token is expired\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://accounts.secure.freee.co.jp/public_api/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"error\": \"invalid_grant\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        CanonicalDealResult result = freeeProvider.upsertSalesInvoice(testConnection, invoice);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("UNAUTHORIZED");
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("403 Forbidden / Plan制限: リトライ不可(CSVフォールバック案内)として分類されること")
    void planLimitation403_notRetryable() {
        CanonicalSalesInvoice invoice = CanonicalSalesInvoice.builder()
                .invoiceId(105L)
                .invoiceNo("INV-202608-005")
                .total(new BigDecimal("200000"))
                .build();

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"message\": \"Deal API requires Professional plan\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        CanonicalDealResult result = freeeProvider.upsertSalesInvoice(testConnection, invoice);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("PLAN_LIMITATION");
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getErrorMessageSafe()).contains("CSV出力");
    }

    @Test
    @DisplayName("429 Too Many Requests: Retry-After秒数を取得しリトライ可能として分類されること")
    void rateLimited429_retryableWithBackoff() {
        CanonicalPurchaseDeal purchase = CanonicalPurchaseDeal.builder()
                .bpPaymentId(201L)
                .amount(new BigDecimal("800000"))
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "45");
        headers.set("X-Freee-Request-ID", "req-rate-limit-429");

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .headers(headers)
                        .body("{\"message\": \"Too Many Requests\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        CanonicalDealResult result = freeeProvider.upsertPurchaseDeal(testConnection, purchase);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("RATE_LIMITED");
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getRetryAfterSeconds()).isEqualTo(45);
        assertThat(result.getProviderRequestId()).isEqualTo("req-rate-limit-429");
    }

    @Test
    @DisplayName("500 Server Error: リトライ可能として分類されること")
    void serverError500_retryable() {
        CanonicalExpenseDeal expense = CanonicalExpenseDeal.builder()
                .expenseRequestId(301L)
                .expenseNo("EX-301")
                .amount(new BigDecimal("15000"))
                .build();

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andRespond(withServerError()
                        .body("{\"message\": \"Internal Server Error\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        CanonicalDealResult result = freeeProvider.upsertExpenseDeal(testConnection, expense);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("SERVER_ERROR");
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("秘密情報の非ログ出力検証 (Secret Log Capture Test)")
    void secretLogCapture_tokenNeverLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger(FreeeAccountingProvider.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        CanonicalSalesInvoice invoice = CanonicalSalesInvoice.builder()
                .invoiceId(999L)
                .invoiceNo("INV-SECRET-TEST")
                .customerCode("CUST-999")
                .total(new BigDecimal("50000"))
                .build();

        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andRespond(withSuccess("{\"deal\": {\"id\": 99999, \"amount\": 50000}}", MediaType.APPLICATION_JSON));

        freeeProvider.upsertSalesInvoice(testConnection, invoice);

        // ログイベントを検査
        for (ILoggingEvent event : listAppender.list) {
            String msg = event.getFormattedMessage();
            assertThat(msg).doesNotContain("secret-token-abcdef123456");
            assertThat(msg).doesNotContain("secret-refresh-987654321");
            assertThat(msg).doesNotContain("Bearer");
        }

        logger.detachAppender(listAppender);
    }

    @Test
    @DisplayName("CSVフォールバック: 同一Canonical DTOから正常に出力され、数式注入対策・正常負数が正しく処理されること")
    void csvExport_identicalDtoAndFormulaInjection() {
        CanonicalSalesInvoice inv1 = CanonicalSalesInvoice.builder()
                .invoiceId(501L)
                .invoiceNo("INV-202608-501")
                .customerCode("CUST-001")
                .customerName("通常企業")
                .issueDate(LocalDate.of(2026, 8, 1))
                .dueDate(LocalDate.of(2026, 8, 31))
                .total(new BigDecimal("1500000"))
                .remarks("通常請求")
                .build();

        // 正常な負数 (値引き・マイナス調整)
        CanonicalSalesInvoice inv2 = CanonicalSalesInvoice.builder()
                .invoiceId(502L)
                .invoiceNo("INV-202608-502")
                .customerCode("CUST-002")
                .customerName("値引き顧客")
                .issueDate(LocalDate.of(2026, 8, 1))
                .dueDate(LocalDate.of(2026, 8, 31))
                .total(new BigDecimal("-50000"))
                .remarks("相殺調整")
                .build();

        // 数式インジェクション攻撃 (先頭 =, +, -, @)
        CanonicalSalesInvoice inv3 = CanonicalSalesInvoice.builder()
                .invoiceId(503L)
                .invoiceNo("=CMD|' /C calc'!A0")
                .customerCode("@CUST-EVIL")
                .customerName("+悪意のある社名")
                .issueDate(LocalDate.of(2026, 8, 1))
                .dueDate(LocalDate.of(2026, 8, 31))
                .total(new BigDecimal("100000"))
                .remarks("-DANGEROUS")
                .build();

        String csv = csvProvider.exportSalesInvoicesCsv(List.of(inv1, inv2, inv3));
        assertThat(csv).contains("INV-202608-501,2026-08-01,2026-08-31,CUST-001,通常企業,売上高,課税売上10%,1500000,通常請求");
        // 正常負数は文字列化やエスケープされずそのまま数値として出力
        assertThat(csv).contains("-50000");
        // 攻撃文字列はタブエスケープされていること
        assertThat(csv).contains("\t=CMD|' /C calc'!A0");
        assertThat(csv).contains("\t@CUST-EVIL");
        assertThat(csv).contains("\t+悪意のある社名");
        assertThat(csv).contains("\t-DANGEROUS");
    }

    @Test
    @DisplayName("401 Unauthorized: トークン強制リフレッシュ後に1回だけ自動リプレイされ成功すること")
    void unauthorized401_refreshAndReplaySuccess() {
        CanonicalSalesInvoice invoice = CanonicalSalesInvoice.builder()
                .invoiceId(777L)
                .invoiceNo("INV-401-TEST")
                .total(new BigDecimal("200000"))
                .build();

        // 1回目のリクエストは 401 を返す
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\": \"Invalid token\"}").contentType(MediaType.APPLICATION_JSON));

        // トークンリフレッシュリクエスト
        mockServer.expect(requestTo("https://accounts.secure.freee.co.jp/public_api/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\": \"new-access-token-999\", \"refresh_token\": \"new-refresh-999\", \"expires_in\": 3600}", MediaType.APPLICATION_JSON));

        // 2回目のリプレイリクエストは新トークンで 200 OK
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/deals"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer new-access-token-999"))
                .andRespond(withSuccess("{\"deal\": {\"id\": 88888, \"amount\": 200000}}", MediaType.APPLICATION_JSON));

        CanonicalDealResult result = freeeProvider.upsertSalesInvoice(testConnection, invoice);

        mockServer.verify();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExternalId()).isEqualTo("88888");
    }

    @Test
    @DisplayName("PII / 機密情報サニタイズ: 400/422 エラー本文のメール、Bearerトークン、電話番号が除外されること")
    void sanitizeErrorResponse_redactsSensitiveData() {
        String rawBody = "{\"errors\": [{\"messages\": [\"Bearer secret_token_xyz123 は無効です。担当者 user@example.com (03-1234-5678) へご連絡ください\"]}]}";
        String sanitized = freeeProvider.sanitizeErrorResponse(rawBody);

        assertThat(sanitized).doesNotContain("secret_token_xyz123");
        assertThat(sanitized).doesNotContain("user@example.com");
        assertThat(sanitized).doesNotContain("03-1234-5678");
        assertThat(sanitized).contains("[REDACTED]");
    }

    @Test
    @DisplayName("verifyMaster: 外部マスタの存在確認 (200 OK -> true, 404 -> false)")
    void verifyMaster_masterExistenceCheck() {
        // 存在する取引先 (200 OK)
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/partners/101?company_id=99999"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"partner\": {\"id\": 101, \"name\": \"取引先A\"}}", MediaType.APPLICATION_JSON));

        // 存在しない取引先 (404 Not Found)
        mockServer.expect(requestTo("https://api.freee.co.jp/api/1/partners/999?company_id=99999"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        boolean exists = freeeProvider.verifyMaster(testConnection, "CUSTOMER_PARTNER", "101", "CUST-001");
        assertThat(exists).isTrue();

        boolean notExists = freeeProvider.verifyMaster(testConnection, "CUSTOMER_PARTNER", "999", "CUST-999");
        assertThat(notExists).isFalse();

        mockServer.verify();
    }
}
