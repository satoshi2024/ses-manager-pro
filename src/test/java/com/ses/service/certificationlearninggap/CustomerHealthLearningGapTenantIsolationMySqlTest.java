package com.ses.service.certificationlearninggap;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.config.LoginUser;
import com.ses.dto.certificationlearninggap.CertificationLearningGapFilter;
import com.ses.dto.certificationlearninggap.CertificationLearningGapRow;
import com.ses.dto.servicedesk.CustomerHealthScoreDto;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.Project;
import com.ses.entity.ServiceRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.servicedesk.CustomerHealthService;
import com.ses.service.servicedesk.impl.ServiceSlaMonitoringServiceImpl;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実MySQLでCustomer Healthと資格・学習gapのtenant母集団を検証する。 */
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class CustomerHealthLearningGapTenantIsolationMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_customer_health_tenant")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private CustomerHealthService customerHealthService;

    @Autowired
    private CertificationLearningGapQueryService queryService;

    @Autowired
    private EngineerAccountLinkService linkService;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private ServiceSlaMonitoringServiceImpl slaMonitoringService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void MySQLでnoRequest顧客と資格母集団をtenant単位で分離する() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String tenantA = "tenant-a-" + suffix;
        String tenantB = "tenant-b-" + suffix;
        Customer customerA = insertCustomer(tenantA, "MySQL顧客A-" + suffix);
        Customer customerB = insertCustomer(tenantB, "MySQL顧客B-" + suffix);
        Engineer engineerA = insertEngineer(tenantA, "MySQL要員A-" + suffix);
        Engineer engineerB = insertEngineer(tenantB, "MySQL要員B-" + suffix);
        SysUser userA = insertEngineerUser(tenantA, "MySQL本人A-" + suffix);

        authenticateAsAdmin(tenantA);
        AccountingTenantContextHolder.setTenantId(tenantA);

        List<CustomerHealthScoreDto> health = customerHealthService.listCustomerHealthSummaries(null, null);
        assertThat(health).extracting(CustomerHealthScoreDto::getCustomerId)
                .containsExactly(customerA.getId());
        assertThatThrownBy(() -> customerHealthService.calculateCustomerHealth(customerB.getId()))
                .isInstanceOf(com.ses.common.exception.BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 404);

        CertificationLearningGapFilter filter = new CertificationLearningGapFilter(
                null, null, null, null, null, LocalDate.now(), null, null);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Page<CertificationLearningGapRow> page = queryService.page(filter, 1, 100, authentication);
        assertThat(page.getRecords()).extracting(CertificationLearningGapRow::engineerId)
                .containsExactly(engineerA.getId());
        assertThat(queryService.count(filter, authentication)).isEqualTo(1);
        assertThatThrownBy(() -> queryService.detail(engineerB.getId(), filter, authentication))
                .isInstanceOf(com.ses.common.exception.BusinessException.class);

        EngineerAccountLink created = linkService.link(engineerA.getId(), userA.getId(), null);
        assertThat(created.getTenantId()).isEqualTo(tenantA);
        assertThat(linkService.findEngineerIdByUserId(userA.getId())).isEqualTo(engineerA.getId());
    }

    @Test
    void MySQLのSLA通知契約候補は顧客と契約の両方がcurrentTenantの行だけを使う() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String tenantA = "sla-a-" + suffix;
        String tenantB = "sla-b-" + suffix;
        Customer customerA = insertCustomer(tenantA, "SLA顧客A-" + suffix);
        Engineer engineerA = insertEngineer(tenantA, "SLA要員A-" + suffix);
        Project projectA = new Project();
        projectA.setCustomerId(customerA.getId());
        projectA.setProjectName("SLA案件A-" + suffix);
        projectMapper.insert(projectA);
        SysUser salesA = insertSalesUser(tenantA, "sla-sales-a-" + suffix);
        SysUser salesB = insertSalesUser(tenantB, "sla-sales-b-" + suffix);
        Contract contractA = insertContract(customerA, projectA, engineerA, salesA, tenantA);
        Contract contractB = insertContract(customerA, projectA, engineerA, salesB, tenantB);

        authenticateAsAdmin(tenantA);
        AccountingTenantContextHolder.setTenantId(tenantA);
        ServiceRequest request = ServiceRequest.builder().tenantId(tenantA).customerId(customerA.getId())
                .contractId(contractB.getId()).status("RECEIVED").build();

        assertThat(slaMonitoringService.resolveNotificationRecipients(request))
                .isEmpty();
        assertThat(contractMapper.selectByIdForTenant(contractB.getId(), tenantA)).isNull();
        assertThat(contractMapper.selectByIdForTenant(contractA.getId(), tenantA)).isNotNull();
    }

    private Customer insertCustomer(String tenantId, String name) {
        Customer customer = Customer.builder().tenantId(tenantId).companyName(name).build();
        customerMapper.insert(customer);
        return customer;
    }

    private Engineer insertEngineer(String tenantId, String name) {
        Engineer engineer = Engineer.builder().tenantId(tenantId).fullName(name)
                .status("稼動中").employmentType("正社員").build();
        engineerMapper.insert(engineer);
        return engineer;
    }

    private SysUser insertEngineerUser(String tenantId, String name) {
        SysUser user = SysUser.builder().tenantId(tenantId)
                .username("nf03-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20))
                .password("test").realName(name).role("要員").status(1).build();
        sysUserMapper.insert(user);
        return user;
    }

    private SysUser insertSalesUser(String tenantId, String username) {
        SysUser user = SysUser.builder().tenantId(tenantId).username(username)
                .password("test").realName(username).role("営業").status(1).build();
        sysUserMapper.insert(user);
        return user;
    }

    private Contract insertContract(Customer customer, Project project, Engineer engineer,
                                    SysUser salesUser, String tenantId) {
        Contract contract = new Contract();
        contract.setTenantId(tenantId);
        contract.setContractNo("CT-SLA-" + UUID.randomUUID());
        contract.setCustomerId(customer.getId());
        contract.setProjectId(project.getId());
        contract.setEngineerId(engineer.getId());
        contract.setSalesUserId(salesUser.getId());
        contract.setContractType("準委任");
        contract.setStartDate(LocalDate.now().minusDays(1));
        contract.setEndDate(LocalDate.now().plusMonths(1));
        contract.setSellingPrice(java.math.BigDecimal.valueOf(800000));
        contract.setCostPrice(java.math.BigDecimal.valueOf(600000));
        contract.setStatus("稼動中");
        contractMapper.insert(contract);
        return contract;
    }

    private void authenticateAsAdmin(String tenantId) {
        SysUser admin = SysUser.builder().username("mysql-admin-" + tenantId)
                .tenantId(tenantId).role("管理者").status(1).build();
        LoginUser principal = new LoginUser(admin,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
