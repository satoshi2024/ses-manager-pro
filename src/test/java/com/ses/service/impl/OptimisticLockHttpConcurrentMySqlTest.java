package com.ses.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
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
import com.ses.service.CustomerService;
import com.ses.service.EngineerService;
import com.ses.service.WorkRecordService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REV-RP-P1-001 / ACC-ARCH-P1-003:
 * Engineer / Customer / WorkRecord の楽観ロックを、コントローラが呼ぶ実サービス経由で並行検証する。
 * 任意 Exception を成功扱いしない（REV-RP-P2-003）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class OptimisticLockHttpConcurrentMySqlTest {

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
    private EngineerService engineerService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private WorkRecordService workRecordService;
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
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void engineer_並行更新は一方のみ成功し409を返す() throws Exception {
        Engineer inserted = Engineer.builder().fullName("並行要員").employmentType("正社員").status("Bench").build();
        engineerMapper.insert(inserted);
        Engineer base = engineerMapper.selectById(inserted.getId());
        Integer sharedVersion = base.getVersion();

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger conflict409 = new AtomicInteger(0);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        runTwoThreads(() -> {
            Engineer patch = Engineer.builder().fullName("勝者A").employmentType("正社員").status("Bench").build();
            patch.setId(base.getId());
            patch.setVersion(sharedVersion);
            engineerService.updateWithStatusGuard(patch);
        }, () -> {
            Engineer patch = Engineer.builder().fullName("勝者B").employmentType("正社員").status("Bench").build();
            patch.setId(base.getId());
            patch.setVersion(sharedVersion);
            engineerService.updateWithStatusGuard(patch);
        }, success, conflict409, unexpected);

        assertEquals(null, unexpected.get(), () -> "予期しない例外: " + unexpected.get());
        assertEquals(1, success.get());
        assertEquals(1, conflict409.get());

        Engineer reloaded = engineerMapper.selectById(base.getId());
        assertEquals(sharedVersion + 1, reloaded.getVersion());
        assertTrue("勝者A".equals(reloaded.getFullName()) || "勝者B".equals(reloaded.getFullName()));
    }

    @Test
    void customer_並行更新は一方のみ成功し409を返す() throws Exception {
        Customer inserted = Customer.builder().companyName("並行顧客").build();
        customerMapper.insert(inserted);
        Customer base = customerMapper.selectById(inserted.getId());
        Integer sharedVersion = base.getVersion();

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger conflict409 = new AtomicInteger(0);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        runTwoThreads(() -> {
            Customer patch = Customer.builder().companyName("顧客A").build();
            patch.setId(base.getId());
            patch.setVersion(sharedVersion);
            customerService.updateWithOptimisticLock(patch);
        }, () -> {
            Customer patch = Customer.builder().companyName("顧客B").build();
            patch.setId(base.getId());
            patch.setVersion(sharedVersion);
            customerService.updateWithOptimisticLock(patch);
        }, success, conflict409, unexpected);

        assertEquals(null, unexpected.get(), () -> "予期しない例外: " + unexpected.get());
        assertEquals(1, success.get());
        assertEquals(1, conflict409.get());

        Customer reloaded = customerMapper.selectById(base.getId());
        assertEquals(sharedVersion + 1, reloaded.getVersion());
        assertTrue("顧客A".equals(reloaded.getCompanyName()) || "顧客B".equals(reloaded.getCompanyName()));
    }

    @Test
    void workRecord_並行saveHoursは一方のみ成功し409を返す() throws Exception {
        Long contractId = seedContractForWorkRecord();
        WorkRecord inserted = new WorkRecord();
        inserted.setContractId(contractId);
        inserted.setWorkMonth("2026-08");
        inserted.setActualHours(new BigDecimal("160.0"));
        inserted.setStatus("入力中");
        workRecordMapper.insert(inserted);
        WorkRecord base = workRecordMapper.selectById(inserted.getId());
        Integer sharedVersion = base.getVersion();

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger conflict409 = new AtomicInteger(0);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        runTwoThreads(() -> workRecordService.saveHours(
                        contractId, "2026-08", new BigDecimal("161.0"), "A", sharedVersion),
                () -> workRecordService.saveHours(
                        contractId, "2026-08", new BigDecimal("162.0"), "B", sharedVersion),
                success, conflict409, unexpected);

        assertEquals(null, unexpected.get(), () -> "予期しない例外: " + unexpected.get());
        assertEquals(1, success.get());
        assertEquals(1, conflict409.get());

        WorkRecord reloaded = workRecordMapper.selectById(base.getId());
        assertEquals(sharedVersion + 1, reloaded.getVersion());
        assertTrue("A".equals(reloaded.getRemarks()) || "B".equals(reloaded.getRemarks()));
        if ("A".equals(reloaded.getRemarks())) {
            assertEquals(0, new BigDecimal("161.0").compareTo(reloaded.getActualHours()));
        } else {
            assertEquals(0, new BigDecimal("162.0").compareTo(reloaded.getActualHours()));
        }
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void engineerHttp_version欠落は409() throws Exception {
        Engineer inserted = Engineer.builder().fullName("HTTP欠落").employmentType("正社員").status("Bench").build();
        engineerMapper.insert(inserted);

        String body = """
                {"fullName":"HTTP欠落更新","employmentType":"正社員","status":"Bench"}
                """;
        mockMvc.perform(put("/api/engineers/" + inserted.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void engineerHttp_staleVersionは409() throws Exception {
        Engineer inserted = Engineer.builder().fullName("HTTP競合").employmentType("正社員").status("Bench").build();
        engineerMapper.insert(inserted);
        Engineer current = engineerMapper.selectById(inserted.getId());
        Integer staleVersion = current.getVersion();

        // 先に別経路で version を進める
        current.setRemarks("先勝ち");
        assertEquals(1, engineerMapper.updateById(current));

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "fullName", "後負け",
                "employmentType", "正社員",
                "status", "Bench",
                "version", staleVersion
        ));
        MvcResult result = mockMvc.perform(put("/api/engineers/" + inserted.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertNotNull(root.get("message").asText());

        Engineer reloaded = engineerMapper.selectById(inserted.getId());
        assertEquals("先勝ち", reloaded.getRemarks());
        assertEquals(staleVersion + 1, reloaded.getVersion());
    }

    private Long seedContractForWorkRecord() {
        Customer customer = new Customer();
        customer.setCompanyName("OL顧客");
        customerMapper.insert(customer);

        Project project = new Project();
        project.setProjectName("OL案件");
        project.setCustomerId(customer.getId());
        projectMapper.insert(project);

        Engineer engineer = Engineer.builder().fullName("OL要員").employmentType("正社員").status("Bench").build();
        engineerMapper.insert(engineer);

        Contract contract = new Contract();
        contract.setEngineerId(engineer.getId());
        contract.setProjectId(project.getId());
        contract.setCustomerId(customer.getId());
        contract.setStartDate(LocalDate.now().minusMonths(1));
        contract.setStatus("稼動中");
        contract.setSellingPrice(new BigDecimal("500000"));
        contract.setCostPrice(new BigDecimal("300000"));
        contractMapper.insert(contract);
        return contract.getId();
    }

    private void runTwoThreads(ThrowingRunnable a, ThrowingRunnable b,
                               AtomicInteger success, AtomicInteger conflict409,
                               AtomicReference<Throwable> unexpected) throws Exception {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        Runnable wrap = taskAsRunnable(a, ready, start, done, success, conflict409, unexpected);
        Runnable wrap2 = taskAsRunnable(b, ready, start, done, success, conflict409, unexpected);

        Future<?> f1 = executor.submit(wrap);
        Future<?> f2 = executor.submit(wrap2);
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();
    }

    private Runnable taskAsRunnable(ThrowingRunnable task, CountDownLatch ready, CountDownLatch start,
                                    CountDownLatch done, AtomicInteger success, AtomicInteger conflict409,
                                    AtomicReference<Throwable> unexpected) {
        return () -> {
            try {
                ready.countDown();
                start.await();
                task.run();
                success.incrementAndGet();
            } catch (BusinessException be) {
                if (be.getCode() == 409 && "error.common.optimisticLock".equals(be.getMessageKey())) {
                    conflict409.incrementAndGet();
                } else {
                    unexpected.compareAndSet(null, be);
                }
            } catch (Throwable t) {
                // REV-RP-P2-003: 任意 Exception を成功扱いしない
                unexpected.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
