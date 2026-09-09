package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Customer;
import com.ses.config.LoginUser;
import com.ses.service.ContractService;
import com.ses.service.CustomerService;
import com.ses.service.ProjectService;
import com.ses.service.ProposalService;
import com.ses.service.SalesActivityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.util.Map;
import java.util.Set;
import com.ses.service.accounting.AccountingTenantContextHolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 顧客APIのテスト（P8 Task9）: 一覧・登録・バリデーションエラー。
 */
@WebMvcTest(CustomerApiController.class)
class CustomerApiControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CustomerService customerService;
    @MockBean
    private ProjectService projectService;
    @MockBean
    private ProposalService proposalService;
    @MockBean
    private ContractService contractService;
    @MockBean
    private SalesActivityService salesActivityService;
    @MockBean
    private com.ses.service.security.DataScopeService dataScopeService;
    @MockBean
    private com.ses.service.security.OrganizationScopeService organizationScopeService;
    @MockBean
    private com.ses.service.security.AuthorizationService authorizationService;
    @MockBean
    private com.ses.service.security.TenantOwnershipResolver tenantOwnershipResolver;
    @MockBean
    private com.ses.mapper.ProjectMapper projectMapper;
    @MockBean
    private com.ses.mapper.ContractMapper contractMapper;

    @BeforeEach
    void allowFullOrganizationScope() {
        AccountingTenantContextHolder.setTenantId("default");
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
        when(tenantOwnershipResolver.resolveCustomerIds("default")).thenReturn(Set.of());     }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void page_一覧は200() throws Exception {
        when(customerService.pageForTenant(any(), anyString(), any(), any(), any(), any())).thenReturn(new Page<>());
        mockMvc.perform(get("/api/customers").with(tenantAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void save_正常は200() throws Exception {
        when(customerService.save(any())).thenReturn(true);
        Customer c = new Customer();
        c.setCompanyName("株式会社テスト");
        mockMvc.perform(post("/api/customers").with(tenantAuthentication()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void save_会社名空は400() throws Exception {
        Map<String, Object> body = Map.of("contactPerson", "担当A");
        mockMvc.perform(post("/api/customers").with(tenantAuthentication()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private RequestPostProcessor tenantAuthentication() {
        com.ses.entity.SysUser user = new com.ses.entity.SysUser();
        user.setId(1L);
        user.setUsername("test-user");
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
