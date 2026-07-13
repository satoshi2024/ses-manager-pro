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

    /** InvoicePdfServiceImpl.DEFAULT_FONT_CANDIDATES と同じ既定候補（,以降のインデックスは除去）。 */
    private static final List<String> FONT_CANDIDATE_PATHS = List.of(
            "/usr/share/fonts/opentype/ipafont-gothic/ipag.ttf",
            "/usr/share/fonts/truetype/fonts-japanese-gothic.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansJP-Regular.ttf"
    );

    private static boolean cjkFontAvailable() {
        return FONT_CANDIDATE_PATHS.stream().anyMatch(p -> new java.io.File(p).exists());
    }

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
    void generate_有効なPDFバイト列を返す() throws Exception {
        // CJKフォントが無い環境（CI等）ではPDF生成不可のためスキップする
        org.junit.jupiter.api.Assumptions.assumeTrue(cjkFontAvailable(),
                "この環境にはCJKフォントが無いため正常系PDF生成テストをスキップ");

        InvoicePdfServiceImpl service = new InvoicePdfServiceImpl(new PdfProperties(), systemConfigService());

        byte[] bytes = service.generate(sampleDetail());

        assertTrue(bytes.length > 0);
        assertEquals("%PDF", new String(bytes, 0, 4));

        PdfReader reader = new PdfReader(bytes);
        try {
            assertEquals(1, reader.getNumberOfPages());
        } finally {
            reader.close();
        }
    }

    @Test
    void generate_フォントが1件も見つからない場合はBusinessException() {
        PdfProperties props = new PdfProperties();
        props.setFontPath("/path/does/not/exist.ttf");
        InvoicePdfServiceImpl service = new InvoicePdfServiceImpl(props, systemConfigService()) {
            // DEFAULT_FONT_CANDIDATES はstatic finalで差し替えられないため、
            // 実環境にフォントが無い体で検証したい場合は別途モック戦略が必要。
            // ここでは「指定パスが存在しない」ケースのみを直接検証する。
        };

        // 実行環境に既定候補フォントが存在する場合はこのテストは成立しないため事前にスキップする。
        org.junit.jupiter.api.Assumptions.assumeTrue(!cjkFontAvailable(),
                "この環境には既定のCJKフォントが存在するためスキップ");

        assertThrows(BusinessException.class, () -> service.generate(sampleDetail()));
    }
}
