package com.ses.service.accounting.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.canonical.CanonicalSalesInvoice;
import com.ses.entity.Invoice;
import com.ses.entity.InvoiceItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 売上請求のfreee取引明細（明細ごとの行・消費税の按分）と、請求書の税率に応じた税区分マッピングの選択。
 * 明細の税込金額・消費税額の合計が請求書の合計・消費税額と必ず一致すること（freee側の消費税額が
 * 適格請求書の消費税額と食い違わないこと）を固定する。
 */
class SalesInvoiceDetailAllocationTest {

    @Test
    void 明細ごとに行を作り消費税を按分して合計を請求書と一致させる() {
        // 小計 1,000,001 × 10% = 100,000（切捨て）。按分すると端数が出る
        Invoice invoice = invoice("1000001", "100000", "1100001", "0.10");
        List<CanonicalSalesInvoice.Detail> details = SalesInvoiceIntegrationServiceImpl.buildDetails(invoice,
                List.of(item("要員A 2026年10月", "333334"), item("要員B 2026年10月", "333333"), item("要員C 2026年10月", "333334")),
                "2001", "21");

        assertThat(details).hasSize(3);
        assertThat(details).extracting(CanonicalSalesInvoice.Detail::getDescription)
                .containsExactly("要員A 2026年10月", "要員B 2026年10月", "要員C 2026年10月");
        assertThat(details.stream().map(CanonicalSalesInvoice.Detail::getVat).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100000");
        assertThat(details.stream().map(CanonicalSalesInvoice.Detail::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1100001");
        // 各行は税抜金額 + 按分した消費税
        for (CanonicalSalesInvoice.Detail d : details) {
            assertThat(d.getAccountItemCode()).isEqualTo("2001");
            assertThat(d.getTaxCode()).isEqualTo("21");
            assertThat(d.getVat()).isBetween(new BigDecimal("33333"), new BigDecimal("33334"));
        }
    }

    @Test
    void 按分できないときは従来どおり請求書合計の1明細にする() {
        Invoice invoice = invoice("600000", "60000", "660000", "0.10");
        // 明細1件
        assertSingle(SalesInvoiceIntegrationServiceImpl.buildDetails(invoice, List.of(item("A", "600000")), null, null), "660000");
        // 明細合計が小計と一致しない（請求書側で値引き調整した等）
        assertSingle(SalesInvoiceIntegrationServiceImpl.buildDetails(invoice,
                List.of(item("A", "300000"), item("B", "290000")), null, null), "660000");
        // 0円以下の明細
        assertSingle(SalesInvoiceIntegrationServiceImpl.buildDetails(invoice,
                List.of(item("A", "610000"), item("値引", "-10000")), null, null), "660000");
        // 明細なし
        assertSingle(SalesInvoiceIntegrationServiceImpl.buildDetails(invoice, List.of(), null, null), "660000");
    }

    @Test
    void 長い明細名はfreeeの上限で切る() {
        Invoice invoice = invoice("200", "20", "220", "0.10");
        String longText = "あ".repeat(300);
        List<CanonicalSalesInvoice.Detail> details = SalesInvoiceIntegrationServiceImpl.buildDetails(invoice,
                List.of(item(longText, "100"), item("B", "100")), null, null);
        assertThat(details.get(0).getDescription()).hasSize(SalesInvoiceIntegrationServiceImpl.FREEE_DETAIL_DESCRIPTION_MAX);
    }

    @Test
    void 税率ごとに使う税区分マッピングが変わり未対応の税率は送らない() {
        assertThat(SalesInvoiceIntegrationServiceImpl.taxMapping(invoice("1", "0", "1", "0.10"))).containsExactly("TAX_SALES_10", "TAX_10");
        assertThat(SalesInvoiceIntegrationServiceImpl.taxMapping(invoice("1", "0", "1", "0.08"))).containsExactly("TAX_SALES_8", "TAX_8");
        assertThat(SalesInvoiceIntegrationServiceImpl.taxMapping(invoice("1", "0", "1", "0"))).containsExactly("TAX_SALES_0", "TAX_0");
        // 税率未保存の旧請求書は従来どおり10%
        assertThat(SalesInvoiceIntegrationServiceImpl.taxMapping(invoice("1", "0", "1", null))).containsExactly("TAX_SALES_10", "TAX_10");
        assertThatThrownBy(() -> SalesInvoiceIntegrationServiceImpl.taxMapping(invoice("1", "0", "1", "0.05")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.accounting.taxRateUnsupported");
    }

    private static void assertSingle(List<CanonicalSalesInvoice.Detail> details, String total) {
        assertThat(details).hasSize(1);
        assertThat(details.get(0).getAmount()).isEqualByComparingTo(total);
        assertThat(details.get(0).getVat()).isNull();
    }

    private static Invoice invoice(String subtotal, String tax, String total, String rate) {
        Invoice invoice = new Invoice();
        invoice.setId(1L);
        invoice.setInvoiceNo("INV-2026-10-001");
        invoice.setSubtotal(new BigDecimal(subtotal));
        invoice.setTax(new BigDecimal(tax));
        invoice.setTotal(new BigDecimal(total));
        invoice.setTaxRate(rate == null ? null : new BigDecimal(rate));
        return invoice;
    }

    private static InvoiceItem item(String description, String amount) {
        InvoiceItem item = new InvoiceItem();
        item.setDescription(description);
        item.setAmount(new BigDecimal(amount));
        return item;
    }
}
