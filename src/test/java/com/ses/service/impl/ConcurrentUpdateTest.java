package com.ses.service.impl;

import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.WorkRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 実DB(MySQL Testcontainers)を使用した並行処理テスト。
 * ロックやCASの実効性を検証する（RC-06対応）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentUpdateTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("ses")
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
    private WorkRecordService workRecordService;
    @Autowired
    private WorkRecordMapper workRecordMapper;
    @Autowired
    private ContractMapper contractMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private ProjectMapper projectMapper;

    @Test
    void testConcurrentApproveAndReject() throws InterruptedException {
        Customer customer = new Customer();
        customer.setCompanyName("Test Customer");
        customerMapper.insert(customer);

        Project project = new Project();
        project.setProjectName("Test Project");
        project.setCustomerId(customer.getId());
        projectMapper.insert(project);

        Engineer engineer = Engineer.builder().fullName("Test Engineer").build();
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

        WorkRecord record = new WorkRecord();
        record.setContractId(contract.getId());
        record.setWorkMonth("2026-07");
        record.setStatus("提出済");
        workRecordMapper.insert(record);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        Runnable approveTask = () -> {
            try {
                ready.countDown();
                start.await();
                workRecordService.approve(record.getId());
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        };

        Runnable rejectTask = () -> {
            try {
                ready.countDown();
                start.await();
                workRecordService.reject(record.getId(), "Reject reason");
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        };

        executor.submit(approveTask);
        executor.submit(rejectTask);

        ready.await();
        start.countDown(); 
        done.await(10, TimeUnit.SECONDS);

        // 1つだけが成功し、もう1つはCAS失敗(409)でBusinessExceptionを投げるはず
        assertEquals(1, successCount.get(), "1つの操作のみ成功するべき");
        assertEquals(1, failCount.get(), "1つの操作はCASによって失敗するべき");
    }
}
