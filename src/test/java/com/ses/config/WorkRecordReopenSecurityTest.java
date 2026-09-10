package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.service.WorkRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 月次確定解除（/api/work-records/reopen）の権限検証。
 * 管理者のみ実行可能で、一般ロールは403になることを確認する
 * （@EnableMethodSecurity + SecurityConfig requestMatchers の二重防御）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkRecordReopenSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkRecordService workRecordService;

    @org.junit.jupiter.api.BeforeEach
    void bindTenantToMockPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() instanceof LoginUser) {
            return;
        }
        String role = authentication.getAuthorities().stream()
                .map(a -> a.getAuthority().startsWith("ROLE_")
                        ? a.getAuthority().substring("ROLE_".length()) : a.getAuthority())
                .findFirst().orElse("管理者");
        SysUser user = new SysUser();
        user.setUsername(authentication.getName());
        user.setPassword("test");
        user.setRole(role);
        user.setStatus(1);
        user.setTenantId("default");
        LoginUser principal = new LoginUser(user,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    @WithMockUser(roles = "営業")
    void reopen_一般ロールは403() throws Exception {
        mockMvc.perform(post("/api/work-records/reopen").param("month", "2026-07").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void reopen_管理者は実行できる() throws Exception {
        mockMvc.perform(post("/api/work-records/reopen").param("month", "2026-07").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "営業")
    void confirm_営業は組織を跨ぐ月次確定を実行できない() throws Exception {
        mockMvc.perform(post("/api/work-records/confirm").param("month", "2026-07").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void confirm_マネージャーは組織を跨ぐ月次確定を実行できない() throws Exception {
        mockMvc.perform(post("/api/work-records/confirm").param("month", "2026-07").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void confirm_管理者は実行できる() throws Exception {
        mockMvc.perform(post("/api/work-records/confirm").param("month", "2026-07").with(csrf()))
                .andExpect(status().isOk());
    }
}
