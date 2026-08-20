package com.ses.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.Quotation;
import com.ses.entity.SalesOrder;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.mapper.SalesOrderMapper;
import com.ses.service.SalesOrderService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

/**
 * S09-P1-01: 同一見積からの注文生成を実MySQL 2txで競合させ、
 * uk_sales_order_quotation を最終防衛線として1行だけ保持することを固定する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SalesOrderConcurrentCreateTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_order_quotation")
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

    @Autowired SalesOrderService salesOrderService;
    @Autowired CustomerMapper customerMapper;
    @Autowired ProjectMapper projectMapper;
    @Autowired EngineerMapper engineerMapper;
    @Autowired QuotationMapper quotationMapper;
    @Autowired SalesOrderMapper salesOrderMapper;

    @Test
    void 同一見積の2transactionは同じ注文を返しDBは1行だけ保持する() throws Exception {
        Long quotationId = fixtureQuotationId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        List<SalesOrder> results;
        try {
            var first = executor.submit(() -> create(quotationId, ready, start));
            var second = executor.submit(() -> create(quotationId, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(results.get(0).getId(), results.get(1).getId(), "両txがDB上の勝者を返すこと");
        assertEquals(1L, salesOrderMapper.selectCount(new LambdaQueryWrapper<SalesOrder>()
                .eq(SalesOrder::getQuotationId, quotationId)), "1見積1注文であること");
    }

    private SalesOrder create(Long quotationId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return salesOrderService.createDraftFromQuotation(quotationId);
    }

    private Long fixtureQuotationId() {
        long nonce = System.nanoTime();
        Customer customer = new Customer();
        customer.setCompanyName("order-quotation-customer-" + nonce);
        customerMapper.insert(customer);

        Project project = new Project();
        project.setProjectName("order-quotation-project-" + nonce);
        project.setCustomerId(customer.getId());
        project.setStatus("募集中");
        projectMapper.insert(project);

        Engineer engineer = Engineer.builder()
                .fullName("order-quotation-engineer-" + nonce)
                .employmentType("正社員")
                .status("Bench")
                .build();
        engineerMapper.insert(engineer);

        Quotation quotation = new Quotation();
        quotation.setQuotationNo("Q-CONCURRENT-" + nonce);
        quotation.setCustomerId(customer.getId());
        quotation.setProjectId(project.getId());
        quotation.setEngineerId(engineer.getId());
        quotation.setTitle("concurrent-quotation-" + nonce);
        quotation.setUnitPrice(new BigDecimal("600000"));
        quotation.setSettlementHoursMin(new BigDecimal("140.0"));
        quotation.setSettlementHoursMax(new BigDecimal("180.0"));
        quotation.setStatus("受注");
        quotation.setValidUntil(LocalDate.now().plusMonths(1));
        quotationMapper.insert(quotation);
        return quotation.getId();
    }
}
