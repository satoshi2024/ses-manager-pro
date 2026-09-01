package com.ses.service.invoice;

import com.ses.dto.invoice.CanonicalInvoice;
import com.ses.dto.invoice.CanonicalInvoice.CanonicalInvoiceItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JP PINT XML レンダラの税率・金額出力を検証する。
 */
class JpPintRendererTest {

    private final JpPintRenderer renderer = new JpPintRenderer();

    @Test
    void render_文書レベルPercentは明細税率を使い8パーセント時に10へ固定しない() {
        CanonicalInvoice invoice = CanonicalInvoice.builder()
                .invoiceNumber("INV-8PCT")
                .issuedDate(LocalDate.of(2026, 8, 20))
                .dueDate(LocalDate.of(2026, 9, 20))
                .taxExclusiveAmount(new BigDecimal("1000"))
                .taxAmount(new BigDecimal("80"))
                .taxInclusiveAmount(new BigDecimal("1080"))
                .items(List.of(
                        CanonicalInvoiceItem.builder()
                                .description("軽減税率明細")
                                .lineAmount(new BigDecimal("1000"))
                                .unitPrice(new BigDecimal("1000"))
                                .quantity(BigDecimal.ONE)
                                .taxCategory("S")
                                .taxRate(new BigDecimal("8"))
                                .build()
                ))
                .build();

        String xml = renderer.render(invoice, "1.1.3");

        // TaxTotal / TaxCategory 直下の文書レベル Percent が先頭に出る
        Matcher docPercent = Pattern.compile(
                "<cac:TaxTotal>.*?<cac:TaxCategory>.*?<cbc:Percent>([^<]+)</cbc:Percent>",
                Pattern.DOTALL).matcher(xml);
        assertTrue(docPercent.find(), "文書レベルの Percent が存在すること");
        assertEquals("8", docPercent.group(1), "8%請求で文書レベル Percent が10に固定されないこと");
        assertTrue(xml.contains("<cbc:TaxAmount>80</cbc:TaxAmount>"),
                "税額は既格納値を再計算せず出力すること");
        assertTrue(xml.contains("<cbc:TaxInclusiveAmount>1080</cbc:TaxInclusiveAmount>"));
    }

    @Test
    void render_明細税率が無い場合は文書レベルPercentは10() {
        CanonicalInvoice invoice = CanonicalInvoice.builder()
                .invoiceNumber("INV-DEFAULT")
                .issuedDate(LocalDate.of(2026, 8, 20))
                .taxExclusiveAmount(new BigDecimal("1000"))
                .taxAmount(new BigDecimal("100"))
                .taxInclusiveAmount(new BigDecimal("1100"))
                .items(List.of(
                        CanonicalInvoiceItem.builder()
                                .description("税率未設定")
                                .lineAmount(new BigDecimal("1000"))
                                .build()
                ))
                .build();

        String xml = renderer.render(invoice, "1.1.3");
        Matcher docPercent = Pattern.compile(
                "<cac:TaxTotal>.*?<cac:TaxCategory>.*?<cbc:Percent>([^<]+)</cbc:Percent>",
                Pattern.DOTALL).matcher(xml);
        assertTrue(docPercent.find());
        assertEquals("10", docPercent.group(1));
    }

    @Test
    void render_例外時にinvoiceNoの機密がログ各要素に出力されず安全な診断情報が記録される() {
        ch.qos.logback.classic.Logger rendererLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JpPintRenderer.class);
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        rendererLogger.addAppender(appender);
        rootLogger.addAppender(appender);

        String secretInvoiceNo = "db password=renderer-secret Authorization: Bearer renderer-token customer@example.com\u0000\u0001\u0008";
        List<String> secrets = List.of("renderer-secret", "renderer-token", "customer@example.com");

        try {
            com.ses.common.util.CorrelationContext.set("corr-renderer-test-123");

            CanonicalInvoice invoice = CanonicalInvoice.builder()
                    .invoiceId(5501L)
                    .invoiceNumber(secretInvoiceNo)
                    .issuedDate(LocalDate.now())
                    .dueDate(LocalDate.now().plusMonths(1))
                    .currency("JPY")
                    .taxExclusiveAmount(new BigDecimal("1000"))
                    .taxAmount(new BigDecimal("100"))
                    .taxInclusiveAmount(new BigDecimal("1100"))
                    .supplier(CanonicalInvoice.SupplierInfo.builder()
                            .corporateNumber("T1234567890123")
                            .name("SES Inc.")
                            .build())
                    .customer(CanonicalInvoice.CustomerInfo.builder()
                            .peppolParticipantId("0192:123456789")
                            .name("Buyer Co")
                            .build())
                    .build();

            assertThrows(com.ses.common.exception.BusinessException.class, () -> renderer.render(invoice, "1.1.3"));

            assertFalse(appender.list.isEmpty(), "エラーログが捕捉されていること");

            for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
                // 1. formattedMessage に secret が含まれないこと
                String formatted = event.getFormattedMessage();
                for (String secret : secrets) {
                    assertFalse(formatted.contains(secret),
                            "formattedMessageに機密が含まれていないこと: " + secret + " in " + formatted);
                }

                // 2. argumentArray に secret が含まれないこと
                Object[] args = event.getArgumentArray();
                if (args != null) {
                    for (Object arg : args) {
                        if (arg != null) {
                            String argStr = arg.toString();
                            for (String secret : secrets) {
                                assertFalse(argStr.contains(secret),
                                        "argumentArrayに機密が含まれていないこと: " + secret + " in " + argStr);
                            }
                        }
                    }
                }

                // 3. IThrowableProxy に secret が含まれないこと
                ch.qos.logback.classic.spi.IThrowableProxy proxy = event.getThrowableProxy();
                assertThrowableProxyNoSecrets(proxy, secrets);
            }

            // 安全な診断情報（correlationId, internal invoiceId, fixed errorCode, exceptionClass）が記録されていること
            boolean hasDiagnostics = appender.list.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("invoiceId=5501")
                            && e.getFormattedMessage().contains("correlationId=corr-renderer-test-123")
                            && e.getFormattedMessage().contains("errorCode=XML_RENDER_FAILED")
                            && e.getFormattedMessage().contains("exceptionClass="));
            assertTrue(hasDiagnostics, "安全な診断識別子が記録されていること");

        } finally {
            com.ses.common.util.CorrelationContext.clear();
            rendererLogger.detachAppender(appender);
            rootLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private void assertThrowableProxyNoSecrets(ch.qos.logback.classic.spi.IThrowableProxy proxy, List<String> secrets) {
        if (proxy == null) {
            return;
        }
        String msg = proxy.getMessage();
        if (msg != null) {
            for (String secret : secrets) {
                assertFalse(msg.contains(secret), "IThrowableProxy messageに機密が含まれていないこと: " + secret);
            }
        }
        assertThrowableProxyNoSecrets(proxy.getCause(), secrets);
        if (proxy.getSuppressed() != null) {
            for (ch.qos.logback.classic.spi.IThrowableProxy supp : proxy.getSuppressed()) {
                assertThrowableProxyNoSecrets(supp, secrets);
            }
        }
    }
}
