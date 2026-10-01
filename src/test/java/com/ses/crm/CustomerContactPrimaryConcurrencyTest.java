package com.ses.crm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.customer.CustomerContactSaveRequest;
import com.ses.entity.Customer;
import com.ses.entity.CustomerContact;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.service.CustomerContactService;
import com.ses.test.EnableDefaultTenantTestContext;
import com.ses.test.TenantTestSecurity;
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

import java.time.LocalDate;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 有限期間の主担当を空顧客へ同時作成した場合の親行ロックを実MySQLで検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
@EnableDefaultTenantTestContext
class CustomerContactPrimaryConcurrencyTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_crm_contact_concurrency")
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
    private CustomerContactService customerContactService;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private CustomerContactMapper customerContactMapper;

    @Test
    void simultaneousFinitePrimaryCreationAllowsOnlyOneWriter() throws Exception {
        Customer customer = new Customer();
        customer.setCompanyName("同時主担当検証社");
        customer.setTenantId("default");
        customer.setLegalEntityId(1L);
        customerMapper.insert(customer);

        CustomerContactSaveRequest request = new CustomerContactSaveRequest();
        request.setName("同時主担当");
        request.setStatus("有効");
        request.setPrimaryFlag(1);
        request.setValidFrom(LocalDate.now().minusDays(1));
        request.setValidTo(LocalDate.now().plusDays(30));

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        Runnable createTask = () -> {
            try {
                setAdminContext();
                ready.countDown();
                start.await();
                customerContactService.create(customer.getId(), request);
                successCount.incrementAndGet();
            } catch (Throwable e) {
                failures.add(e);
            } finally {
                TenantTestSecurity.clear();
                done.countDown();
            }
        };

        executor.submit(createTask);
        executor.submit(createTask);
        assertTrue(ready.await(10, TimeUnit.SECONDS), "2 transactionが開始待ちになるはず: " + failures);
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "2 transactionが完了するはず");
        executor.shutdownNow();

        assertEquals(1, successCount.get(), "同時作成では主担当1件だけが成功するはず: " + failures);
        assertEquals(1, failures.size(), "重複期間側は明示的に失敗するはず: " + failures);
        BusinessException overlap = assertInstanceOf(BusinessException.class, failures.peek());
        assertEquals("error.crm.primaryContactOverlap", overlap.getMessageKey());
        assertEquals(1, customerContactMapper.selectCount(new LambdaQueryWrapper<CustomerContact>()
                .eq(CustomerContact::getCustomerId, customer.getId())),
                "失敗transactionはcontactを残してはいけない");
    }

    private void setAdminContext() {
        TenantTestSecurity.bindAs(1L, "admin", "default", "管理者");
    }
}
