package com.ses.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("REV-P0-001 OIDC 外部ID紐付け及び管理者ハード境界セキュリティテスト")
class OidcSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "hr_user", roles = {"HR"})
    @DisplayName("HRロールが /api/identity-providers/1/external-identities を呼ぶと 403 Forbidden で拒絶されること")
    void hrUser_cannotAccessExternalIdentities() throws Exception {
        String body = """
                {
                    "userId": 1,
                    "subject": "sub-12345",
                    "emailSnapshot": "admin@example.com"
                }
                """;
        mockMvc.perform(post("/api/identity-providers/1/external-identities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "manager_user", roles = {"マネージャー"})
    @DisplayName("マネージャーロールが /api/identity-providers/1/external-identities を呼ぶと 403 Forbidden で拒絶されること")
    void managerUser_cannotAccessExternalIdentities() throws Exception {
        String body = """
                {
                    "userId": 1,
                    "subject": "sub-67890",
                    "emailSnapshot": "admin@example.com"
                }
                """;
        mockMvc.perform(post("/api/identity-providers/1/external-identities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "hr_user", roles = {"HR"})
    @DisplayName("HRロールが管理者専用URL (/user/list, /system-config) にアクセスすると 403 Forbidden になること")
    void hrUser_cannotAccessAdminBoundaries() throws Exception {
        mockMvc.perform(get("/user/list"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/system-config"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/audit-log/list"))
                .andExpect(status().isForbidden());
    }
}
