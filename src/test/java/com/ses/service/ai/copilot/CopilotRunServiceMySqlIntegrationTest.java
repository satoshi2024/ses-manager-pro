package com.ses.service.ai.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.CopilotProviderUsage;
import com.ses.service.ai.copilot.summary.CopilotCostState;
import com.ses.service.ai.copilot.summary.DispatchOutcome;
import com.ses.service.ai.copilot.summary.SummaryCostEvidence;
import com.ses.service.ai.copilot.summary.SummaryResponse;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** C2-B1: MySQL実transactionでsummary metadata、tenant境界、CASとrollbackを検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class CopilotRunServiceMySqlIntegrationTest {
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

    @Autowired private CopilotRunService runService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @SpyBean private ObjectMapper objectMapper;
    @SpyBean private AiRecommendationRunMapper runMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearThreadContexts() {
        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void NOT_SENTとUNKNOWNのusagemetadataをcommit後の独立connectionで確認する() throws Exception {
        assertTrue(AopUtils.isAopProxy(runService), "Spring transaction proxyを通す");

        Fixture notSent = createFixture("tenant-a");
        SummaryCostEvidence notSentEvidence = new SummaryCostEvidence(CopilotCostState.NOT_SENT,
                null, null, DispatchOutcome.notStarted("PREFLIGHT_DENIED"), null, null,
                "PREFLIGHT_DENIED");
        CopilotRunService.SummaryCompletionResult notSentResult = AccountingTenantContextHolder.runWithTenant(
                "tenant-a", () -> runService.completeSummaryAttempt(notSent.attempt(), 12,
                        SummaryResponse.unavailable("SUMMARY_REJECTED", notSentEvidence)));
        assertEquals(CopilotRunService.SummaryCompletionResult.COMPLETED, notSentResult);
        Snapshot notSentRow = readIndependently(notSent.runId());
        assertEquals("FAILED", notSentRow.status());
        assertEquals(1, notSentRow.statusVersion());
        assertEquals(0, notSentRow.costJpy());
        assertEquals("SUMMARY_REJECTED", notSentRow.errorCode());
        JsonNode notSentMetadata = objectMapper.readTree(notSentRow.redactedSummaryJson());
        assertEquals("NOT_SENT", notSentMetadata.path("costState").asText());
        assertEquals("NOT_STARTED", notSentMetadata.path("dispatchOutcome").asText());
        String expectedNotSentJson = expectedCompletionJson(notSent.attempt(), "SUMMARY_REJECTED", 12,
                "NOT_SENT", "NOT_STARTED", "PREFLIGHT_DENIED", "PREFLIGHT_DENIED", null, 0);
        assertEquals(objectMapper.readTree(expectedNotSentJson), notSentMetadata);
        assertEquals(sha256(expectedNotSentJson), notSentRow.inputHash());

        Fixture unknown = createFixture("tenant-a");
        CopilotProviderUsage usage = new CopilotProviderUsage(12, 4, 16, 0, 0);
        SummaryCostEvidence unknownEvidence = SummaryCostEvidence.fromPreflight(null,
                new DispatchOutcome(DispatchOutcome.State.RESPONSE_RECEIVED, "PROVIDER_RESPONSE_RECEIVED"),
                usage, "INNER_VALIDATION_FAILED");
        assertEquals(CopilotCostState.UNKNOWN, unknownEvidence.costState());
        CopilotRunService.SummaryCompletionResult unknownResult = AccountingTenantContextHolder.runWithTenant(
                "tenant-a", () -> runService.completeSummaryAttempt(unknown.attempt(), 34,
                        SummaryResponse.unavailable("INNER_VALIDATION_FAILED", unknownEvidence)));
        assertEquals(CopilotRunService.SummaryCompletionResult.COMPLETED, unknownResult);
        Snapshot unknownRow = readIndependently(unknown.runId());
        assertEquals("FAILED", unknownRow.status());
        assertEquals(1, unknownRow.statusVersion());
        assertNull(unknownRow.costJpy());
        assertEquals("INNER_VALIDATION_FAILED", unknownRow.errorCode());
        JsonNode unknownMetadata = objectMapper.readTree(unknownRow.redactedSummaryJson());
        assertEquals("UNKNOWN", unknownMetadata.path("costState").asText());
        assertEquals("RESPONSE_RECEIVED", unknownMetadata.path("dispatchOutcome").asText());
        JsonNode storedUsage = unknownMetadata.path("providerUsage");
        assertEquals(12, storedUsage.path("promptTokenCount").asInt());
        assertEquals(4, storedUsage.path("candidateTokenCount").asInt());
        assertEquals(16, storedUsage.path("totalTokenCount").asInt());
        assertEquals(0, storedUsage.path("thoughtsTokenCount").asInt());
        assertEquals(0, storedUsage.path("cachedContentTokenCount").asInt());
        assertFalse(unknownRow.redactedSummaryJson().contains("costJpy"));
        String expectedUnknownJson = expectedCompletionJson(unknown.attempt(), "INNER_VALIDATION_FAILED", 34,
                "UNKNOWN", "RESPONSE_RECEIVED", "PROVIDER_RESPONSE_RECEIVED",
                "INNER_VALIDATION_FAILED", usage, null);
        assertEquals(objectMapper.readTree(expectedUnknownJson), unknownMetadata);
        assertEquals(sha256(expectedUnknownJson), unknownRow.inputHash());
    }

    @Test
    void UPPER_BOUND完了は実UPDATEで古いcost_jpyを消しmetadataをcommitする() throws Exception {
        assertTrue(AopUtils.isAopProxy(runService), "Spring transaction proxyを通す");
        Fixture fixture = createFixture("tenant-a");
        Fixture tenantBSentinel = createFixture("tenant-b");
        jdbcTemplate.update("UPDATE t_ai_recommendation_run SET cost_jpy=99, redacted_summary_json=? WHERE id=?",
                "{\"attemptKind\":\"SUMMARY\",\"traceId\":\"" + fixture.attempt().traceId()
                        + "\",\"outcome\":\"PENDING\",\"costState\":\"UNKNOWN\",\"costJpy\":99,"
                        + "\"costUpperBoundYen\":999,\"configHash\":\""
                        + fixture.attempt().artifact().configHash() + "\"}", fixture.runId());
        Snapshot sentinelBefore = readIndependently(tenantBSentinel.runId());

        SummaryCostEvidence upperBound = new SummaryCostEvidence(CopilotCostState.UPPER_BOUND,
                null, 123, new DispatchOutcome(DispatchOutcome.State.RESPONSE_RECEIVED,
                "PROVIDER_RESPONSE_RECEIVED"), null, null, "PRECHECK_BOUND");
        CopilotRunService.SummaryCompletionResult result = AccountingTenantContextHolder.runWithTenant(
                "tenant-a", () -> runService.completeSummaryAttempt(fixture.attempt(), 21,
                        SummaryResponse.unavailable("COST_UPPER_BOUND", upperBound)));

        assertEquals(CopilotRunService.SummaryCompletionResult.COMPLETED, result);
        Snapshot committed = readIndependently(fixture.runId());
        assertEquals("FAILED", committed.status());
        assertEquals(1, committed.statusVersion());
        assertNull(committed.costJpy(), "実UPDATE後は以前のcost_jpyが残らない");
        JsonNode metadata = objectMapper.readTree(committed.redactedSummaryJson());
        assertEquals("UPPER_BOUND", metadata.path("costState").asText());
        assertEquals(123, metadata.path("costUpperBoundYen").asInt());
        assertFalse(metadata.has("costJpy"));
        String expectedUpperBoundJson = expectedUpperBoundJson(fixture.attempt());
        assertEquals(objectMapper.readTree(expectedUpperBoundJson), metadata);
        assertEquals(sha256(expectedUpperBoundJson), committed.inputHash());
        assertEquals(sentinelBefore, readIndependently(tenantBSentinel.runId()));
    }

    @Test
    void caller指定ACTUALはJSON化とmapper更新前に拒否され二tenant全列不変() throws Exception {
        assertTrue(AopUtils.isAopProxy(runService), "Spring transaction proxyを通す");
        Fixture fixture = createFixture("tenant-a");
        Fixture tenantBSentinel = createFixture("tenant-b");
        Snapshot before = readIndependently(fixture.runId());
        Snapshot sentinelBefore = readIndependently(tenantBSentinel.runId());
        clearInvocations(objectMapper, runMapper);

        SummaryCostEvidence forgedActual = new SummaryCostEvidence(CopilotCostState.ACTUAL, 7, null,
                new DispatchOutcome(DispatchOutcome.State.RESPONSE_RECEIVED, "RESPONSE_RECEIVED"),
                null, new CopilotProviderUsage(10, 2, 12, null, null), "CALLER_FORGED");
        SummaryResponse conflictingFields = new SummaryResponse(null, List.of(), "FAILED", null,
                0L, null, 99, forgedActual);
        BusinessException thrown = assertThrows(BusinessException.class,
                () -> AccountingTenantContextHolder.runWithTenant("tenant-a",
                        () -> runService.completeSummaryAttempt(fixture.attempt(), 8, conflictingFields)));

        assertEquals(409, thrown.getCode());
        assertEquals("COPILOT_ACTUAL_COST_NOT_VERIFIED", thrown.getMessageKey());
        verify(objectMapper, never()).writeValueAsString(any());
        verify(runMapper, never()).update(org.mockito.ArgumentMatchers.<com.ses.entity.AiRecommendationRun>isNull(),
                any(Wrapper.class));
        verify(runMapper, never()).insert(any(com.ses.entity.AiRecommendationRun.class));
        assertEquals(before, readIndependently(fixture.runId()));
        assertEquals(sentinelBefore, readIndependently(tenantBSentinel.runId()));
    }

    @Test
    void 反復完了はSTALEとなりterminal行の全列を変更しない() throws Exception {
        Fixture fixture = createFixture("tenant-a");
        Fixture tenantBSentinel = createFixture("tenant-b");
        Snapshot sentinelBefore = readIndependently(tenantBSentinel.runId());
        SummaryCostEvidence evidence = unknownEvidence();
        SummaryResponse response = SummaryResponse.unavailable("SUMMARY_REJECTED", evidence);
        AccountingTenantContextHolder.runWithTenant("tenant-a",
                () -> runService.completeSummaryAttempt(fixture.attempt(), 19, response));
        Snapshot terminal = readIndependently(fixture.runId());

        CopilotRunService.SummaryCompletionResult duplicate = AccountingTenantContextHolder.runWithTenant(
                "tenant-a", () -> runService.completeSummaryAttempt(fixture.attempt(), 99,
                        SummaryResponse.unavailable("ANOTHER_RESULT", unknownEvidence())));

        assertEquals(CopilotRunService.SummaryCompletionResult.STALE, duplicate);
        assertEquals(terminal, readIndependently(fixture.runId()));
        assertEquals(sentinelBefore, readIndependently(tenantBSentinel.runId()));
    }

    @Test
    void tenant未設定または不一致ではDBを変更しない() throws Exception {
        Fixture fixture = createFixture("tenant-a");
        Fixture tenantBSentinel = createFixture("tenant-b");
        Snapshot before = readIndependently(fixture.runId());
        Snapshot sentinelBefore = readIndependently(tenantBSentinel.runId());

        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
        assertThrows(BusinessException.class,
                () -> runService.completeSummaryAttempt(fixture.attempt(), 5,
                        SummaryResponse.unavailable("NO_CONTEXT", unknownEvidence())));
        assertEquals(before, readIndependently(fixture.runId()));

        assertThrows(BusinessException.class, () -> AccountingTenantContextHolder.runWithTenant("tenant-b",
                () -> runService.completeSummaryAttempt(fixture.attempt(), 5,
                        SummaryResponse.unavailable("WRONG_TENANT", unknownEvidence()))));
        assertEquals(before, readIndependently(fixture.runId()));
        assertEquals(sentinelBefore, readIndependently(tenantBSentinel.runId()));
    }

    @Test
    void DB行のtenant_trace_usecase_status_version不一致はSTALEで全列不変() throws Exception {
        Fixture traceFixture = createFixture("tenant-a");
        Fixture tenantBSentinel = createFixture("tenant-b");
        Snapshot sentinelBefore = readIndependently(tenantBSentinel.runId());
        Snapshot traceBefore = readIndependently(traceFixture.runId());
        CopilotRunService.CopilotAttemptRecord wrongTrace = attempt(traceFixture, "wrong-trace", "tenant-a");
        assertEquals(CopilotRunService.SummaryCompletionResult.STALE,
                AccountingTenantContextHolder.runWithTenant("tenant-a", () -> runService.completeSummaryAttempt(
                        wrongTrace, 1, SummaryResponse.unavailable("STALE", unknownEvidence()))));
        assertEquals(traceBefore, readIndependently(traceFixture.runId()));

        CopilotRunService.CopilotAttemptRecord wrongTenant = attempt(traceFixture,
                traceFixture.attempt().traceId(), "tenant-b");
        assertEquals(CopilotRunService.SummaryCompletionResult.STALE,
                AccountingTenantContextHolder.runWithTenant("tenant-b", () -> runService.completeSummaryAttempt(
                        wrongTenant, 1, SummaryResponse.unavailable("STALE", unknownEvidence()))));
        assertEquals(traceBefore, readIndependently(traceFixture.runId()));

        Fixture useCaseFixture = createFixture("tenant-a");
        jdbcTemplate.update("UPDATE t_ai_recommendation_run SET use_case='OTHER' WHERE id=?",
                useCaseFixture.runId());
        Snapshot useCaseBefore = readIndependently(useCaseFixture.runId());
        assertEquals(CopilotRunService.SummaryCompletionResult.STALE,
                AccountingTenantContextHolder.runWithTenant("tenant-a", () -> runService.completeSummaryAttempt(
                        useCaseFixture.attempt(), 1, SummaryResponse.unavailable("STALE", unknownEvidence()))));
        assertEquals(useCaseBefore, readIndependently(useCaseFixture.runId()));

        Fixture statusFixture = createFixture("tenant-a");
        jdbcTemplate.update("UPDATE t_ai_recommendation_run SET status='FAILED' WHERE id=?",
                statusFixture.runId());
        Snapshot statusBefore = readIndependently(statusFixture.runId());
        assertEquals(CopilotRunService.SummaryCompletionResult.STALE,
                AccountingTenantContextHolder.runWithTenant("tenant-a", () -> runService.completeSummaryAttempt(
                        statusFixture.attempt(), 1, SummaryResponse.unavailable("STALE", unknownEvidence()))));
        assertEquals(statusBefore, readIndependently(statusFixture.runId()));

        Fixture versionFixture = createFixture("tenant-a");
        jdbcTemplate.update("UPDATE t_ai_recommendation_run SET status_version=1 WHERE id=?",
                versionFixture.runId());
        Snapshot versionBefore = readIndependently(versionFixture.runId());
        assertEquals(CopilotRunService.SummaryCompletionResult.STALE,
                AccountingTenantContextHolder.runWithTenant("tenant-a", () -> runService.completeSummaryAttempt(
                        versionFixture.attempt(), 1, SummaryResponse.unavailable("STALE", unknownEvidence()))));
        assertEquals(versionBefore, readIndependently(versionFixture.runId()));
        assertEquals(sentinelBefore, readIndependently(tenantBSentinel.runId()));
    }

    @Test
    void barrier競合は一件だけ完了し他方はSTALE() throws Exception {
        Fixture fixture = createFixture("tenant-a");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CopilotRunService.SummaryCompletionResult> first = executor.submit(() -> completeAfterBarrier(
                    fixture, ready, start));
            Future<CopilotRunService.SummaryCompletionResult> second = executor.submit(() -> completeAfterBarrier(
                    fixture, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<CopilotRunService.SummaryCompletionResult> results = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(r -> r == CopilotRunService.SummaryCompletionResult.COMPLETED).count());
            assertEquals(1, results.stream().filter(r -> r == CopilotRunService.SummaryCompletionResult.STALE).count());
            Snapshot row = readIndependently(fixture.runId());
            assertEquals("FAILED", row.status());
            assertEquals(1, row.statusVersion());
            assertNull(row.costJpy());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            AccountingTenantContextHolder.clear();
        }
    }

    @Test
    void 外側transactionのrollback後はcommit前の全列へ戻る() throws Exception {
        Fixture fixture = createFixture("tenant-a");
        Fixture tenantBSentinel = createFixture("tenant-b");
        Snapshot before = readIndependently(fixture.runId());
        Snapshot sentinelBefore = readIndependently(tenantBSentinel.runId());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThrows(RollbackMarker.class, () -> transaction.execute(status -> {
            CopilotRunService.SummaryCompletionResult result = AccountingTenantContextHolder.runWithTenant(
                    "tenant-a", () -> runService.completeSummaryAttempt(fixture.attempt(), 8,
                            SummaryResponse.unavailable("ROLLBACK_CASE", unknownEvidence())));
            assertEquals(CopilotRunService.SummaryCompletionResult.COMPLETED, result);
            throw new RollbackMarker();
        }));

        assertEquals(before, readIndependently(fixture.runId()));
        assertEquals(sentinelBefore, readIndependently(tenantBSentinel.runId()));
    }

    @Test
    void completionJSONの書込前serialization失敗は元causeを保ち二tenant全列不変() throws Exception {
        assertTrue(AopUtils.isAopProxy(runService));
        Fixture fixture = createFixture("tenant-a");
        Fixture tenantBSentinel = createFixture("tenant-b");
        Snapshot before = readIndependently(fixture.runId());
        Snapshot sentinelBefore = readIndependently(tenantBSentinel.runId());
        IllegalArgumentException serializationCause = new IllegalArgumentException("fixture serialization failure");

        doAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            if (value instanceof Map<?, ?> map && "SERIALIZATION_FAIL".equals(map.get("outcome"))) {
                throw serializationCause;
            }
            return invocation.callRealMethod();
        }).when(objectMapper).writeValueAsString(any());

        try {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> AccountingTenantContextHolder.runWithTenant("tenant-a",
                            () -> runService.completeSummaryAttempt(fixture.attempt(), 8,
                                    SummaryResponse.unavailable("SERIALIZATION_FAIL", unknownEvidence()))));
            assertSame(serializationCause, thrown.getCause());
            assertEquals(before, readIndependently(fixture.runId()));
            assertEquals(sentinelBefore, readIndependently(tenantBSentinel.runId()));
        } finally {
            reset(objectMapper);
        }
    }

    @Test
    void MySQL実UPDATE失敗はSQLStateとcauseを保ち二tenant全列不変() throws Exception {
        assertTrue(AopUtils.isAopProxy(runService));
        Fixture fixture = createFixture("tenant-a");
        Fixture tenantBSentinel = createFixture("tenant-b");
        Snapshot before = readIndependently(fixture.runId());
        Snapshot sentinelBefore = readIndependently(tenantBSentinel.runId());
        String triggerName = "tr_nf08_fail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER " + triggerName + " BEFORE UPDATE ON t_ai_recommendation_run "
                    + "FOR EACH ROW BEGIN IF OLD.id = " + fixture.runId() + " THEN "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'nf08 fixture update failure'; "
                    + "END IF; END");
        }
        try {
            DataAccessException thrown = assertThrows(DataAccessException.class,
                    () -> AccountingTenantContextHolder.runWithTenant("tenant-a",
                            () -> runService.completeSummaryAttempt(fixture.attempt(), 8,
                                    SummaryResponse.unavailable("SQL_FAIL", unknownEvidence()))));
            SQLException sqlCause = findSqlException(thrown);
            assertNotNull(sqlCause);
            assertEquals("45000", sqlCause.getSQLState());
            assertEquals("nf08 fixture update failure", sqlCause.getMessage());
            assertEquals(before, readIndependently(fixture.runId()));
            assertEquals(sentinelBefore, readIndependently(tenantBSentinel.runId()));
        } finally {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER IF EXISTS " + triggerName);
            }
        }
    }

    @Test
    void beforeCommit失敗はCOMPLETED応答後にrollbackされafterCompletionと二tenant全列で確認する() throws Exception {
        assertTrue(AopUtils.isAopProxy(runService));
        Fixture fixture = createFixture("tenant-a");
        Fixture tenantBSentinel = createFixture("tenant-b");
        Snapshot before = readIndependently(fixture.runId());
        Snapshot sentinelBefore = readIndependently(tenantBSentinel.runId());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        AtomicInteger afterCompletionStatus = new AtomicInteger(-1);
        CommitBeforeSuccessMarker marker = new CommitBeforeSuccessMarker();

        CommitBeforeSuccessMarker thrown = assertThrows(CommitBeforeSuccessMarker.class,
                () -> transaction.execute(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) { throw marker; }
                        @Override public void afterCompletion(int status) { afterCompletionStatus.set(status); }
                    });
                    CopilotRunService.SummaryCompletionResult result = AccountingTenantContextHolder.runWithTenant(
                            "tenant-a", () -> runService.completeSummaryAttempt(fixture.attempt(), 8,
                                    SummaryResponse.unavailable("BEFORE_COMMIT_FAIL", unknownEvidence())));
                    assertEquals(CopilotRunService.SummaryCompletionResult.COMPLETED, result);
                    return null;
                }));

        assertSame(marker, thrown);
        assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK, afterCompletionStatus.get());
        assertEquals(before, readIndependently(fixture.runId()));
        assertEquals(sentinelBefore, readIndependently(tenantBSentinel.runId()));
    }

    @Test
    void commit後のcaller例外simulationはdurable行をrollback扱いしない() throws Exception {
        Fixture fixture = createFixture("tenant-a");
        Snapshot before = readIndependently(fixture.runId());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CopilotRunService.SummaryCompletionResult committed = transaction.execute(status ->
                AccountingTenantContextHolder.runWithTenant("tenant-a",
                        () -> runService.completeSummaryAttempt(fixture.attempt(), 8,
                                SummaryResponse.unavailable("ACK_LOSS_CASE", unknownEvidence()))));
        assertEquals(CopilotRunService.SummaryCompletionResult.COMPLETED, committed);
        Snapshot durable = readIndependently(fixture.runId());
        assertFalse(before.equals(durable), "proxy/transaction return後は独立connectionからcommit済み行を読む");

        // commit後のcaller例外を単純に模擬するだけで、実ACK障害やUNKNOWN処理は検証しない。
        // 既にdurableな行をPENDINGへ戻す・pre-commit snapshotと等しいと主張することは禁止する。
        RuntimeException callerFailure = new RuntimeException("simulated caller exception after commit");
        assertSame(callerFailure, assertThrows(RuntimeException.class, () -> { throw callerFailure; }));
        assertEquals(durable, readIndependently(fixture.runId()));
    }

    private SQLException findSqlException(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) return sqlException;
        }
        return null;
    }

    private CopilotRunService.SummaryCompletionResult completeAfterBarrier(
            Fixture fixture, CountDownLatch ready, CountDownLatch start) throws Exception {
        AccountingTenantContextHolder.setTenantId("tenant-a");
        try {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return runService.completeSummaryAttempt(fixture.attempt(), 20,
                    SummaryResponse.unavailable("CONCURRENT_RESULT", unknownEvidence()));
        } finally {
            AccountingTenantContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private Fixture createFixture(String tenantId) {
        Long artifactId = jdbcTemplate.queryForObject("SELECT id FROM m_ai_artifact_version "
                + "WHERE use_case='MANAGEMENT_COPILOT' AND status='ACTIVE' AND deleted_flag=0 LIMIT 1", Long.class);
        assertNotNull(artifactId);
        String traceId = UUID.randomUUID().toString();
        String inputHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        jdbcTemplate.update("INSERT INTO t_ai_recommendation_run "
                        + "(tenant_id, trace_id, use_case, artifact_version_id, input_hash, "
                        + "redacted_summary_json, status, status_version) VALUES (?, ?, 'MANAGEMENT_COPILOT', ?, ?, ?, 'PENDING', 0)",
                tenantId, traceId, artifactId, inputHash,
                "{\"attemptKind\":\"SUMMARY\",\"outcome\":\"PENDING\",\"costState\":\"UNKNOWN\"}");
        Long runId = jdbcTemplate.queryForObject("SELECT id FROM t_ai_recommendation_run WHERE trace_id=?",
                Long.class, traceId);
        CopilotRunService.CopilotArtifactSnapshot artifact = new CopilotRunService.CopilotArtifactSnapshot(
                artifactId, "mock", "mock-management-copilot", "nf08-f1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "catalog-v1", 0);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attemptKind", "SUMMARY");
        metadata.put("traceId", traceId);
        metadata.put("outcome", "PENDING");
        metadata.put("costState", "UNKNOWN");
        metadata.put("configHash", artifact.configHash());
        CopilotRunService.CopilotAttemptRecord attempt = new CopilotRunService.CopilotAttemptRecord(
                runId, traceId, null, "SUMMARY", tenantId, artifact, metadata);
        return new Fixture(runId, attempt);
    }

    private CopilotRunService.CopilotAttemptRecord attempt(Fixture fixture, String traceId, String tenantId) {
        CopilotRunService.CopilotAttemptRecord source = fixture.attempt();
        return new CopilotRunService.CopilotAttemptRecord(source.runId(), traceId, source.parentTraceId(),
                source.attemptKind(), tenantId, source.artifact(), source.metadata());
    }

    private Snapshot readIndependently(long runId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM t_ai_recommendation_run WHERE id=?")) {
            statement.setLong(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                Map<String, Object> columns = new java.util.LinkedHashMap<>();
                var metadata = result.getMetaData();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    columns.put(metadata.getColumnLabel(column), result.getObject(column));
                }
                return new Snapshot((String) columns.get("tenant_id"), (String) columns.get("trace_id"),
                        (String) columns.get("use_case"), (String) columns.get("status"),
                        ((Number) columns.get("status_version")).intValue(), (Integer) columns.get("cost_jpy"),
                        (String) columns.get("error_code"), (String) columns.get("redacted_summary_json"),
                        (String) columns.get("input_hash"), java.util.Collections.unmodifiableMap(columns));
            }
        }
    }

    private SummaryCostEvidence unknownEvidence() {
        return SummaryCostEvidence.fromPreflight(null,
                new DispatchOutcome(DispatchOutcome.State.RESPONSE_RECEIVED, "PROVIDER_RESPONSE_RECEIVED"),
                new CopilotProviderUsage(12, 4, 16, 0, 0), "INNER_VALIDATION_FAILED");
    }

    private String expectedCompletionJson(CopilotRunService.CopilotAttemptRecord attempt,
                                          String outcome, int latency, String costState,
                                          String dispatchOutcome, String dispatchReason, String costReason,
                                          CopilotProviderUsage usage, Integer costJpy) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attemptKind", "SUMMARY");
        metadata.put("traceId", attempt.traceId());
        metadata.put("outcome", outcome);
        metadata.put("costState", costState);
        metadata.put("configHash", attempt.artifact().configHash());
        metadata.put("latencyMs", latency);
        metadata.put("dispatchOutcome", dispatchOutcome);
        metadata.put("dispatchReasonCode", dispatchReason);
        metadata.put("costReasonCode", costReason);
        if (usage != null) {
            Map<String, Object> usageMetadata = new LinkedHashMap<>();
            usageMetadata.put("promptTokenCount", usage.promptTokenCount());
            usageMetadata.put("candidateTokenCount", usage.candidateTokenCount());
            usageMetadata.put("totalTokenCount", usage.totalTokenCount());
            usageMetadata.put("thoughtsTokenCount", usage.thoughtsTokenCount());
            usageMetadata.put("cachedContentTokenCount", usage.cachedContentTokenCount());
            metadata.put("providerUsage", usageMetadata);
        }
        if (costJpy != null) metadata.put("costJpy", costJpy);
        return objectMapper.writeValueAsString(metadata);
    }

    private String expectedUpperBoundJson(CopilotRunService.CopilotAttemptRecord attempt) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attemptKind", "SUMMARY");
        metadata.put("traceId", attempt.traceId());
        metadata.put("outcome", "COST_UPPER_BOUND");
        metadata.put("costState", "UPPER_BOUND");
        metadata.put("configHash", attempt.artifact().configHash());
        metadata.put("latencyMs", 21);
        metadata.put("dispatchOutcome", "RESPONSE_RECEIVED");
        metadata.put("dispatchReasonCode", "PROVIDER_RESPONSE_RECEIVED");
        metadata.put("costReasonCode", "PRECHECK_BOUND");
        metadata.put("costUpperBoundYen", 123);
        return objectMapper.writeValueAsString(metadata);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record Fixture(long runId, CopilotRunService.CopilotAttemptRecord attempt) { }
    private record Snapshot(String tenantId, String traceId, String useCase, String status,
                            int statusVersion, Integer costJpy, String errorCode,
                            String redactedSummaryJson, String inputHash, Map<String, Object> allColumns) { }
    private static final class RollbackMarker extends RuntimeException { }
    private static final class CommitBeforeSuccessMarker extends RuntimeException { }
}
