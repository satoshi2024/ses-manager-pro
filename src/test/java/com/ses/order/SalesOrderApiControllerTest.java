package com.ses.order;

import com.ses.common.result.ApiResult;
import com.ses.dto.order.SalesOrderSaveRequest;
import com.ses.entity.SalesOrder;
import com.ses.service.SalesOrderService;
import com.ses.service.approval.ApprovalTargetAdapterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T056定向テスト: 注文API（一覧/作成/原本/PDF/download/承認）（L2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "admin", roles = "管理者")
class SalesOrderApiControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean SalesOrderService salesOrderService;
    @MockBean ApprovalTargetAdapterRegistry approvalRegistry;

    @Test
    void create_returnsPoWarningWhenDuplicate() throws Exception {
        SalesOrder order = new SalesOrder();
        order.setId(10L);
        order.setOrderNo("O-202608-0001");
        order.setCustomerId(1L);
        order.setCustomerPoNo("PO-1");
        when(salesOrderService.createFromRequest(any())).thenReturn(order);
        when(salesOrderService.isCustomerPoDuplicate(eq(1L), any(), any())).thenReturn(true);

        SalesOrderSaveRequest req = new SalesOrderSaveRequest();
        req.setCustomerId(1L);
        req.setOrderDate(LocalDate.of(2026, 8, 5));
        SalesOrderSaveRequest.Line line = new SalesOrderSaveRequest.Line();
        line.setEngineerId(2L);
        line.setUnitPrice(java.math.BigDecimal.valueOf(600000));
        req.setLines(List.of(line));

        mockMvc.perform(post("/api/sales-orders")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"customerId\":1,\"orderDate\":\"2026-08-05\",\"lines\":[{\"engineerId\":2,\"unitPrice\":600000}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.poWarning").value(true));
    }

    @Test
    void uploadSourceDocument_rejectsDuplicateHash() throws Exception {
        doThrow(new com.ses.common.exception.BusinessException(409, "error.order.duplicateSourceDocument"))
                .when(salesOrderService).uploadSourceDocument(eq(1L), any());

        MockMultipartFile file = new MockMultipartFile("file", "order.pdf", "application/pdf", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/sales-orders/1/source-document").file(file).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void downloadDocument_isScopedToOrder() throws Exception {
        when(salesOrderService.downloadDocument(eq(1L), eq(99L)))
                .thenReturn(new java.io.ByteArrayInputStream("pdf".getBytes()));

        mockMvc.perform(get("/api/sales-orders/1/documents/99"))
                .andExpect(status().isOk());
    }

    @Test
    void detail_returnsJson() throws Exception {
        when(salesOrderService.detail(1L)).thenReturn(new com.ses.dto.order.SalesOrderDetailDto());
        mockMvc.perform(get("/api/sales-orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
