package com.ses.config;

import com.ses.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 監査ログ画面・APIが管理者限定であることの検証（P8フォローアップ・提案11）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditLogService auditLogService;

    @Test
    @WithMockUser(roles = "営業")
    void api_一般ロールは403() throws Exception {
        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void api_管理者は200() throws Exception {
        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isOk());
    }
}
