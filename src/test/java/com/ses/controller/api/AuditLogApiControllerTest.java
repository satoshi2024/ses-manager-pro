package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 監査ログAPIのテスト（P8フォローアップ・提案11）。
 */
@WebMvcTest(AuditLogApiController.class)
class AuditLogApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditLogService auditLogService;

    @Test
    @WithMockUser(roles = "管理者")
    void page_管理者は200() throws Exception {
        when(auditLogService.page(any(Long.class), any(Long.class), any(), any())).thenReturn(new Page<>());
        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
