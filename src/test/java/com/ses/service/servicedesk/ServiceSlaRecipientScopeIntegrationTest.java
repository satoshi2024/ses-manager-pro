package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.config.LoginUser;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.Project;
import com.ses.entity.RoleMenu;
import com.ses.entity.ServiceRequest;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.RoleMenuMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.CustomerScopeResolver;
import com.ses.service.security.DataScopeService;
import com.ses.service.servicedesk.impl.ServiceSlaMonitoringServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Recipient本人の実DB組織・sales担当・menu/roleを通じたscope回帰。 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "organization.scope.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:nf02-sla-recipient-scope;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@Transactional
@Execution(ExecutionMode.SAME_THREAD)
class ServiceSlaRecipientScopeIntegrationTest {

    @Autowired private ServiceSlaMonitoringServiceImpl service;
    @Autowired private CustomerScopeResolver customerScopeResolver;
    @Autowired private DataScopeService dataScopeService;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private ProjectMapper projectMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private ContractMapper contractMapper;
    @Autowired private SysUserMapper userMapper;
    @Autowired private OrganizationUnitMapper organizationMapper;
    @Autowired private UserOrganizationMapper userOrganizationMapper;
    @Autowired private ServiceRequestMapper requestMapper;
    @Autowired private RoleMenuMapper roleMenuMapper;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private String originalSalesScope;
    private String tenantId;
    private boolean menuRevocationObserved;
    private RequestAttributes previousRequestAttributes;
    private RequestAttributes testRequestAttributes;

