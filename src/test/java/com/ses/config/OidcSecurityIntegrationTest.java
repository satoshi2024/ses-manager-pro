package com.ses.config;

import com.ses.entity.UserExternalIdentity;
import com.ses.service.ExternalIdentityProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T016の直接consumer: 管理者承認API、CSRF、POST-only logout。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OidcSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExternalIdentityProvisioningService provisioningService;

    @Test
    @WithMockUser(roles = "営業")
    void 外部identity承認APIは管理者以外を拒否する() throws Exception {
        mockMvc.perform(post("/api/identity-providers/10/external-identities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":99,\"subject\":\"sub-1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "HR")
    void 外部identity承認APIはHRを拒否する() throws Exception {
        mockMvc.perform(post("/api/identity-providers/10/external-identities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"subject\":\"sub-admin\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void 外部identity承認APIはマネージャーを拒否する() throws Exception {
        mockMvc.perform(post("/api/identity-providers/10/external-identities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"subject\":\"sub-admin\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 外部identity承認APIはCSRFなしを拒否する() throws Exception {
        mockMvc.perform(post("/api/identity-providers/10/external-identities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":99,\"subject\":\"sub-1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 外部identity承認APIは管理者かつCSRF付きだけ通過する() throws Exception {
        UserExternalIdentity link = new UserExternalIdentity();
        link.setId(1L);
        link.setTenantId("test-tenant");
        link.setUserId(99L);
        link.setProviderId(10L);
        link.setSubject("sub-1");
        when(provisioningService.provision(any(), any())).thenReturn(link);

        mockMvc.perform(post("/api/identity-providers/10/external-identities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":99,\"subject\":\"sub-1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HR")
    void HRは管理者専用画面とAPIへ到達できない() throws Exception {
        mockMvc.perform(get("/user/list")).andExpect(status().isForbidden());
        mockMvc.perform(get("/system-config")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/role-menus")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/system-configs")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void マネージャーは管理者専用画面とAPIへ到達できない() throws Exception {
        mockMvc.perform(get("/user/list")).andExpect(status().isForbidden());
        mockMvc.perform(get("/system-config")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/role-menus")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/system-configs")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void logoutはPOSTかつCSRF必須でGETLogoutを許可しない() throws Exception {
        mockMvc.perform(get("/logout"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/logout").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login?logout"));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void MFA未完了のsessionは通常画面へ進めない() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE, Boolean.TRUE);

        mockMvc.perform(get("/dashboard").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/mfa/challenge"));
    }
}
