package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.*;
import com.ses.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class ReferentialIntegrityGuardTest {

    @Autowired private EngineerService engineerService;
    @Autowired private CustomerService customerService;
    @Autowired private ProjectService projectService;
    @Autowired private ContractService contractService;
    @Autowired private ProposalService proposalService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private WorkRecordService workRecordService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Engineer createEngineer() {
        Engineer e = new Engineer();
        e.setFullName("Test Engineer");
        e.setEmploymentType("正社員");
        e.setStatus("待機中");
        engineerService.save(e);
        return e;
    }

    private Customer createCustomer() {
        Customer c = new Customer();
        c.setCompanyName("Test Customer");
        customerService.save(c);
        return c;
    }

    private Project createProject(Long customerId) {
        Project p = new Project();
        p.setProjectName("Test Project");
        p.setCustomerId(customerId);
        projectService.save(p);
        return p;
    }

    private Contract createContract(Long engineerId, Long projectId, Long customerId, String status) {
        Contract c = new Contract();
        c.setEngineerId(engineerId);
        c.setProjectId(projectId);
        c.setCustomerId(customerId);
        c.setStatus(status);
        c.setStartDate(LocalDate.now());
        contractService.saveWithBusinessRules(c);
        return c;
    }

    private Proposal createProposal(Long engineerId, Long projectId, String status) {
        Proposal p = new Proposal();
        p.setEngineerId(engineerId);
        p.setProjectId(projectId);
        p.setStatus(status);
        p.setProposedAt(java.time.LocalDateTime.now());
        proposalService.save(p);
        return p;
    }

    private Invoice createInvoice(Long customerId, boolean deleted) {
        Invoice i = new Invoice();
        i.setInvoiceNo("INV-202607-0001");
        i.setCustomerId(customerId);
        i.setBillingMonth("2026-07");
        i.setStatus("未請求");
        i.setSubtotal(new java.math.BigDecimal("10000"));
        i.setTax(new java.math.BigDecimal("1000"));
        i.setTotal(new java.math.BigDecimal("11000"));
        invoiceService.save(i);
        if (deleted) {
            invoiceService.removeById(i.getId());
        }
        return i;
    }

    private WorkRecord createWorkRecord(Long contractId) {
        WorkRecord w = new WorkRecord();
        w.setContractId(contractId);
        w.setWorkMonth("2026-07");
        w.setActualHours(new java.math.BigDecimal("150"));
        workRecordService.save(w);
        return w;
    }

    // --- Engineer Tests ---
    @Test
    void engineerGuard_activeContract() {
        Engineer e = createEngineer();
        Customer c = createCustomer();
        Project p = createProject(c.getId());
        createContract(e.getId(), p.getId(), c.getId(), "稼動中");

        BusinessException ex = assertThrows(BusinessException.class, () -> engineerService.removeById(e.getId()));
        assertEquals("稼動中の契約があるため削除できません", ex.getMessage());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_engineer WHERE id = ? AND deleted_flag = 0", Integer.class, e.getId()));
    }

    @Test
    void engineerGuard_completedContractOnly() {
        Engineer e = createEngineer();
        Customer c = createCustomer();
        Project p = createProject(c.getId());
        createContract(e.getId(), p.getId(), c.getId(), "終了");

        assertTrue(engineerService.removeById(e.getId()));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_engineer WHERE id = ? AND deleted_flag = 0", Integer.class, e.getId()));
    }

    @Test
    void engineerGuard_openProposal() {
        Engineer e = createEngineer();
        Customer c = createCustomer();
        Project p = createProject(c.getId());
        createProposal(e.getId(), p.getId(), "書類選考中");

        BusinessException ex = assertThrows(BusinessException.class, () -> engineerService.removeById(e.getId()));
        assertEquals("進行中の提案があるため削除できません", ex.getMessage());
    }

    @Test
    void engineerGuard_rejectedProposalOnly() {
        Engineer e = createEngineer();
        Customer c = createCustomer();
        Project p = createProject(c.getId());
        createProposal(e.getId(), p.getId(), "見送り");

        assertTrue(engineerService.removeById(e.getId()));
    }

    // --- Customer Tests ---
    @Test
    void customerGuard_hasProject() {
        Customer c = createCustomer();
        createProject(c.getId());

        BusinessException ex = assertThrows(BusinessException.class, () -> customerService.removeById(c.getId()));
        assertTrue(ex.getMessage().contains("案件が"));
    }

    @Test
    void customerGuard_deletedInvoiceOnly() {
        Customer c = createCustomer();
        createInvoice(c.getId(), true);

        assertTrue(customerService.removeById(c.getId()));
    }

    @Test
    void customerGuard_noRelations() {
        Customer c = createCustomer();
        assertTrue(customerService.removeById(c.getId()));
    }

    // --- Project Tests ---
    @Test
    void projectGuard_hasContract() {
        Engineer e = createEngineer();
        Customer c = createCustomer();
        Project p = createProject(c.getId());
        createContract(e.getId(), p.getId(), c.getId(), "終了");

        BusinessException ex = assertThrows(BusinessException.class, () -> projectService.removeById(p.getId()));
        assertEquals("契約が紐づいているため削除できません", ex.getMessage());
    }

    // --- Contract Tests ---
    @Test
    void contractGuard_hasWorkRecord() {
        Engineer e = createEngineer();
        Customer c = createCustomer();
        Project p = createProject(c.getId());
        Contract contract = createContract(e.getId(), p.getId(), c.getId(), "終了");
        createWorkRecord(contract.getId());

        BusinessException ex = assertThrows(BusinessException.class, () -> contractService.removeById(contract.getId()));
        assertEquals("実績が登録されているため削除できません", ex.getMessage());
    }

    @Test
    void contractGuard_isActive() {
        Engineer e = createEngineer();
        Customer c = createCustomer();
        Project p = createProject(c.getId());
        Contract contract = createContract(e.getId(), p.getId(), c.getId(), "稼動中");

        BusinessException ex = assertThrows(BusinessException.class, () -> contractService.removeById(contract.getId()));
        assertEquals("稼動中の契約は削除できません。先に終了/解約へ変更してください", ex.getMessage());
    }

    @Test
    void contractGuard_completedAndNoWorkRecord() {
        Engineer e = createEngineer();
        Customer c = createCustomer();
        Project p = createProject(c.getId());
        Contract contract = createContract(e.getId(), p.getId(), c.getId(), "終了");

        assertTrue(contractService.removeById(contract.getId()));
    }
}
