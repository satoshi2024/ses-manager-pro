package com.ses.service.pwa;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.common.exception.PwaConflictException;
import com.ses.entity.Engineer;
import com.ses.entity.PwaClientMutation;
import com.ses.entity.ExpenseRequest;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.PwaClientMutationMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.service.expense.ExpenseRequestService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 実MySQLで同一clientRequestIdの二writerを競合させ、DB UNIQUEとclaim transactionを検証する。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class PwaClientMutationLedgerMySqlConcurrencyTest {

    private static final String REQUEST_ID = "pwa-mysql-race-1";
    private static final String SCOPE = "opaque-scope-mysql";
    private static final long USER_ID = 700001L;

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_pwa_ledger")
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
    private PwaClientMutationLedgerService ledger;
    @Autowired
    private PwaClientMutationMapper mapper;
    @Autowired
    private ExpenseRequestMapper expenseMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private ExpenseRequestService expenseService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PwaCanonicalizer canonicalizer;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MockMvc mockMvc;

    private Long expenseId;
    private Long engineerId;

    @MockBean
    private PwaUserContextService userContextService;

    @MockBean
    private PwaMutationMetrics mutationMetrics;

    @AfterEach
    void cleanup() {
        mapper.delete(new QueryWrapper<PwaClientMutation>()
                .eq("user_id", USER_ID));
        if (expenseId != null) expenseMapper.deleteById(expenseId);
        if (engineerId != null) engineerMapper.deleteById(engineerId);
    }

    @Test
    void 同一ID同一hashの二writerは一件だけclaimし敗者は409になる() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode().put("contractId", 10);
        PwaMutationCommand command = new PwaMutationCommand("timesheet", "2026-08", 0, payload);
        String hash = command.payloadHash(canonicalizer);
        PwaUserContextService.CurrentContext context = new PwaUserContextService.CurrentContext(
                700001L, 900001L, SCOPE, Instant.now().minusSeconds(60));
        when(userContextService.assertCurrent(SCOPE)).thenReturn(context);
        when(userContextService.hashScope(SCOPE)).thenReturn("b".repeat(64));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<PwaClientMutationLedgerService.Claim> claims = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        long createdAt = Instant.now().toEpochMilli();
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    claims.add(ledger.claim(command, REQUEST_ID, hash, createdAt, SCOPE));
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).hasSize(1)
                .first().isInstanceOf(PwaConflictException.class);
        assertThat(claims).hasSize(1);
        assertThat(mapper.selectByUserAndClientRequest(USER_ID, REQUEST_ID)).isNotNull();

        PwaClientMutationLedgerService.Claim winner = claims.get(0);
        ledger.complete(winner.mutationId(), Map.of("version", 0));
        String rotatedScope = "opaque-scope-mysql-rotated";
        when(userContextService.assertCurrent(rotatedScope)).thenReturn(
                new PwaUserContextService.CurrentContext(USER_ID, 900001L, rotatedScope,
                        Instant.now().minusSeconds(60)));
        PwaClientMutationLedgerService.Claim replay = ledger.claim(command, REQUEST_ID, hash, createdAt,
                rotatedScope);
        assertThat(replay.replay()).isTrue();
    }

    @Test
    void 同一baseVersionの実domain更新は一件成功し一件409になり業務行を上書きしない() throws Exception {
        engineerId = seedEngineer();
        ExpenseRequest expense = ExpenseRequest.builder()
                .engineerId(engineerId)
                .expenseDate(java.time.LocalDate.of(2026, 8, 28))
                .category(ExpenseRequestService.CATEGORY_TRANSPORT)
                .amount(new java.math.BigDecimal("1000"))
                .description("初期")
                .status(ExpenseRequestService.STATUS_DRAFT)
                .version(0)
                .build();
        expenseMapper.insert(expense);
        expenseId = expense.getId();

        ObjectNode payload = objectMapper.createObjectNode()
                .put("id", expenseId)
                .put("expenseDate", "2026-08-28")
                .put("category", ExpenseRequestService.CATEGORY_TRANSPORT)
                .put("amount", 2000)
                .putNull("customerId")
                .putNull("projectId")
                .put("description", "競合writer");
        PwaMutationCommand command = new PwaMutationCommand(
                "expense:PUT:/api/my/pwa/expenses/drafts/" + expenseId,
                "expense", "2026-08", 0, payload);
        String hash = command.payloadHash(canonicalizer);
        PwaUserContextService.CurrentContext context = new PwaUserContextService.CurrentContext(
                USER_ID, engineerId, SCOPE, Instant.now().minusSeconds(60));
        when(userContextService.assertCurrent(SCOPE)).thenReturn(context);
        when(userContextService.hashScope(SCOPE)).thenReturn("c".repeat(64));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<String> requestIds = List.of("pwa-mysql-domain-a", "pwa-mysql-domain-b");
        List<Integer> successfulVersions = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        long createdAt = Instant.now().toEpochMilli();
        for (String requestId : requestIds) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    TransactionTemplate tx = new TransactionTemplate(transactionManager);
                    tx.executeWithoutResult(status -> {
                        PwaClientMutationLedgerService.Claim claim = ledger.claim(
                                command, requestId, hash, createdAt, SCOPE);
                        ExpenseRequest locked = expenseMapper.selectByIdForUpdate(expenseId);
                        if (!Integer.valueOf(0).equals(locked.getVersion())) {
                            ledger.abandon(claim.mutationId());
                            throw new PwaConflictException("pwa.staleBaseVersion", Map.of(
                                    "type", "STALE_BASE_VERSION", "serverVersion", locked.getVersion()));
                        }
                        expenseService.updateDraft(engineerId, expenseId,
                                new ExpenseRequestService.ExpenseDraftCommand(
                                        java.time.LocalDate.of(2026, 8, 28),
                                        ExpenseRequestService.CATEGORY_TRANSPORT,
                                        new java.math.BigDecimal("2000"), null, null, "競合writer"));
                        ExpenseRequest after = expenseMapper.selectById(expenseId);
                        ledger.complete(claim.mutationId(), Map.of("version", after.getVersion()));
                        successfulVersions.add(after.getVersion());
                    });
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(successfulVersions).containsExactly(1);
        assertThat(failures).hasSize(1).first().isInstanceOf(PwaConflictException.class);
        ExpenseRequest saved = expenseMapper.selectById(expenseId);
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getDescription()).isEqualTo("競合writer");
        assertThat(mapper.selectList(new QueryWrapper<PwaClientMutation>()
                .eq("user_id", USER_ID).in("client_request_id", requestIds)))
                .allMatch(row -> "COMPLETED".equals(row.getStatus()));
    }

    @Test
    void 実HTTPcontroller経路でもledgerとexpense更新を同一transactionで完了する() throws Exception {
        engineerId = seedEngineer();
        ExpenseRequest expense = ExpenseRequest.builder()
                .engineerId(engineerId)
                .expenseDate(java.time.LocalDate.of(2026, 8, 28))
                .category(ExpenseRequestService.CATEGORY_TRANSPORT)
                .amount(new java.math.BigDecimal("1000"))
                .description("controller前")
                .status(ExpenseRequestService.STATUS_DRAFT)
                .version(0)
                .build();
        expenseMapper.insert(expense);
        expenseId = expense.getId();

        ObjectNode payload = objectMapper.createObjectNode()
                .put("id", expenseId).put("expenseDate", "2026-08-28")
                .put("category", ExpenseRequestService.CATEGORY_TRANSPORT)
                .put("amount", 2000).putNull("customerId").putNull("projectId")
                .put("description", "controller後");
        ObjectNode body = objectMapper.createObjectNode()
                .put("screen", "expense").put("month", "2026-08")
                .set("payload", payload);
        PwaMutationCommand command = new PwaMutationCommand(
                "expense:PUT:/api/my/pwa/expenses/drafts/" + expenseId,
                "expense", "2026-08", 0, payload);
        String hash = command.payloadHash(canonicalizer);
        when(userContextService.assertCurrent(SCOPE)).thenReturn(new PwaUserContextService.CurrentContext(
                USER_ID, engineerId, SCOPE, Instant.now().minusSeconds(60)));
        when(userContextService.hashScope(SCOPE)).thenReturn("e".repeat(64));

        mockMvc.perform(put("/api/my/pwa/expenses/drafts/" + expenseId)
                        .with(user("1").roles("要員")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString())
                        .header("X-Client-Request-Id", "pwa-mysql-http-1")
                        .header("X-Client-Payload-Hash", hash)
                        .header("X-Client-Base-Version", "0")
                        .header("X-Client-Created-At", Instant.now().toEpochMilli())
                        .header("X-User-Scope", SCOPE))
                .andExpect(status().isOk());

        ExpenseRequest saved = expenseMapper.selectById(expenseId);
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getDescription()).isEqualTo("controller後");
        PwaClientMutation mutation = mapper.selectByUserAndClientRequest(USER_ID, "pwa-mysql-http-1");
        assertThat(mutation.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void 実HTTPcontroller経路で完了ackが失敗するとledgerと業務行をrollbackする() throws Exception {
        engineerId = seedEngineer();
        ExpenseRequest expense = ExpenseRequest.builder()
                .engineerId(engineerId)
                .expenseDate(java.time.LocalDate.of(2026, 8, 28))
                .category(ExpenseRequestService.CATEGORY_TRANSPORT)
                .amount(new java.math.BigDecimal("1000"))
                .description("HTTP rollback前")
                .status(ExpenseRequestService.STATUS_DRAFT)
                .version(0)
                .build();
        expenseMapper.insert(expense);
        expenseId = expense.getId();

        ObjectNode payload = objectMapper.createObjectNode()
                .put("id", expenseId).put("expenseDate", "2026-08-28")
                .put("category", ExpenseRequestService.CATEGORY_TRANSPORT)
                .put("amount", 3000).putNull("customerId").putNull("projectId")
                .put("description", "HTTP rollback後は残さない");
        ObjectNode body = objectMapper.createObjectNode()
                .put("screen", "expense").put("month", "2026-08")
                .set("payload", payload);
        PwaMutationCommand command = new PwaMutationCommand(
                "expense:PUT:/api/my/pwa/expenses/drafts/" + expenseId,
                "expense", "2026-08", 0, payload);
        String hash = command.payloadHash(canonicalizer);
        when(userContextService.assertCurrent(SCOPE)).thenReturn(new PwaUserContextService.CurrentContext(
                USER_ID, engineerId, SCOPE, Instant.now().minusSeconds(60)));
        when(userContextService.hashScope(SCOPE)).thenReturn("f".repeat(64));

        AtomicBoolean failCompletion = new AtomicBoolean();
        doAnswer(invocation -> {
            if (failCompletion.get()) throw new IllegalStateException("forced HTTP completion failure");
            return null;
        }).when(mutationMetrics).increment("completed", "expense");
        failCompletion.set(true);
        try {
            mockMvc.perform(put("/api/my/pwa/expenses/drafts/" + expenseId)
                            .with(user("1").roles("要員")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.toString())
                            .header("X-Client-Request-Id", "pwa-mysql-http-rollback")
                            .header("X-Client-Payload-Hash", hash)
                            .header("X-Client-Base-Version", "0")
                            .header("X-Client-Created-At", Instant.now().toEpochMilli())
                            .header("X-User-Scope", SCOPE))
                    .andExpect(status().is5xxServerError());
        } finally {
            failCompletion.set(false);
        }

        ExpenseRequest restored = expenseMapper.selectById(expenseId);
        assertThat(restored.getVersion()).isEqualTo(0);
        assertThat(restored.getDescription()).isEqualTo("HTTP rollback前");
        assertThat(mapper.selectByUserAndClientRequest(USER_ID, "pwa-mysql-http-rollback")).isNull();
    }

    @Test
    void domain失敗時は業務行とledgerackを同一transactionでrollbackする() {
        engineerId = seedEngineer();
        ExpenseRequest expense = ExpenseRequest.builder()
                .engineerId(engineerId)
                .expenseDate(java.time.LocalDate.of(2026, 8, 28))
                .category(ExpenseRequestService.CATEGORY_TRANSPORT)
                .amount(new java.math.BigDecimal("1000"))
                .description("rollback前")
                .status(ExpenseRequestService.STATUS_DRAFT)
                .version(0)
                .build();
        expenseMapper.insert(expense);
        expenseId = expense.getId();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("id", expenseId).put("expenseDate", "2026-08-28")
                .put("category", ExpenseRequestService.CATEGORY_TRANSPORT)
                .put("amount", 3000).putNull("customerId").putNull("projectId")
                .put("description", "rollback後は残さない");
        PwaMutationCommand command = new PwaMutationCommand(
                "expense:PUT:/api/my/pwa/expenses/drafts/" + expenseId,
                "expense", "2026-08", 0, payload);
        String hash = command.payloadHash(canonicalizer);
        when(userContextService.assertCurrent(SCOPE)).thenReturn(new PwaUserContextService.CurrentContext(
                USER_ID, engineerId, SCOPE, Instant.now().minusSeconds(60)));
        when(userContextService.hashScope(SCOPE)).thenReturn("d".repeat(64));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            PwaClientMutationLedgerService.Claim claim = ledger.claim(
                    command, "pwa-mysql-rollback", hash, Instant.now().toEpochMilli(), SCOPE);
            expenseService.updateDraft(engineerId, expenseId,
                    new ExpenseRequestService.ExpenseDraftCommand(
                            java.time.LocalDate.of(2026, 8, 28),
                            ExpenseRequestService.CATEGORY_TRANSPORT,
                            new java.math.BigDecimal("3000"), null, null, "rollback後は残さない"));
            ledger.complete(claim.mutationId(), Map.of("version", 1));
            throw new IllegalStateException("forced PWA rollback");
        })).isInstanceOf(IllegalStateException.class);

        ExpenseRequest restored = expenseMapper.selectById(expenseId);
        assertThat(restored.getVersion()).isEqualTo(0);
        assertThat(restored.getDescription()).isEqualTo("rollback前");
        assertThat(mapper.selectByUserAndClientRequest(USER_ID, "pwa-mysql-rollback"))
                .isNull();
    }

    private Long seedEngineer() {
        Engineer engineer = Engineer.builder()
                .fullName("PWA concurrency engineer")
                .employmentType("正社員")
                .status("稼動中")
                .version(0)
                .build();
        engineerMapper.insert(engineer);
        return engineer.getId();
    }
}
