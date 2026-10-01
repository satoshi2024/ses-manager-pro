package com.ses.controller.api;

import com.ses.entity.Customer;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.Project;
import com.ses.service.CustomerService;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.SysUserService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.CostCenterService;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import com.ses.service.accounting.AccountingTenantContextHolder;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * customer/project/legal-entityのoption系エンドポイントがid+nameのDTO契約を返すことを固定する。
 *
 * <p>文字列配列を{@code <select>}のvalueに渡すと{@code option value="undefined"}になり、
 * 管理会計フィルターのID絞り込みが機能しない（第十四次Review P1-6/P2-1）。
 */
@WebMvcTest(AutocompleteApiController.class)
class AutocompleteApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private EngineerService engineerService;
    @MockBean private CustomerService customerService;
    @MockBean private ProjectService projectService;
    @MockBean private com.ses.mapper.SysUserMapper sysUserMapper;
    @MockBean private DataScopeService dataScopeService;
    @MockBean private OrganizationScopeService organizationScopeService;
    @MockBean private CostCenterService costCenterService;
    @MockBean private com.ses.service.security.TenantOwnershipResolver tenantOwnershipResolver;
    @MockBean private com.ses.mapper.ProjectMapper projectMapper;

    @BeforeEach
    void bindTenant() {
        AccountingTenantContextHolder.setTenantId("default");
        when(tenantOwnershipResolver.resolveCustomerIds("default")).thenReturn(Set.of(10L));
        when(tenantOwnershipResolver.resolveEngineerIds("default")).thenReturn(Set.of());
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void customerOptions_idとnameを持つオブジェクト配列を返す() throws Exception {
        when(dataScopeService.isScoped()).thenReturn(false);
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        Customer customer = new Customer();
        customer.setId(10L);
        customer.setCompanyName("株式会社テスト");
        when(tenantOwnershipResolver.selectCustomers("default", Set.of(10L), null)).thenReturn(List.of(customer));

        mockMvc.perform(get("/api/autocomplete/customer-options").with(authentication("管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].name").value("株式会社テスト"));
    }

    @Test
    void projectOptions_idとnameを持つオブジェクト配列を返す() throws Exception {
        when(dataScopeService.isScoped()).thenReturn(false);
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        Project project = new Project();
        project.setId(20L);
        project.setProjectName("基幹システム移行");
        when(tenantOwnershipResolver.resolveProjectIds("default")).thenReturn(Set.of(20L));
        when(projectMapper.selectByIdsForTenant("default", Set.of(20L))).thenReturn(List.of(project));

        mockMvc.perform(get("/api/autocomplete/project-options").with(authentication("管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(20))
                .andExpect(jsonPath("$.data[0].name").value("基幹システム移行"));
    }

    /** scope外(空集合)ならSQLを引かず即0件を返し、取得後フィルターに依存しないこと。 */
    @Test
    void customerOptions_scope外は空配列を返す() throws Exception {
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedCustomerIds()).thenReturn(java.util.Set.of());
        when(organizationScopeService.hasFullAccess()).thenReturn(true);

        mockMvc.perform(get("/api/autocomplete/customer-options").with(authentication("営業")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /** 法人フィルターはルート組織の名前を代表名として使う。 */
    @Test
    void legalEntities_ルート組織名を代表名にする() throws Exception {
        OrganizationUnit root = OrganizationUnit.builder()
                .legalEntityId(1L).code("ROOT").name("株式会社ルート")
                .type("事業部").validFrom(LocalDate.of(2026, 1, 1)).status("有効").build();
        root.setId(100L);
        OrganizationUnit child = OrganizationUnit.builder()
                .legalEntityId(1L).code("CHILD").name("開発課").parentId(100L)
                .type("課").validFrom(LocalDate.of(2026, 1, 1)).status("有効").build();
        child.setId(101L);
        when(organizationScopeService.listVisibleOrganizations(null, LocalDate.now()))
                .thenReturn(List.of(child, root));

        mockMvc.perform(get("/api/autocomplete/legal-entities").with(authentication("管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("株式会社ルート"));
    }

    private RequestPostProcessor authentication(String role) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                TenantTestSecurity.authentication(1L, "autocomplete-test-user", "default", role));
    }
}
