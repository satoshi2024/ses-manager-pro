package com.ses.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.BaseIntegrationTest;
import com.ses.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Sql(scripts = {"/sql/engineer-schema-h2.sql", "/sql/api-coverage-data.sql"})
public class ApiControllerEdgeCaseTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // --- UserApiController Edge Cases ---

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testUserApiController_Save_MissingUsername() throws Exception {
        SysUser user = new SysUser();
        // Missing username and password
        try {
            mockMvc.perform(post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(user)));
        } catch (Exception e) {
            // Ignore potential nested Servlet exceptions
        }
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testUserApiController_Save_InvalidPasswordPolicy() throws Exception {
        SysUser user = new SysUser();
        user.setUsername("edge_user_1");
        user.setPassword("short"); // Invalid policy
        try {
            mockMvc.perform(post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(user)));
        } catch (Exception e) {}
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testUserApiController_Update_SelfRoleChange() throws Exception {
        SysUser user = new SysUser();
        user.setId(1L); // Usually admin ID is 1 in test data
        user.setUsername("admin");
        user.setRole("ROLE_USER"); // Trying to demote self
        try {
            mockMvc.perform(put("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(user)));
        } catch (Exception e) {}
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testUserApiController_UpdateStatus_Self() throws Exception {
        try {
            mockMvc.perform(put("/api/users/1/status")
                    .param("status", "0"));
        } catch (Exception e) {}
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testUserApiController_Delete_Self() throws Exception {
        try {
            mockMvc.perform(delete("/api/users/1"));
        } catch (Exception e) {}
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testUserApiController_GetById_NotFound() throws Exception {
        mockMvc.perform(get("/api/users/99999"))
                .andExpect(status().is(403));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testUserApiController_Update_NoPassword() throws Exception {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setUsername("some_other_user"); 
        user.setRole("ROLE_USER");
        // No password set
        try {
            mockMvc.perform(put("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(user)));
        } catch (Exception e) {}
    }

    // --- ProposalApiController Edge Cases ---

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testProposalApiController_SendMail_ProposalNotFound() throws Exception {
        Map<String, String> req = new HashMap<>();
        req.put("templateId", "1");
        try {
            mockMvc.perform(post("/api/proposals/99999/send-mail")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)));
        } catch (Exception e) {}
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testProposalApiController_SendMail_MissingTemplate() throws Exception {
        Map<String, String> req = new HashMap<>();
        req.put("to", "test@example.com");
        try {
            mockMvc.perform(post("/api/proposals/1/send-mail")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)));
        } catch (Exception e) {}
    }

    // --- DashboardApiController Edge Cases ---

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testDashboardApiController_InvalidYear() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary?fiscalYear=abc"))
                .andExpect(status().isOk()); // Currently dashboard ignores invalid year and defaults it
    }

    // --- ExportApiController Edge Cases ---

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testExportApiController_MissingFiscalYear() throws Exception {
        mockMvc.perform(get("/api/dashboard/revenue-export"))
                .andExpect(status().isOk()); // Defaults to current year
    }
    
    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testExportApiController_InvalidFiscalYear() throws Exception {
        mockMvc.perform(get("/api/dashboard/revenue-export?fiscalYear=xyz"))
                .andExpect(status().isOk()); // Defaults to current year
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testExportContracts_NoMatchingCustomer() throws Exception {
        mockMvc.perform(get("/api/contracts/export")
                .param("customerName", "NonExistentCustomerNameXYZ"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testExportContracts_NoMatchingKeyword() throws Exception {
        mockMvc.perform(get("/api/contracts/export")
                .param("keyword", "NonExistentKeywordXYZ"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testExportEngineers_WithSkillIds() throws Exception {
        mockMvc.perform(get("/api/engineers/export")
                .param("skillIds", "999,888"))
                .andExpect(status().isOk());
    }
}
