package com.ses.crm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.customer.CustomerContactSaveRequest;
import com.ses.entity.Customer;
import com.ses.entity.CustomerContact;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.service.CustomerContactService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 有限期間の主担当を空顧客へ同時作成した場合の親行ロックを実MySQLで検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
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
        AtomicInteger failureCount = new AtomicInteger();

        Runnable createTask = () -> {
            try {
                setAdminContext();
                ready.countDown();
                start.await();
                customerContactService.create(customer.getId(), request);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            } finally {
                SecurityContextHolder.clearContext();
                done.countDown();
            }
        };

        executor.submit(createTask);
        executor.submit(createTask);
        assertTrue(ready.await(10, TimeUnit.SECONDS), "2 transactionが開始待ちになるはず");
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "2 transactionが完了するはず");
        executor.shutdownNow();

        assertEquals(1, successCount.get(), "同時作成では主担当1件だけが成功するはず");
        assertEquals(1, failureCount.get(), "重複期間側は明示的に失敗するはず");
        assertEquals(1, customerContactMapper.selectCount(new LambdaQueryWrapper<CustomerContact>()
                .eq(CustomerContact::getCustomerId, customer.getId())),
                "失敗transactionはcontactを残してはいけない");
    }

    private void setAdminContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
    }
}
