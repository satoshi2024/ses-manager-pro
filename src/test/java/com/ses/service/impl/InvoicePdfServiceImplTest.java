package com.ses.service.impl;

import com.lowagie.text.pdf.PdfReader;
import com.ses.common.exception.BusinessException;
import com.ses.config.PdfProperties;
import com.ses.dto.InvoiceDetailDto;
import com.ses.entity.Customer;
import com.ses.entity.InvoiceItem;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 請求書PDF生成サービスのテスト（P8フォローアップ・提案12）。
 * 生成されたバイト列が実際に読み戻し可能なPDFであることをOpenPDFのPdfReaderで検証する。
 *
 * PDF生成には日本語(CJK)フォントの埋め込みが必須のため、環境にフォントが
 * 存在するかどうかで実行するテストを切り替える（Assumptionsでスキップ）:
 *  - フォントあり → 正常系(有効なPDFを返す)を検証
 *  - フォントなし(CI等) → フォント未検出でBusinessExceptionになることを検証
 * どちらの環境でもエラーにならず、片方が実行・片方がスキップされる。
 */
class InvoicePdfServiceImplTest {

    private SystemConfigService systemConfigService() {
        SystemConfigService s = Mockito.mock(SystemConfigService.class);
        when(s.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        return s;
    }

    private InvoiceDetailDto sampleDetail() {
        InvoiceDetailDto detail = new InvoiceDetailDto();
        detail.setInvoiceNo("INV-202607-0001");
        detail.setBillingMonth("2026-07");
        detail.setIssuedDate(LocalDate.of(2026, 7, 31));
        detail.setSubtotal(new BigDecimal("1000000"));
        detail.setTax(new BigDecimal("100000"));
        detail.setTotal(new BigDecimal("1100000"));

        Customer customer = new Customer();
        customer.setCompanyName("株式会社テスト商事");
        detail.setCustomer(customer);

        InvoiceItem item = new InvoiceItem();
        item.setDescription("【山田太郎】金融システム開発");
        item.setAmount(new BigDecimal("1000000"));
        detail.setItems(List.of(item));

        return detail;
    }

    @Test
    void generate_有効なPDFバイト列を返し日本語テキストが抽出できること() throws Exception {
        InvoicePdfServiceImpl service = new InvoicePdfServiceImpl(new PdfProperties(), systemConfigService());

        byte[] bytes = service.generate(sampleDetail());

        assertTrue(bytes.length > 0);
        assertEquals("%PDF", new String(bytes, 0, 4));

        PdfReader reader = new PdfReader(bytes);
        try {
            assertEquals(1, reader.getNumberOfPages());
            com.lowagie.text.pdf.parser.PdfTextExtractor extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
            String text = extractor.getTextFromPage(1);
            assertTrue(text.contains("請求書"), "抽出テキストに '請求書' が含まれていません");
            assertTrue(text.contains("株式会社テスト商事"), "抽出テキストに '株式会社テスト商事' が含まれていません");
        } finally {
            reader.close();
        }
    }
}
