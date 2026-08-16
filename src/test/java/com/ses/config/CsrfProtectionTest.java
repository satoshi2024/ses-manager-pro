package com.ses.config;

import com.ses.service.EngineerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSRF保護の検証（P8 Task6）。
 * /api/** の更新系はCSRFトークン無しで403、トークン付きで通過することを確認する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsrfProtectionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EngineerService engineerService;

    private static final String VALID_BODY =
            "{\"fullName\":\"山田太郎\",\"employmentType\":\"正社員\",\"status\":\"Bench\"}";

    @Test
    @WithMockUser(roles = "管理者")
    void post_トークン無しは403() throws Exception {
        mockMvc.perform(post("/api/engineers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void post_トークン付きは通過する() throws Exception {
        when(engineerService.save(any())).thenReturn(true);
        mockMvc.perform(post("/api/engineers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());
    }

    // R23-P1-01 §5: compliance-gateの更新系APIもCSRF保護対象（/api/** 共通契約）。

    @Test
    @WithMockUser(roles = "管理者")
    void complianceGate_post_トークン無しは403() throws Exception {
        mockMvc.perform(post("/api/compliance-gate/mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappingCode\":\"G1\",\"mappingVersion\":\"V1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void complianceGate_post_トークン付きは403にならない() throws Exception {
        // mapping作成はsource必須（SRC-C/E/N/L/INDEX）で400になるが、CSRF通過（403でないこと）を検証
        mockMvc.perform(post("/api/compliance-gate/mappings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappingCode\":\"G1\",\"mappingVersion\":\"V1\"}"))
                .andExpect(status().isBadRequest());
    }
}
