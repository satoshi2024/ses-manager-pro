package com.ses.controller.api;

import com.ses.dto.InvoiceDetailDto;
import com.ses.mapper.BpPaymentMapper;
import com.ses.service.InvoicePdfService;
import com.ses.service.InvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 請求書PDFダウンロードAPIのテスト（P8フォローアップ・提案12）。
 */
@WebMvcTest(InvoiceApiController.class)
class InvoiceApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InvoiceService invoiceService;

    @MockBean
    private InvoicePdfService invoicePdfService;

    @MockBean
    private BpPaymentMapper bpPaymentMapper;

    @Test
    @WithMockUser
    void pdf_ContentTypeとファイル名付きで返す() throws Exception {
        InvoiceDetailDto detail = new InvoiceDetailDto();
        detail.setInvoiceNo("INV-202607-0001");
        when(invoiceService.detail(anyLong())).thenReturn(detail);
        when(invoicePdfService.generate(any())).thenReturn(new byte[]{'%', 'P', 'D', 'F'});

        mockMvc.perform(get("/api/invoices/1/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString(MediaType.APPLICATION_PDF_VALUE)))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("INV-202607-0001")));
    }
}
