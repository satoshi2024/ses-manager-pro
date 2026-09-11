package com.ses.integration;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.BaseIntegrationTest;
import com.ses.config.LoginUser;
import com.ses.dto.project.ProjectListDto;
import com.ses.entity.Customer;
import com.ses.entity.Project;
import com.ses.entity.SysUser;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 案件APIのfullAccessでもtenant ownershipを超えないことをH2 dual-tenantで検証する。
 */
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
class ProjectTenantIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private com.ses.mapper.SysUserMapper sysUserMapper;
    @Autowired
    private ObjectMapper objectMapper;

    private Fixture tenantA;
    private Fixture tenantB;

    @BeforeEach
    void setUp() {
        tenantA = fixture("tenant-a", "A");
        tenantB = fixture("tenant-b", "B");
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 管理者fullAccess一覧は現在tenantの案件だけを返す() throws Exception {
        mockMvc.perform(get("/api/projects").param("current", "1").param("size", "50")
                        .with(asAdmin(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(tenantA.projectId()));

        mockMvc.perform(get("/api/projects/options").with(asAdmin(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(tenantA.projectId()));
    }

    @Test
    void 管理者でも他tenant案件のGET_PUT_DELETEは404() throws Exception {
        mockMvc.perform(get("/api/projects/" + tenantB.projectId()).with(asAdmin(tenantA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        Map<String, Object> body = Map.of(
                "id", tenantB.projectId(),
                "projectName", "横断更新",
                "customerId", tenantA.customerId());
        mockMvc.perform(put("/api/projects").with(asAdmin(tenantA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(delete("/api/projects/" + tenantB.projectId())
                        .with(asAdmin(tenantA)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        Project stillThere = projectMapper.selectById(tenantB.projectId());
        assertThat(stillThere).isNotNull();
        assertThat(stillThere.getDeletedFlag()).isZero();
    }

    @Test
    void tenantNULL顧客と顧客tenant不一致の案件は不可視() {
        Customer nullTenantCustomer = customer(null, "未解決顧客");
        Project nullTenantProject = project(nullTenantCustomer.getId(), "NULL顧客案件");

        Customer wrongCustomer = customer("tenant-b", "B顧客をA案件へ");
        Project mismatch = project(wrongCustomer.getId(), "顧客tenant不一致");

        assertThat(projectMapper.selectByIdForTenant(nullTenantProject.getId(), "tenant-a")).isNull();
        assertThat(projectMapper.selectByIdForTenant(mismatch.getId(), "tenant-a")).isNull();
        assertThat(projectMapper.selectOwnedProjectIds("tenant-a"))
                .containsExactly(tenantA.projectId());

        Page<ProjectListDto> page = projectMapper.selectPageWithNames(
                new Page<>(1, 50), null, null, null, null, null, "tenant-a");
        assertThat(page.getRecords()).extracting(ProjectListDto::getId)
                .containsExactly(tenantA.projectId());
    }

    private Fixture fixture(String tenantId, String label) {
        SysUser user = new SysUser();
        user.setUsername("proj-" + tenantId);
        user.setPassword("password");
        user.setRealName("管理者" + label);
        user.setRole("管理者");
        user.setTenantId(tenantId);
        user.setStatus(1);
        sysUserMapper.insert(user);

        Customer customer = customer(tenantId, "顧客" + label);
        Project project = project(customer.getId(), "案件" + label);
        return new Fixture(tenantId, user, customer.getId(), project.getId());
    }

    private Customer customer(String tenantId, String name) {
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setCompanyName(name);
        customerMapper.insert(customer);
        return customer;
    }

    private Project project(Long customerId, String name) {
        Project project = new Project();
        project.setCustomerId(customerId);
        project.setProjectName(name);
        project.setStatus("募集中");
        projectMapper.insert(project);
        return project;
    }

    private RequestPostProcessor asAdmin(Fixture fixture) {
        SysUser principalUser = new SysUser();
        principalUser.setId(fixture.user().getId());
        principalUser.setUsername(fixture.user().getUsername());
        principalUser.setPassword(fixture.user().getPassword());
        principalUser.setRealName(fixture.user().getRealName());
        principalUser.setRole("管理者");
        principalUser.setTenantId(fixture.user().getTenantId());
        principalUser.setStatus(fixture.user().getStatus());
        LoginUser principal = new LoginUser(principalUser,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                principal.getAuthorities()));
    }

    private record Fixture(String tenantId, SysUser user, Long customerId, Long projectId) {
    }
}
