package com.ses.service.impl;

import com.lowagie.text.pdf.PdfReader;
import com.ses.config.PdfProperties;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Quotation;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 見積書PDFのテスト。CJKフォントの有無で正常系/スキップを切り替える（請求書PDFテストと同方針）。
 */
class QuotationPdfServiceImplTest {

    private static final List<String> FONT_CANDIDATE_PATHS = List.of(
            "/usr/share/fonts/opentype/ipafont-gothic/ipag.ttf",
            "/usr/share/fonts/truetype/fonts-japanese-gothic.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansJP-Regular.ttf"
    );

    private static boolean cjkFontAvailable() {
        return FONT_CANDIDATE_PATHS.stream().anyMatch(p -> new java.io.File(p).exists());
    }

    private QuotationPdfServiceImpl service() {
        SystemConfigService cfg = Mockito.mock(SystemConfigService.class);
        when(cfg.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));

        QuotationMapper qm = Mockito.mock(QuotationMapper.class);
        Quotation q = new Quotation();
        q.setId(1L);
        q.setQuotationNo("Q-202607-0001");
        q.setTitle("金融システム開発");
        q.setUnitPrice(new BigDecimal("700000"));
        q.setCustomerId(1L);
        q.setEngineerId(2L);
        when(qm.selectById(1L)).thenReturn(q);

        CustomerMapper cm = Mockito.mock(CustomerMapper.class);
        Customer c = new Customer();
        c.setCompanyName("株式会社テスト商事");
        when(cm.selectById(1L)).thenReturn(c);

        EngineerMapper em = Mockito.mock(EngineerMapper.class);
        Engineer e = new Engineer();
        e.setFullName("山田太郎");
        e.setInitialName("Y.T");
        when(em.selectById(2L)).thenReturn(e);

        return new QuotationPdfServiceImpl(new PdfProperties(), cfg, qm, cm, em);
    }

    @Test
    void generate_有効なPDFバイト列を返す() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(cjkFontAvailable(),
                "この環境にはCJKフォントが無いため正常系PDF生成テストをスキップ");

        byte[] bytes = service().generate(1L);
        assertTrue(bytes.length > 0);
        assertEquals("%PDF", new String(bytes, 0, 4));
        PdfReader reader = new PdfReader(bytes);
        try {
            assertEquals(1, reader.getNumberOfPages());
        } finally {
            reader.close();
        }
    }
}
