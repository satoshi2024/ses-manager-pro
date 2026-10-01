package com.ses.service.invoice;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Customer;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.IntegrationJob;
import com.ses.entity.Invoice;
import com.ses.entity.PeppolParticipant;
import com.ses.service.CustomerService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.InvoiceService;
import com.ses.service.PeppolParticipantService;
import com.ses.service.integration.IntegrationJobService;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import com.ses.test.MySQLContainer;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * S16-P1-02: 同一 invoice の並行 enqueue は Standard SEND 1 行 + job 1 件、他方は 409。
 * クラスに @Transactional を付けない（並行スレッドが互いの INSERT を見えるようにする）。
 * 実DB(MySQL Testcontainers)で一意制約／CAS の実効性を検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DigitalInvoiceEnqueueConcurrentTest {

    @Container
    @SuppressWarnings("resource") // ライフサイクルは Testcontainers Extension が管理する。
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
    private DigitalInvoiceService digitalInvoiceService;

    @Autowired
    private PeppolParticipantService peppolParticipantService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private IntegrationJobService integrationJobService;

    @Autowired
    private InvoiceService invoiceService;

    @MockBean
    private DigitalInvoiceProvider digitalInvoiceProvider;

    @MockBean
    private com.ses.service.DocumentService documentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicReference<Long> customerIdRef = new AtomicReference<>();
    private final AtomicReference<Long> invoiceIdRef = new AtomicReference<>();

    @BeforeEach
    void setUpTenantScope() {
        TenantTestSecurity.bindAs("default", "管理者");
        TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L);
    }

    @AfterEach
    void cleanup() {
        // UNIQUE は論理削除では解放されないため物理削除する
        Long invoiceId = invoiceIdRef.getAndSet(null);
        if (invoiceId != null) {
            List<Long> diIds = jdbcTemplate.queryForList(
                    "SELECT id FROM t_digital_invoice WHERE invoice_id = ?", Long.class, invoiceId);
            for (Long diId : diIds) {
                jdbcTemplate.update(
                        "DELETE FROM t_integration_job WHERE target_type = 't_digital_invoice' AND target_id = ?",
                        diId);
            }
            jdbcTemplate.update("DELETE FROM t_digital_invoice WHERE invoice_id = ?", invoiceId);
            jdbcTemplate.update("DELETE FROM t_invoice WHERE id = ?", invoiceId);
        }
        Long customerId = customerIdRef.getAndSet(null);
        if (customerId != null) {
            jdbcTemplate.update(
                    "DELETE FROM t_peppol_participant WHERE owner_type = 'CUSTOMER' AND owner_id = ?",
                    customerId);
            jdbcTemplate.update("DELETE FROM m_customer WHERE id = ?", customerId);
        }
        TenantTestSecurity.clear();
    }

    @Test
    void concurrentEnqueue_keepsOneStandardSendAndOneJob_secondIs409() throws Exception {
        when(digitalInvoiceProvider.sendInvoice(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> "mock-provider-" + inv.getArgument(2));
        when(documentService.registerGenerated(any(), any())).thenAnswer(inv -> {
            com.ses.entity.Document doc = new com.ses.entity.Document();
            doc.setId(7101L);
            return doc;
        });

        Customer c = new Customer();
        c.setCompanyName("Concurrent Co " + UUID.randomUUID());
        c.setDeliveryPreference("PEPPOL");
        customerService.save(c);
        customerIdRef.set(c.getId());

        PeppolParticipant pp = new PeppolParticipant();
        pp.setOwnerType("CUSTOMER");
        pp.setOwnerId(c.getId());
        pp.setVerifiedAt(LocalDateTime.now());
        pp.setParticipantId("concurrent-peppol-" + UUID.randomUUID());
        pp.setSchemeId("0192");
        pp.setProvider("FAST_ACCOUNTING");
        pp.setStatus("VERIFIED");
        peppolParticipantService.save(pp);

        Invoice invoice = new Invoice();
        invoice.setInvoiceNo("NF09-C-" + System.nanoTime());
        invoice.setCustomerId(c.getId());
        invoice.setBillingMonth("2026-08");
        invoice.setSubtotal(new BigDecimal("1000"));
        invoice.setTax(new BigDecimal("100"));
        invoice.setTotal(new BigDecimal("1100"));
        invoice.setTaxRate(new BigDecimal("0.10"));
        invoice.setStatus("未送付");
        invoice.setIssuedDate(LocalDate.now());
        invoiceService.save(invoice);
        long invoiceId = invoice.getId();
        invoiceIdRef.set(invoiceId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict409 = new AtomicInteger();
        List<Throwable> otherErrors = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        TenantTestSecurity.bindAs("default", "管理者");
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        digitalInvoiceService.enqueueInvoiceForSend(invoiceId, "1.1.3", c.getId());
                        success.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getCode() == 409) {
                            conflict409.incrementAndGet();
                        } else {
                            otherErrors.add(e);
                        }
                    } catch (Throwable t) {
                        otherErrors.add(t);
                    } finally {
                        TenantTestSecurity.clear();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertTrue(otherErrors.isEmpty(), () -> "unexpected errors: " + otherErrors);
        assertEquals(1, success.get(), "exactly one enqueue should succeed");
        assertEquals(1, conflict409.get(), "the other request should be 409");

        long sendRows = digitalInvoiceService.lambdaQuery()
                .eq(DigitalInvoice::getInvoiceId, invoiceId)
                .eq(DigitalInvoice::getDirection, "SEND")
                .eq(DigitalInvoice::getProfile, "Standard")
                .count();
        assertEquals(1, sendRows);

        long jobs = integrationJobService.lambdaQuery()
                .eq(IntegrationJob::getIdempotencyKey,
                        "digital_invoice_send_default_1_" + invoiceId + "_Standard_1.1.3_g0")
                .count();
        assertEquals(1, jobs);
    }
}
