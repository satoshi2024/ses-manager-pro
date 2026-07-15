package com.ses.config;

import com.ses.entity.BpPayment;
import com.ses.service.BpPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BpPaymentSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BpPaymentService bpPaymentService;

    @Test
    @WithMockUser(roles = "HR")
    void updateLayer_invoice権限がないロールは403() throws Exception {
        mockMvc.perform(put("/api/invoices/bp-payments/1/layer")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500000}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void updateLayer_管理者は実行できる() throws Exception {
        BpPayment result = new BpPayment();
        result.setId(1L);
        when(bpPaymentService.updateLayer(eq(1L), any(BpPayment.class))).thenReturn(result);

        mockMvc.perform(put("/api/invoices/bp-payments/1/layer")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500000}"))
                .andExpect(status().isOk());
    }
}
