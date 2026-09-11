package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Engineer;
import com.ses.service.EngineerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import com.ses.config.LoginUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 要員登録APIの入力バリデーション検証（P8 Task1）
 */
@WebMvcTest(EngineerApiController.class)
class EngineerApiControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EngineerService engineerService;

    @MockBean
    private com.ses.service.EngineerSalesService engineerSalesService;

    @MockBean
    private com.ses.service.security.DataScopeService dataScopeService;

    @MockBean
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    @MockBean
    private com.ses.service.RetentionRiskService retentionRiskService;

    @MockBean
    private com.ses.service.EngineerAccountLinkService engineerAccountLinkService;
    @MockBean
    private com.ses.service.security.TenantOwnershipResolver tenantOwnershipResolver;
    @MockBean
    private com.ses.mapper.EngineerMapper engineerMapper;
    @MockBean
    private com.ses.service.security.LegalEntityContextService legalEntityContextService;

    @BeforeEach
    void allowFullScopeForExistingControllerCases() {
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(tenantOwnershipResolver.resolveEngineerIds("default"))
                .thenReturn(java.util.Set.of(1L));
    }

    /** 氏名・雇用形態が揃った正常な要員は登録成功（code=200） */
    @Test
    void save_validEngineer_returns200() throws Exception {
        when(engineerService.save(any(Engineer.class))).thenReturn(true);

        Engineer engineer = Engineer.builder()
                .fullName("山田太郎")
                .employmentType("正社員")
                .status("Bench")
                .expectedUnitPrice(new BigDecimal("60"))
                .build();

        mockMvc.perform(post("/api/engineers").with(tenantAuthentication()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /** 氏名が空の場合は code=400 と日本語メッセージを返す */
    @Test
    void save_blankFullName_returns400() throws Exception {
        Engineer engineer = Engineer.builder()
                .fullName("")
                .employmentType("正社員")
                .status("Bench")
                .build();

        mockMvc.perform(post("/api/engineers").with(tenantAuthentication()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(engineer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("氏名は必須です")));
    }

    /** 希望単価が負値の場合は code=400 を返す */
    @Test
    void save_negativeUnitPrice_returns400() throws Exception {
        Engineer engineer = Engineer.builder()
                .fullName("山田太郎")
                .employmentType("正社員")
                .status("Bench")
                .expectedUnitPrice(new BigDecimal("-1"))
                .build();

        mockMvc.perform(post("/api/engineers").with(tenantAuthentication()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(engineer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @MockBean
    private com.ses.service.ProposalService proposalService;

    @Test
    void getProposalHistory_returns200() throws Exception {
        when(proposalService.getProposalHistory(1L)).thenReturn(java.util.List.of());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/engineers/1/proposal-history")
                        .with(tenantAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private RequestPostProcessor tenantAuthentication() {
        com.ses.entity.SysUser user = new com.ses.entity.SysUser();
        user.setId(1L);
        user.setUsername("test-admin");
        user.setPassword("password");
        user.setRole("管理者");
        user.setStatus(1);
        user.setTenantId("default");
        LoginUser principal = new LoginUser(user,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
