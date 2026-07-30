package com.ses.controller.api;

import com.ses.dto.document.DocumentDetailDTO;
import com.ses.dto.document.DocumentListDTO;
import com.ses.service.DocumentService;
import com.ses.service.security.impl.FileScopeValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task A1 DocumentApiController テスト。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentApiControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean DocumentService documentService;
    @MockBean FileScopeValidationService fileScopeValidationService;

    @BeforeEach
    void setUp() {
        var principal = User.withUsername("admin").password("").authorities("ROLE_管理者").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    void list_returns200AndPageResult() throws Exception {
        DocumentListDTO dto = DocumentListDTO.builder()
                .id(1L)
                .title("テスト契約書")
                .documentType("CONTRACT")
                .status("CONFIRMED")
                .build();
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<DocumentListDTO> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 1);
        page.setRecords(List.of(dto));
        when(documentService.searchDocuments(any())).thenReturn(page);

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("テスト契約書"));
    }

    @Test
    void detail_returns200AndDetailDTO() throws Exception {
        DocumentDetailDTO detail = DocumentDetailDTO.builder()
                .id(1L)
                .title("テスト請求書")
                .documentType("INVOICE_OUT")
                .status("DRAFT")
                .build();
        when(documentService.getDocumentDetail(eq(1L))).thenReturn(detail);

        mockMvc.perform(get("/api/documents/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("テスト請求書"));
    }

    @Test
    void disposalApprove_asNonAdmin_returns403() throws Exception {
        var principal = User.withUsername("salesUser").password("").authorities("ROLE_営業").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/documents/disposal/100/approve"))
                .andExpect(status().isForbidden());
    }

    @Test
    void disposalExecute_asNonAdmin_returns403() throws Exception {
        var principal = User.withUsername("salesUser").password("").authorities("ROLE_営業").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/documents/disposal/100/execute"))
                .andExpect(status().isForbidden());
    }
}
