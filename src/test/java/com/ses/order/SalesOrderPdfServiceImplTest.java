package com.ses.order;

import com.lowagie.text.pdf.PdfReader;
import com.ses.config.PdfProperties;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.SalesOrder;
import com.ses.entity.SalesOrderLine;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SalesOrderLineMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.impl.SalesOrderPdfServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.MessageSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * T056定向テスト: 注文請書PDF生成（L1）。同梱フォント(ipaexg.ttf)で無条件に検証する。
 */
class SalesOrderPdfServiceImplTest {

    private SalesOrderPdfServiceImpl service() {
        SystemConfigService cfg = Mockito.mock(SystemConfigService.class);
        when(cfg.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));

        CustomerMapper cm = Mockito.mock(CustomerMapper.class);
        Customer c = new Customer();
        c.setCompanyName("株式会社テスト商事");
        when(cm.selectById(1L)).thenReturn(c);

        EngineerMapper em = Mockito.mock(EngineerMapper.class);
        Engineer e = new Engineer();
        e.setFullName("山田太郎");
        when(em.selectById(2L)).thenReturn(e);

        ProjectMapper pm = Mockito.mock(ProjectMapper.class);
        Project p = new Project();
        p.setProjectName("金融システム開発");
        when(pm.selectById(3L)).thenReturn(p);

        SalesOrderLineMapper lm = Mockito.mock(SalesOrderLineMapper.class);
        SalesOrderLine line = new SalesOrderLine();
        line.setId(10L);
        line.setLineNo(1);
        line.setEngineerId(2L);
        line.setProjectId(3L);
        line.setUnitPrice(new BigDecimal("600000"));
        line.setAmount(new BigDecimal("600000"));
        when(lm.selectList(any())).thenReturn(List.of(line));

        MessageSource messageSource = Mockito.mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), Mockito.isNull(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));

        PdfProperties pdfProps = new PdfProperties();
        org.springframework.beans.factory.ObjectProvider<com.ses.service.DocumentService> provider =
                Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        return new SalesOrderPdfServiceImpl(cfg, cm, em, pm, lm, new com.ses.common.util.PdfFontUtils(pdfProps), provider, messageSource);
    }

    @Test
    void generate_有効な注文請書PDFを返す() throws Exception {
        SalesOrder order = new SalesOrder();
        order.setId(1L);
        order.setOrderNo("O-202608-0001");
        order.setCustomerPoNo("PO-100");
        order.setCustomerId(1L);
        order.setOrderDate(LocalDate.of(2026, 8, 5));
        order.setStartDate(LocalDate.of(2026, 9, 1));
        order.setEndDate(LocalDate.of(2027, 8, 31));
        order.setTotalAmountSnapshot(new BigDecimal("600000"));
        order.setPaymentTermsSnapshot("翌月末払い");

        byte[] bytes = service().generate(order);
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
