package com.ses.config;

import com.ses.service.AuditLogService;
import com.ses.service.ContractRenewalService;
import com.ses.service.ContractService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契約自動更新ドラフト手動生成（/api/contracts/generate-renewals）の権限検証
 * （P8フォローアップ・提案14）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContractRenewalSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ContractService contractService;

    @MockBean
    private ContractRenewalService contractRenewalService;

    @MockBean
    private AuditLogService auditLogService;

    @Test
    @WithMockUser(roles = "営業")
    void generateRenewals_一般ロールは403() throws Exception {
        mockMvc.perform(post("/api/contracts/generate-renewals").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void generateRenewals_管理者は200() throws Exception {
        mockMvc.perform(post("/api/contracts/generate-renewals").with(csrf()))
                .andExpect(status().isOk());
    }
}
