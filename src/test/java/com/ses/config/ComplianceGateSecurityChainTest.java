package com.ses.config;

import com.ses.service.ComplianceApprovalService;
import com.ses.service.ComplianceExternalReviewAdoptionService;
import com.ses.service.ComplianceExternalReviewVerificationService;
import com.ses.service.ComplianceGateAdminService;
import com.ses.service.ComplianceMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R23-P1-01 P0-2/§5: 実SecurityFilterChainのrole境界テスト。
 * 管理者/HR/マネージャーはapproval/capabilitiesへ到達可能（P0-2 matcher順序修正の検証）。
 * 営業・要員は403。管理操作（verifications等）は管理者のみ。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(statements = {
        "INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) "
                + "SELECT 'compliance-gate', '派遣コンプライアンスG2', '/compliance-gate', '/api/compliance-gate', 73 "
                + "WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'compliance-gate')",
        "INSERT INTO t_role_menu (role, menu_id) "
                + "SELECT r.role, m.id FROM (SELECT '管理者' AS role UNION SELECT 'HR' UNION SELECT 'マネージャー') r, m_menu m "
                + "WHERE m.menu_key = 'compliance-gate' "
                + "AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = r.role AND rm.menu_id = m.id)"
})
@Transactional
class ComplianceGateSecurityChainTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ComplianceGateAdminService complianceGateAdminService;
    @MockBean
    private ComplianceApprovalService complianceApprovalService;
    @MockBean
    private ComplianceExternalReviewVerificationService verificationService;
    @MockBean
    private ComplianceExternalReviewAdoptionService adoptionService;
    @MockBean
    private ComplianceMappingService complianceMappingService;
    @MockBean
    private com.ses.service.compliance.ComplianceCapabilityService capabilityService;

    @Test
    @WithMockUser(roles = "HR")
    void HRはcapabilitiesとapprovalへ到達できる() throws Exception {
        when(capabilityService.current()).thenReturn(new com.ses.dto.compliance.ComplianceCapabilityDto());
        mockMvc.perform(get("/api/compliance-gate/capabilities"))
                .andExpect(status().isOk());
        when(complianceApprovalService.approve(any(), any(), any(), any(), any()))
                .thenReturn(new com.ses.entity.ComplianceMappingApprovalEvent());
        mockMvc.perform(post("/api/compliance-gate/approvals").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappingId\":1,\"workplaceId\":1,\"reason\":\"r\","
                                + "\"evidenceDocumentId\":1,\"evidenceDocumentVersionId\":1}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void マネージャーはcapabilitiesへ到達できる() throws Exception {
        when(capabilityService.current()).thenReturn(new com.ses.dto.compliance.ComplianceCapabilityDto());
        mockMvc.perform(get("/api/compliance-gate/capabilities"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業はcomplianceGateに到達できない() throws Exception {
        mockMvc.perform(get("/api/compliance-gate/capabilities"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/compliance-gate"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "要員")
    void 要員はcomplianceGateに到達できない() throws Exception {
        mockMvc.perform(get("/api/compliance-gate/capabilities"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "HR")
    void HRは管理操作subjectsPOSTへ到達できない() throws Exception {
        mockMvc.perform(post("/api/compliance-gate/subjects").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectCode\":\"S1\",\"displayName\":\"n\",\"organizationName\":\"o\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 管理者は全てのcomplianceGate操作へ到達できる() throws Exception {        when(complianceGateAdminService.listSubjects()).thenReturn(java.util.List.of());
        mockMvc.perform(get("/api/compliance-gate/subjects"))
                .andExpect(status().isOk());
        when(capabilityService.current()).thenReturn(new com.ses.dto.compliance.ComplianceCapabilityDto());
        mockMvc.perform(get("/api/compliance-gate/capabilities"))
                .andExpect(status().isOk());
    }
}
