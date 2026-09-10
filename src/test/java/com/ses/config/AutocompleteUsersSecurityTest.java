package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/autocomplete/users が管理者限定であることの検証（WS-C R3）。
 * 営業ロールは403、管理者ロールは200を返すことを確認する。
 * また engineers エンドポイントは全ロールから引き続き利用可能であること。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AutocompleteUsersSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "営業")
    void users_営業ロールは403() throws Exception {
        mockMvc.perform(get("/api/autocomplete/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    void users_管理者ロールは200() throws Exception {
        mockMvc.perform(get("/api/autocomplete/users").with(authentication("管理者")))
                .andExpect(status().isOk());
    }

    @Test
    void engineers_営業ロールは200() throws Exception {
        mockMvc.perform(get("/api/autocomplete/engineers").with(authentication("営業")))
                .andExpect(status().isOk());
    }

    private RequestPostProcessor authentication(String role) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("autocomplete-security-user");
        user.setPassword("password");
        user.setRole(role);
        user.setStatus(1);
        user.setTenantId("default");
        LoginUser principal = new LoginUser(user,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
