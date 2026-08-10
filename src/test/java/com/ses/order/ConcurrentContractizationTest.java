package com.ses.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.SalesOrder;
import com.ses.entity.SalesOrderLine;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SalesOrderLineMapper;
import com.ses.mapper.SalesOrderMapper;
import com.ses.service.ContractService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 同一注文明細の契約化を実MySQL 2txで競合させ、DB一意制約を最終防衛線として固定する。 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentContractizationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_contractization")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired ContractService contractService;
    @Autowired CustomerMapper customerMapper;
    @Autowired ProjectMapper projectMapper;
    @Autowired EngineerMapper engineerMapper;
    @Autowired SalesOrderMapper salesOrderMapper;
    @Autowired SalesOrderLineMapper salesOrderLineMapper;
    @Autowired ContractMapper contractMapper;

    @Test
    void 同一注文明細の2transactionは同じ契約を返しDBは1行だけ保持する() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        List<Contract> results;
        try {
            var first = executor.submit(() -> contractize(fixture, ready, start));
            var second = executor.submit(() -> contractize(fixture, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(results.get(0).getId(), results.get(1).getId(), "両txがDB上の勝者を返すこと");
        assertEquals(1L, contractMapper.selectCount(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getOrderLineId, fixture.line().getId())), "1明細1契約であること");
    }

    private Contract contractize(Fixture fixture, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return contractService.createDraftFromSalesOrderLine(fixture.line(), fixture.order());
    }

    private Fixture fixture() {
        long nonce = System.nanoTime();
        Customer customer = new Customer();
        customer.setCompanyName("contractization-customer-" + nonce);
        customerMapper.insert(customer);

        Project project = new Project();
        project.setProjectName("contractization-project-" + nonce);
        project.setCustomerId(customer.getId());
        project.setStatus("募集中");
        projectMapper.insert(project);

        Engineer engineer = Engineer.builder()
                .fullName("contractization-engineer-" + nonce)
                .employmentType("正社員")
                .status("Bench")
                .build();
        engineerMapper.insert(engineer);

        SalesOrder order = new SalesOrder();
        order.setOrderNo("SO-CONCURRENT-" + nonce);
        order.setCustomerId(customer.getId());
        order.setOrderDate(LocalDate.now());
        order.setStatus("注文請提出");
        salesOrderMapper.insert(order);

        SalesOrderLine line = new SalesOrderLine();
        line.setOrderId(order.getId());
        line.setLineNo(1);
        line.setProjectId(project.getId());
        line.setEngineerId(engineer.getId());
        line.setQuantity(1);
        line.setUnitPrice(new BigDecimal("600000"));
        line.setAmount(new BigDecimal("600000"));
        salesOrderLineMapper.insert(line);
        return new Fixture(order, line);
    }

    private record Fixture(SalesOrder order, SalesOrderLine line) {
    }
}
