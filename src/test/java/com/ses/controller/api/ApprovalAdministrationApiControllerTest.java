package com.ses.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R4-P1-01: route/responsibility管理APIの不正入力をHTTP契約で固定する。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApprovalAdministrationApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "1", roles = "管理者")
    void routeの不正approverTypeは400で返す() throws Exception {
        mockMvc.perform(post("/api/approval/routes")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "requestType": "api-invalid-approver-type",
                                  "validFrom": "2026-01-01",
                                  "steps": [{"stepNo": 1, "parallelGroup": 1, "approverType": "UNKNOWN"}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser(username = "1", roles = "管理者")
    void routeの逆期間は400で返す() throws Exception {
        mockMvc.perform(post("/api/approval/routes")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "requestType": "api-reverse-route-period",
                                  "validFrom": "2026-01-02",
                                  "validTo": "2026-01-01",
                                  "steps": [{"stepNo": 1, "parallelGroup": 1, "approverType": "USER", "approverValue": "1"}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser(username = "1", roles = "管理者")
    void responsibilityの不正typeは400で返す() throws Exception {
        mockMvc.perform(post("/api/approval/responsibilities")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "responsibilityType": "UNKNOWN",
                                  "userId": 1,
                                  "validFrom": "2026-01-01"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser(username = "1", roles = "管理者")
    void responsibilityの不存在組織は404で返す() throws Exception {
        mockMvc.perform(post("/api/approval/responsibilities")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "responsibilityType": "ORGANIZATION_MANAGER",
                                  "organizationId": 999999999,
                                  "userId": 1,
                                  "validFrom": "2026-01-01"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @WithMockUser(username = "1", roles = "管理者")
    void responsibilityの無効userは400で返す() throws Exception {
        mockMvc.perform(post("/api/approval/responsibilities")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "responsibilityType": "FINANCE_MANAGER",
                                  "userId": 999999999,
                                  "validFrom": "2026-01-01"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
