package com.ses.controller.api;

import com.ses.entity.Menu;
import com.ses.service.MenuCacheService;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T079 B2: scenario compare APIのL2〜L3 test。CSRF・CRUD・比較・HRの粗利maskを確認する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StaffingScenarioApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthorizationService authorizationService;

    @MockBean
    private MenuCacheService menuCacheService;

    @BeforeEach
    void setUp() {
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
        when(menuCacheService.getAllMenus()).thenReturn(List.of(
                Menu.builder().menuKey("analytics").pathPrefix("/analytics").apiPrefix("/api/analytics").build()));
        when(menuCacheService.getMenuKeysByRole("管理者")).thenReturn(List.of("analytics"));
        when(menuCacheService.getMenuKeysByRole("HR")).thenReturn(List.of("analytics"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void CSRFトークンなしのscenario作成は拒否される() throws Exception {
        authenticate(91001L, "管理者");
        mockMvc.perform(post("/api/analytics/staffing-scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"S1\",\"baseDate\":\"2026-09-01\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void scenarioの作成一覧比較がAPIで動く() throws Exception {
        authenticate(91002L, "管理者");
        String created = mockMvc.perform(post("/api/analytics/staffing-scenarios")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"API-S1\",\"baseDate\":\"2026-09-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ownerUserId").value(91002L))
                .andReturn().getResponse().getContentAsString();
        long id = extractId(created);

        mockMvc.perform(get("/api/analytics/staffing-scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/analytics/staffing-scenarios/compare")
                        .param("scenarioIds", String.valueOf(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(24))
                .andExpect(jsonPath("$.data[0].scenarioId").value(id));

        mockMvc.perform(delete("/api/analytics/staffing-scenarios/" + id)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void HRには粗利がmaskされて返る() throws Exception {
        authenticate(91003L, "HR");
        String created = mockMvc.perform(post("/api/analytics/staffing-scenarios")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"API-HR\",\"baseDate\":\"2026-09-01\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = extractId(created);
        mockMvc.perform(get("/api/analytics/staffing-scenarios/compare")
                        .param("scenarioIds", String.valueOf(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].grossProfit").doesNotExist());
    }

    private void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private long extractId(String json) {
        int idx = json.indexOf("\"id\":");
        if (idx < 0) {
            return -1;
        }
        String rest = json.substring(idx + 5);
        int end = 0;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
            end++;
        }
        return end == 0 ? -1 : Long.parseLong(rest.substring(0, end));
    }
}
