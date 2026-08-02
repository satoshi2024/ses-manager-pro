package com.ses.service.impl;

import com.lowagie.text.pdf.PdfReader;
import com.ses.config.PdfProperties;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Quotation;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 見積書PDFのテスト。
 *
 * <p>以前はOS側のCJKフォント（{@code /usr/share/fonts/...}）の有無でスキップしていたが、
 * {@link com.ses.common.util.PdfFontUtils} は同梱フォント {@code resources/fonts/ipaexg.ttf} を
 * 最優先で読み込むため、OSのフォント構成に関係なくPDFを生成できる。判定はLinuxのパス限定だったので
 * Windowsでは常時スキップ、CI(ubuntu)でもフォント未導入のためスキップされ、事実上どこでも
 * 実行されない「見せかけの緑」になっていた。請求書PDFテストと同様に無条件で検証する。
 */
class QuotationPdfServiceImplTest {

    private QuotationPdfServiceImpl service() {
        SystemConfigService cfg = Mockito.mock(SystemConfigService.class);
        when(cfg.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));

        CustomerMapper cm = Mockito.mock(CustomerMapper.class);
        Customer c = new Customer();
        c.setCompanyName("株式会社テスト商事");
        when(cm.selectById(1L)).thenReturn(c);
        EngineerMapper em = Mockito.mock(EngineerMapper.class);
        Engineer e = new Engineer();
        e.setFullName("山田太郎");
        e.setInitialName("Y.T");
        when(em.selectById(2L)).thenReturn(e);

        PdfProperties pdfProps = new PdfProperties();
        org.springframework.beans.factory.ObjectProvider<com.ses.service.DocumentService> provider = Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        return new QuotationPdfServiceImpl(cfg, cm, em, new com.ses.common.util.PdfFontUtils(pdfProps), provider);
    }

    @Test
    void generate_有効なPDFバイト列を返す() throws Exception {
        Quotation q = new Quotation(); q.setId(1L); q.setQuotationNo("Q-202607-0001"); q.setTitle("金融システム開発"); q.setUnitPrice(new java.math.BigDecimal("700000")); q.setCustomerId(1L); q.setEngineerId(2L);
        byte[] bytes = service().generate(q);
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
