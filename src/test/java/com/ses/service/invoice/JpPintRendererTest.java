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
}
