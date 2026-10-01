package com.ses.controller.api;

import com.ses.BaseIntegrationTest;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NF08: legacy match / proposal-draft / chat は ai.enabled 統一gateを通り、OFF時は503で副作用なし。
 */
@TestPropertySource(properties = {
        "ai.enabled=false",
        "ai.provider=mock",
        "ai.external-send-enabled=false",
        "app.security.oidc.tenant-id="
})
class AiLegacyEndpointFeatureGateMockMvcTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clear() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void aiEnabledFalseのmatchは503でrecommendationRunを作らない() throws Exception {
        long before = countRuns();

        mockMvc.perform(post("/api/ai/match/engineer-to-projects")
                        .with(tenantUser("default"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"engineerId\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));

        mockMvc.perform(get("/api/ai/matching/project/1")
                        .with(tenantUser("default"))
                        .with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));

        assertEquals(before, countRuns());
    }

    @Test
    void aiEnabledFalseのproposalDraftは503で副作用なし() throws Exception {
        long before = countRuns();

        mockMvc.perform(post("/api/ai/proposal-draft")
                        .with(tenantUser("default"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"engineerId\":1,\"projectId\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));

        assertEquals(before, countRuns());
    }

    @Test
    void aiEnabledFalseのchat無resourceは503でrecommendationRunを作らない() throws Exception {
        long before = countRuns();

        mockMvc.perform(post("/api/ai/chat")
                        .with(tenantUser("default"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"こんにちは\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));

        assertEquals(before, countRuns());
    }

    @Test
    void aiEnabledFalseのchat有resourceは503で副作用なし() throws Exception {
        long before = countRuns();

        mockMvc.perform(post("/api/ai/chat")
                        .with(tenantUser("default"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"候補を教えて\",\"engineerId\":1,\"projectId\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));

        assertEquals(before, countRuns());
    }

    private long countRuns() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_ai_recommendation_run", Long.class);
        return count == null ? 0L : count;
    }

    private RequestPostProcessor tenantUser(String tenantId) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("admin123");
        user.setRole("管理者");
        user.setStatus(1);
        user.setTenantId(tenantId);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
    }
}
