package com.ses.service.impl;

import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.ses.test.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACC-ARCH-P1-003: Engineer / Customer / WorkRecord の楽観ロックが実MySQLで効くことを検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class OptimisticLockCoreEntitiesMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
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
    private EngineerMapper engineerMapper;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private WorkRecordMapper workRecordMapper;
    @Autowired
    private ContractMapper contractMapper;
    @Autowired
    private ProjectMapper projectMapper;

    @Test
    void engineer_staleVersionの更新は失敗する() {
        Engineer inserted = Engineer.builder().fullName("楽観ロック要員").employmentType("正社員").status("Bench").build();
        engineerMapper.insert(inserted);

        Engineer first = engineerMapper.selectById(inserted.getId());
        Engineer second = engineerMapper.selectById(inserted.getId());
        assertEquals(0, first.getVersion());

        first.setRemarks("first-win");
        assertEquals(1, engineerMapper.updateById(first));

        second.setRemarks("stale-lose");
        int stale = engineerMapper.updateById(second);
        assertTrue(stale == 0, "古いversionのupdateは0件になるべき");

        Engineer reloaded = engineerMapper.selectById(inserted.getId());
        assertEquals("first-win", reloaded.getRemarks());
        assertEquals(1, reloaded.getVersion());
    }

    @Test
    void customer_staleVersionの更新は失敗する() {
        Customer inserted = Customer.builder().companyName("楽観ロック顧客").build();
        customerMapper.insert(inserted);

        Customer first = customerMapper.selectById(inserted.getId());
        Customer second = customerMapper.selectById(inserted.getId());

        first.setRemarks("first-win");
        assertEquals(1, customerMapper.updateById(first));

        second.setRemarks("stale-lose");
        assertEquals(0, customerMapper.updateById(second));

        Customer reloaded = customerMapper.selectById(inserted.getId());
        assertEquals("first-win", reloaded.getRemarks());
        assertEquals(1, reloaded.getVersion());
    }

    @Test
    void workRecord_staleVersionの更新は失敗する() {
        Customer customer = new Customer();
        customer.setCompanyName("WR顧客");
        customerMapper.insert(customer);

        Project project = new Project();
        project.setProjectName("WR案件");
        project.setCustomerId(customer.getId());
        projectMapper.insert(project);

        Engineer engineer = Engineer.builder().fullName("WR要員").employmentType("正社員").status("Bench").build();
        engineerMapper.insert(engineer);

        Contract contract = new Contract();
        contract.setEngineerId(engineer.getId());
        contract.setProjectId(project.getId());
        contract.setCustomerId(customer.getId());
        contract.setStartDate(java.time.LocalDate.now().minusMonths(1));
        contract.setStatus("稼動中");
        contract.setSellingPrice(new BigDecimal("500000"));
        contract.setCostPrice(new BigDecimal("300000"));
        contractMapper.insert(contract);

        WorkRecord inserted = new WorkRecord();
        inserted.setContractId(contract.getId());
        inserted.setWorkMonth("2026-08");
        inserted.setActualHours(new BigDecimal("160.0"));
        inserted.setStatus("入力中");
        workRecordMapper.insert(inserted);

        WorkRecord first = workRecordMapper.selectById(inserted.getId());
        WorkRecord second = workRecordMapper.selectById(inserted.getId());
        assertEquals(0, first.getVersion());

        first.setRemarks("first-win");
        assertEquals(1, workRecordMapper.updateById(first));

        second.setRemarks("stale-lose");
        assertEquals(0, workRecordMapper.updateById(second));

        WorkRecord reloaded = workRecordMapper.selectById(inserted.getId());
        assertEquals("first-win", reloaded.getRemarks());
        assertEquals(1, reloaded.getVersion());
    }
}