    private void commitSalesScopeConfig(String value, String description) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> systemConfigService.put(
                "scope.sales-own-data-only", value, description));
    }

    @BeforeEach
    void bindTenant() {
        tenantId = "nf02-scope-" + UUID.randomUUID();
        AccountingTenantContextHolder.setTenantId(tenantId);
        previousRequestAttributes = RequestContextHolder.getRequestAttributes();
        testRequestAttributes = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(testRequestAttributes);
        originalSalesScope = systemConfigService.getString("scope.sales-own-data-only", "false");
    }

    @AfterEach
    void cleanupContextAndConfigCache() {
        commitSalesScopeConfig(originalSalesScope, "テスト後の設定復元");
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
        if (previousRequestAttributes == null) {
            RequestContextHolder.resetRequestAttributes();
        } else {
            RequestContextHolder.setRequestAttributes(previousRequestAttributes);
        }
    }

    @AfterTransaction
    void menuMasterChangeWasRolledBack() {
        if (menuRevocationObserved) {
            Long menuId = jdbc.queryForObject("SELECT id FROM m_menu WHERE menu_key='service-desk'", Long.class);
            Integer restored = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_role_menu WHERE role='営業' AND menu_id=?", Integer.class, menuId);
            assertThat(restored).isEqualTo(1);
        }
    }

    @Test
    void manager組織移動sales担当解除role変更とmenu撤回を現行DBscopeで拒否する() {
        String suffix = UUID.randomUUID().toString();
        Customer customer = Customer.builder().companyName("NF02-scope-" + suffix).build();
        customer.setTenantId(tenantId);
        customerMapper.insert(customer);
        OrganizationUnit organizationA = organization("NF02-A-" + suffix);
        OrganizationUnit organizationB = organization("NF02-B-" + suffix);
        organizationMapper.insert(organizationA);
        organizationMapper.insert(organizationB);
        Engineer engineer = Engineer.builder().tenantId(tenantId).fullName("NF02-scope-engineer-" + suffix)
                .employmentType("正社員").status("稼動中").organizationId(organizationA.getId()).build();
        engineerMapper.insert(engineer);
        Project project = new Project();
        project.setProjectName("NF02-scope-project-" + suffix);
        project.setCustomerId(customer.getId());
        projectMapper.insert(project);

        SysUser manager = user("nf02-manager-" + suffix, "マネージャー");
        SysUser sales = user("nf02-sales-" + suffix, "営業");
        SysUser ineligibleOwner = user("nf02-hr-" + suffix, "HR");
        assign(manager, organizationA.getId());
        Contract contract = contract(customer, project, engineer, null, suffix);
        ServiceRequest managerRequest = request(customer, manager, suffix + "-manager");

        bindRecipient(manager);
        assertThat(customerScopeResolver.resolve(LocalDate.now())).containsExactly(customer.getId());
        Object cachedManagerScope = RequestContextHolder.currentRequestAttributes().getAttribute(
                "com.ses.service.security.impl.OrganizationScopeServiceImpl.CACHE", RequestAttributes.SCOPE_REQUEST);
        assertThat(cachedManagerScope).isNotNull();
        assertThat(service.resolveNotificationRecipients(managerRequest)).containsExactly(manager.getId());
        userOrganizationMapper.update(null, new UpdateWrapper<UserOrganization>()
                .eq("user_id", manager.getId()).isNull("valid_to")
                .set("valid_to", LocalDate.now().minusDays(1)));
        assign(manager, organizationB.getId());
        assertThat(service.resolveNotificationRecipients(managerRequest)).isEmpty();
        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(testRequestAttributes);
        SecurityContextHolder.clearContext();

        commitSalesScopeConfig("true", "NF02通知先scope回帰テスト");
        assertThat(systemConfigService.getString("scope.sales-own-data-only", "false")).isEqualTo("true");
        updateContractSalesOwner(contract, sales.getId());
        ServiceRequest salesRequest = request(customer, sales, suffix + "-sales");
        bindRecipient(sales);
        assertThat(com.ses.common.util.SecurityUtils.currentUserId()).isEqualTo(sales.getId());
        assertThat(customerScopeResolver.resolve(LocalDate.now())).contains(customer.getId());
        assertThat(service.resolveNotificationRecipients(salesRequest)).containsExactly(sales.getId());

        updateContractSalesOwner(contract, ineligibleOwner.getId());
        testRequestAttributes = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(testRequestAttributes);
        assertThat(jdbc.queryForObject("SELECT sales_user_id FROM t_contract WHERE id=?", Long.class,
                contract.getId())).isEqualTo(ineligibleOwner.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_proposal WHERE proposed_by=?", Integer.class,
                sales.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_engineer_sales WHERE sales_user_id=?", Integer.class,
                sales.getId())).isZero();
        assertThat(dataScopeService.allowedCustomerIds()).isEmpty();
        assertThat(customerScopeResolver.resolve(LocalDate.now())).isEmpty();

        // 管理者callerは顧客全体を参照できるが、候補営業は本人の担当範囲で判定する。
        SysUser adminActor = user("nf02-admin-" + suffix, "管理者");
        bindRecipient(adminActor);
        RequestAttributes callerAttributes = RequestContextHolder.getRequestAttributes();
        assertThat(service.resolveNotificationRecipients(salesRequest)).containsExactly(adminActor.getId());
        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(callerAttributes);
        assertThat(com.ses.common.util.SecurityUtils.currentUserId()).isEqualTo(adminActor.getId());
        updateContractSalesOwner(contract, sales.getId());
        assertThat(service.resolveNotificationRecipients(salesRequest)).containsExactly(sales.getId());
        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(callerAttributes);
        assertThat(com.ses.common.util.SecurityUtils.currentUserId()).isEqualTo(adminActor.getId());

        userMapper.update(null, new UpdateWrapper<SysUser>().eq("id", sales.getId()).set("role", "HR"));
        assertThat(service.resolveNotificationRecipients(salesRequest)).containsExactly(adminActor.getId());
        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(callerAttributes);
        assertThat(com.ses.common.util.SecurityUtils.currentUserId()).isEqualTo(adminActor.getId());
        userMapper.update(null, new UpdateWrapper<SysUser>().eq("id", sales.getId()).set("role", "営業"));
        assertThat(service.resolveNotificationRecipients(salesRequest)).containsExactly(sales.getId());
        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(callerAttributes);
        assertThat(com.ses.common.util.SecurityUtils.currentUserId()).isEqualTo(adminActor.getId());

        Long menuId = jdbc.queryForObject("SELECT id FROM m_menu WHERE menu_key='service-desk'", Long.class);
        assertThat(roleMenuMapper.selectMenuKeysByRole("営業")).contains("service-desk");
        assertThat(service.resolveNotificationRecipients(salesRequest)).containsExactly(sales.getId());
        roleMenuMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RoleMenu>()
                .eq("role", "営業").eq("menu_id", menuId));
        menuRevocationObserved = true;
        assertThat(service.resolveNotificationRecipients(salesRequest)).containsExactly(adminActor.getId());
        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(callerAttributes);
        assertThat(com.ses.common.util.SecurityUtils.currentUserId()).isEqualTo(adminActor.getId());
    }

    private OrganizationUnit organization(String code) {
        return OrganizationUnit.builder().tenantId(1L).legalEntityId(981001L)
                .code(code).name(code).type("部門").validFrom(LocalDate.now().minusYears(1))
                .status("有効").version(0).build();
    }

    private SysUser user(String username, String role) {
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setPassword("test-password");
        user.setRole(role);
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    private void bindRecipient(SysUser recipient) {
        LoginUser principal = new LoginUser(recipient,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + recipient.getRole())), tenantId);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    private void assign(SysUser manager, Long organizationId) {
        userOrganizationMapper.insert(UserOrganization.builder().tenantId(tenantId)
                .userId(manager.getId()).organizationId(organizationId).primaryFlag(1)
                .validFrom(LocalDate.now().minusYears(1)).build());
    }

    private Contract contract(Customer customer, Project project, Engineer engineer,
                              SysUser sales, String suffix) {
        Contract contract = new Contract();
        contract.setTenantId(tenantId);
        contract.setContractNo("NF02-SCOPE-" + suffix);
        contract.setCustomerId(customer.getId());
        contract.setProjectId(project.getId());
        contract.setEngineerId(engineer.getId());
        contract.setSalesUserId(sales == null ? null : sales.getId());
        contract.setSellingPrice(new BigDecimal("800000"));
        contract.setCostPrice(new BigDecimal("600000"));
        contract.setStartDate(LocalDate.now().minusDays(1));
        contract.setEndDate(LocalDate.now().plusMonths(2));
        contract.setStatus("稼動中");
        contractMapper.insert(contract);
        return contract;
    }

    private void updateContractSalesOwner(Contract contract, Long salesUserId) {
        contractMapper.update(null, new UpdateWrapper<Contract>()
                .eq("id", contract.getId()).set("sales_user_id", salesUserId));
    }

    private ServiceRequest request(Customer customer, SysUser owner, String suffix) {
        ServiceRequest request = new ServiceRequest();
        request.setTenantId(tenantId);
        request.setRequestNo("REQ-NF02-" + suffix);
        request.setCustomerId(customer.getId());
        request.setOwnerUserId(owner.getId());
        request.setStatus("IN_PROGRESS");
        request.setPriority("P1");
        request.setCategory("QUALITY");
        request.setChannel("INTERNAL");
        request.setSubject("private-" + suffix);
        request.setDescription("private detail");
        requestMapper.insert(request);
        return request;
    }
}
