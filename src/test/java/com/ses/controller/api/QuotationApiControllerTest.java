package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Quotation;
import com.ses.service.QuotationPdfService;
import com.ses.service.QuotationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(QuotationApiController.class)
class QuotationApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QuotationService quotationService;

    @MockBean
    private QuotationPdfService quotationPdfService;

    @Test
    @WithMockUser
    void list_returnsOk() throws Exception {
        when(quotationService.page(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());
        mockMvc.perform(get("/api/quotations").param("keyword", "金融"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void changeStatus_delegatesToService() throws Exception {
        mockMvc.perform(put("/api/quotations/1/status")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"status\":\"提出済\"}"))
                .andExpect(status().isOk());
        verify(quotationService).changeStatus(1L, "提出済");
    }

    @Test
    @WithMockUser
    void delete_delegatesToService() throws Exception {
        when(quotationService.removeById(anyLong())).thenReturn(true);
        mockMvc.perform(delete("/api/quotations/5")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
        verify(quotationService).removeById(5L);
    }

    @Test
    @WithMockUser
    void createDraft_delegatesToService() throws Exception {
        when(quotationService.createDraftFromQuotation(anyLong())).thenReturn(new com.ses.entity.Contract());
        mockMvc.perform(post("/api/quotations/3/create-draft")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
        verify(quotationService).createDraftFromQuotation(3L);
    }
}
