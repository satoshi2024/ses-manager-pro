package com.ses.service.impl;

import com.lowagie.text.pdf.BaseFont;
import com.ses.common.util.PdfFontUtils;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Quotation;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.DocumentService;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("見積書 PDF i18n 多言語化テスト (R3-018)")
class QuotationPdfI18nTest {

    @InjectMocks
    private QuotationPdfServiceImpl quotationPdfService;

    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private CustomerMapper customerMapper;
    @Mock
    private EngineerMapper engineerMapper;
    @Mock
    private PdfFontUtils pdfFontUtils;
    @Mock
    private ObjectProvider<DocumentService> documentServiceProvider;
    @Mock
    private MessageSource messageSource;

    @Test
    @DisplayName("指定されたLocaleに従ってメッセージソースからラベルを読み出す")
    void generateWithLocaleUsesMessageSource() throws Exception {
        Quotation quotation = new Quotation();
        quotation.setId(1L);
        quotation.setQuotationNo("QUO-2026-001");
        quotation.setCustomerId(10L);
        quotation.setEngineerId(20L);
        quotation.setTitle("システム開発見積");
        quotation.setUnitPrice(new BigDecimal("800000"));

        Customer customer = new Customer();
        customer.setId(10L);
        customer.setCompanyName("テスト顧客株式会社");

        Engineer engineer = new Engineer();
        engineer.setId(20L);
        engineer.setInitialName("T.S.");

        when(customerMapper.selectById(10L)).thenReturn(customer);
        when(engineerMapper.selectById(20L)).thenReturn(engineer);
        BaseFont baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        when(pdfFontUtils.resolveCjkFont()).thenReturn(baseFont);

        when(messageSource.getMessage(eq("quotation.pdf.title"), any(), any(), eq(Locale.ENGLISH)))
                .thenReturn("Quotation");
        when(messageSource.getMessage(eq("quotation.pdf.no"), any(), any(), eq(Locale.ENGLISH)))
                .thenReturn("Quotation No: ");

        byte[] pdfBytes = quotationPdfService.generate(quotation, Locale.ENGLISH);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0, "PDFバイト配列が生成されること");
    }
}
