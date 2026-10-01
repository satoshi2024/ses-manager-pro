package com.ses.integration;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.BaseIntegrationTest;
import com.ses.config.LoginUser;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.Project;
import com.ses.entity.SysUser;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.WorkRecordService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 勤怠の一覧・月次締め・本人導線が同じ契約顧客ownershipを使うことを検証する。 */
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
class WorkRecordTenantIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private ContractMapper contractMapper;
    @Autowired
    private WorkRecordMapper workRecordMapper;
    @Autowired
    private EngineerAccountLinkMapper accountLinkMapper;
    @Autowired
    private com.ses.mapper.SysUserMapper sysUserMapper;
    @Autowired
    private WorkRecordService workRecordService;

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
    void 管理一覧と承認滞留は現在tenantだけを母集団にする() throws Exception {
        mockMvc.perform(get("/api/work-records/grid")
                        .param("month", tenantA.workMonth()).with(asUser(tenantA, "管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].contractId").value(tenantA.contractId()));

        mockMvc.perform(get("/api/work-records/grid/page")
                        .param("month", tenantA.workMonth()).param("current", "1").param("size", "20")
                        .with(asUser(tenantA, "管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].contractId").value(tenantA.contractId()));

        mockMvc.perform(get("/api/work-records/pending-approval-summary")
                        .param("month", tenantA.workMonth()).with(asUser(tenantA, "管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submittedCount").value(1))
                .andExpect(jsonPath("$.data.items[0].contractId").value(tenantA.contractId()));
    }

    @Test
    void 月次締めsummaryも現在tenantだけを母集団にし内部entityを公開しない() throws Exception {
        mockMvc.perform(get("/api/monthly-closing/summary")
                        .param("month", tenantA.workMonth()).with(asUser(tenantA, "管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unconfirmedCount").value(1))
                .andExpect(jsonPath("$.data.unconfirmedRecords[0].contractId").value(tenantA.contractId()))
                .andExpect(jsonPath("$.data.unconfirmedRecords[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$.data.unconfirmedRecords[0].paymentAmount").doesNotExist());
    }

    @Test
    void confirmMonthは他tenantの勤怠を変更しない() {
        AccountingTenantContextHolder.runWithTenant("tenant-a", () -> workRecordService.confirmMonth(tenantA.workMonth()));

        WorkRecord a = workRecordMapper.selectByIdForTenant(tenantA.workRecordId(), "tenant-a");
        WorkRecord b = workRecordMapper.selectByIdForTenant(tenantB.workRecordId(), "tenant-b");
        assertThat(a.getStatus()).isEqualTo("確定");
        assertThat(b.getStatus()).isEqualTo("提出済");
    }

    @Test
    void 本人勤怠は認証tenantと本人linkの共通境界を使う() throws Exception {
        mockMvc.perform(get("/api/my/timesheet")
                        .param("month", tenantA.workMonth()).with(asUser(tenantA, "要員")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].contractId").value(tenantA.contractId()));
    }

    @Test
    void NULLまたは契約顧客tenant不一致の勤怠は不可視() {
        Customer wrongCustomer = customer("tenant-b", "不一致顧客");
        Contract mismatch = contract(tenantA, wrongCustomer.getId(), "不一致");
        WorkRecord mismatchRecord = workRecord(mismatch, "提出済");

        Customer unknownCustomer = customer(null, "未解決顧客");
        Contract unknown = contract(tenantA, unknownCustomer.getId(), "NULL契約");
        unknown.setTenantId(null);
        contractMapper.updateById(unknown);
        WorkRecord unknownRecord = workRecord(unknown, "提出済");

        assertThat(workRecordMapper.selectByIdForTenant(mismatchRecord.getId(), "tenant-a")).isNull();
        assertThat(workRecordMapper.selectByIdForTenant(unknownRecord.getId(), "tenant-a")).isNull();
        assertThat(workRecordMapper.selectMonthlyGrid(tenantA.workMonth(),
                YearMonth.parse(tenantA.workMonth()).atEndOfMonth().toString(), "tenant-a"))
                .extracting(com.ses.dto.WorkRecordGridDto::getContractId)
                .containsExactly(tenantA.contractId());
    }

    @Test
    void 契約の要員案件営業ownership不一致は全ての勤怠母集団から除外する() {
        Contract engineerMismatch = contractWithReferences(tenantA, tenantA.customerId(),
                tenantB.engineerId(), tenantA.projectId(), null, "要員tenant不一致");
        Contract projectMismatch = contractWithReferences(tenantA, tenantA.customerId(),
                tenantA.engineerId(), tenantB.projectId(), null, "案件customer不一致");
        Contract salesMismatch = contractWithReferences(tenantA, tenantA.customerId(),
                tenantA.engineerId(), tenantA.projectId(), tenantB.user().getId(), "営業tenant不一致");
        workRecord(engineerMismatch, "提出済");
        workRecord(projectMismatch, "提出済");
        workRecord(salesMismatch, "提出済");

        String tenant = tenantA.tenantId();
        String monthEnd = YearMonth.parse(tenantA.workMonth()).atEndOfMonth().toString();
        assertThat(workRecordMapper.selectMonthlyGrid(tenantA.workMonth(), monthEnd, tenant))
                .extracting(com.ses.dto.WorkRecordGridDto::getContractId)
                .containsExactly(tenantA.contractId());
        assertThat(workRecordMapper.selectMonthlyGridPage(new Page<>(1, 20),
                tenantA.workMonth(), monthEnd, null, null, tenant).getTotal()).isEqualTo(1);
        assertThat(workRecordMapper.selectPendingApprovalPage(new Page<>(1, 20),
                tenantA.workMonth(), monthEnd, tenant).getTotal()).isEqualTo(1);
        assertThat(workRecordMapper.selectMonthlyGridForEngineer(tenantB.engineerId(), tenantA.workMonth(), monthEnd, tenant))
                .isEmpty();
        assertThat(contractMapper.selectByIdForTenant(engineerMismatch.getId(), tenant)).isNull();
        assertThat(contractMapper.selectByIdForTenant(projectMismatch.getId(), tenant)).isNull();
        assertThat(contractMapper.selectByIdForTenant(salesMismatch.getId(), tenant)).isNull();
    }

    private Fixture fixture(String tenantId, String label) {
        SysUser user = new SysUser();
        user.setUsername("wr-" + tenantId);
        user.setPassword("password");
        user.setRealName("勤怠" + label);
        user.setRole("要員");
        user.setTenantId(tenantId);
        user.setStatus(1);
        // HTTPテストのprincipalに使うだけであり、認証ユーザーのtenantはDBにも保存する。
        sysUserMapper.insert(user);
        Customer customer = customer(tenantId, "顧客" + label);
        Project project = new Project();
        project.setCustomerId(customer.getId());
        project.setLegalEntityId(1L);
        project.setCreatedBy(user.getId());
        project.setProjectName("案件" + label);
        project.setStatus("募集中");
        projectMapper.insert(project);

        Engineer engineer = new Engineer();
        engineer.setTenantId(tenantId);
        engineer.setLegalEntityId(1L);
        engineer.setFullName("要員" + label);
        engineer.setEmploymentType("正社員");
        engineer.setStatus("稼動中");
        engineerMapper.insert(engineer);

        String month = YearMonth.now().minusMonths(1).toString();
        Contract contract = new Contract();
        contract.setTenantId(tenantId);
        contract.setLegalEntityId(1L);
        contract.setContractNo("WR-" + tenantId);
        contract.setCustomerId(customer.getId());
        contract.setProjectId(project.getId());
        contract.setEngineerId(engineer.getId());
        contract.setContractType("準委任");
        contract.setStatus("稼動中");
        contract.setStartDate(YearMonth.parse(month).atDay(1).minusMonths(1));
        contract.setSellingPrice(new BigDecimal("600000"));
        contract.setCostPrice(new BigDecimal("400000"));
        contractMapper.insert(contract);

        WorkRecord record = workRecord(contract, "提出済");
        // 本人導線のtenant-aware account link。userIdはHTTP principal用の安定したテストID。
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(tenantId);
        link.setEngineerId(engineer.getId());
        link.setSysUserId(user.getId());
        accountLinkMapper.insert(link);
        return new Fixture(tenantId, month, user, customer.getId(), project.getId(), engineer.getId(),
                contract.getId(), record.getId());
    }

    private Customer customer(String tenantId, String name) {
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setLegalEntityId(1L);
        customer.setCompanyName(name);
        customerMapper.insert(customer);
        return customer;
    }

    private Contract contract(Fixture owner, Long customerId, String label) {
        Project project = new Project();
        project.setCustomerId(customerId);
        project.setLegalEntityId(1L);
        project.setCreatedBy(owner.user().getId());
        project.setProjectName("案件" + owner.tenantId() + "-" + label);
        project.setStatus("募集中");
        projectMapper.insert(project);
        Contract contract = new Contract();
        contract.setTenantId(owner.tenantId());
        contract.setLegalEntityId(1L);
        contract.setContractNo("WR-" + owner.tenantId() + "-" + label);
        contract.setCustomerId(customerId);
        contract.setProjectId(project.getId());
        contract.setEngineerId(owner.engineerId());
        contract.setContractType("準委任");
        contract.setStatus("稼動中");
        contract.setStartDate(YearMonth.parse(owner.workMonth()).atDay(1).minusMonths(1));
        contract.setSellingPrice(new BigDecimal("600000"));
        contract.setCostPrice(new BigDecimal("400000"));
        contractMapper.insert(contract);
        return contract;
    }

    private Contract contractWithReferences(Fixture owner, Long customerId, Long engineerId,
                                            Long projectId, Long salesUserId, String label) {
        Contract contract = new Contract();
        contract.setTenantId(owner.tenantId());
        contract.setLegalEntityId(1L);
        contract.setContractNo("WR-" + owner.tenantId() + "-" + label);
        contract.setCustomerId(customerId);
        contract.setProjectId(projectId);
        contract.setEngineerId(engineerId);
        contract.setSalesUserId(salesUserId);
        contract.setContractType("準委任");
        contract.setStatus("稼動中");
        contract.setStartDate(YearMonth.parse(owner.workMonth()).atDay(1).minusMonths(1));
        contract.setSellingPrice(new BigDecimal("600000"));
        contract.setCostPrice(new BigDecimal("400000"));
        contractMapper.insert(contract);
        return contract;
    }

    private WorkRecord workRecord(Contract contract, String status) {
        WorkRecord record = new WorkRecord();
        record.setContractId(contract.getId());
        record.setWorkMonth(YearMonth.now().minusMonths(1).toString());
        record.setActualHours(new BigDecimal("160"));
        record.setBillingAmount(new BigDecimal("600000"));
        record.setPaymentAmount(new BigDecimal("400000"));
        record.setStatus(status);
        workRecordMapper.insert(record);
        return record;
    }

    private RequestPostProcessor asUser(Fixture fixture, String role) {
        SysUser principalUser = new SysUser();
        principalUser.setId(fixture.user().getId());
        principalUser.setUsername(fixture.user().getUsername());
        principalUser.setPassword(fixture.user().getPassword());
        principalUser.setRealName(fixture.user().getRealName());
        principalUser.setRole(role);
        principalUser.setTenantId(fixture.user().getTenantId());
        principalUser.setStatus(fixture.user().getStatus());
        LoginUser principal = new LoginUser(principalUser,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                principal.getAuthorities()));
    }

    private record Fixture(String tenantId, String workMonth, SysUser user, Long customerId,
                           Long projectId, Long engineerId, Long contractId, Long workRecordId) {
    }
}
