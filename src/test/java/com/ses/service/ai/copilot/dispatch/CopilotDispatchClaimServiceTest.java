package com.ses.service.ai.copilot.dispatch;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AiExternalSendCostBucket;
import com.ses.entity.AiExternalSendCostEntry;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.AiExternalSendAuditor;
import com.ses.service.ai.ExternalSendCostLedger;
import com.ses.service.ai.PersistentExternalSendCostLedger;
import com.ses.service.ai.PersistentExternalSendCostLedger.BucketKeys;
import com.ses.service.ai.PersistentExternalSendCostLedger.ReserveResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF08 B2c-1 claim serviceの分岐（mock mapper・fake transaction manager・固定Clock）。
 * 競合・lock・一意制約・commit不明の実DB挙動はMySQL gate（CopilotDispatchClaimMySqlIntegrationTest）で確認する。
 */
class CopilotDispatchClaimServiceTest {

    private static final String TENANT = "tenant-a";
    private static final Instant T0 = Instant.parse("2026-11-15T01:00:00Z");

    private AiRecommendationRunMapper runMapper;
    private PersistentExternalSendCostLedger ledger;
    private AiExternalSendAuditor auditor;
    private PlatformTransactionManager transactionManager;
    private DispatchClaimFixtures.SequenceClock clock;
    private AtomicLong nanos;
    private CopilotDispatchClaimService service;
    private AiExternalSendCostBucket dayBucket;
    private AiExternalSendCostBucket monthBucket;

    @BeforeEach
    void setUp() {
        runMapper = mock(AiRecommendationRunMapper.class);
        ledger = mock(PersistentExternalSendCostLedger.class);
        auditor = mock(AiExternalSendAuditor.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        clock = new DispatchClaimFixtures.SequenceClock(T0);
        nanos = new AtomicLong(1_000_000_000L);
        service = new CopilotDispatchClaimService(runMapper, ledger, auditor, transactionManager, clock,
                nanos::get, new SecureRandom());
        when(runMapper.markDispatchReady(any())).thenReturn(1);
        when(runMapper.claimDispatch(any(), any())).thenReturn(1);
        dayBucket = bucket(1L);
        monthBucket = bucket(2L);
        when(ledger.lockBucket(eq(AiExternalSendCostBucket.TYPE_DAY), anyString(), anyString())).thenReturn(dayBucket);
        when(ledger.lockBucket(eq(AiExternalSendCostBucket.TYPE_MONTH), anyString(), anyString()))
                .thenReturn(monthBucket);
        when(ledger.reserve(any(), anyInt(), anyLong(), any(), any(), any())).thenReturn(ReserveResult.RESERVED);
        AccountingTenantContextHolder.setTenantId(TENANT);
    }

    private static AiExternalSendCostBucket bucket(long id) {
        AiExternalSendCostBucket bucket = new AiExternalSendCostBucket();
        bucket.setId(id);
        bucket.setCommittedYen(0L);
        return bucket;
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void READY化は一度だけ生成したclaim_IDとhashだけを固定しnonceの生値を渡さない() {
        DispatchTicket first = prepare();
        DispatchTicket second = prepare();
        ArgumentCaptor<DispatchColumns> columns = ArgumentCaptor.forClass(DispatchColumns.class);
        verify(runMapper, times(2)).markDispatchReady(columns.capture());
        DispatchColumns written = columns.getAllValues().get(0);
        assertEquals(first.claimId(), written.getClaimId());
        assertTrue(DispatchDigests.CANONICAL_UUID.matcher(written.getClaimId()).matches());
        assertArrayEquals(DispatchDigests.sha256(first.nonce()), written.getNonceHash());
        assertFalse(java.util.Arrays.equals(first.nonce(), written.getNonceHash()), "生のnonceを保存しない");
        assertArrayEquals(DispatchDigests.sha256(first.idempotencyKey()), written.getIdempotencyHash());
        assertEquals(LocalDateTime.of(2026, 11, 15, 1, 0), written.getIssuedAt(), "UTCのDATETIME(6)");
        assertEquals(LocalDateTime.of(2026, 11, 15, 1, 2), written.getExpiresAt());
        assertNotEquals(first.claimId(), second.claimId());
        assertFalse(java.util.Arrays.equals(first.nonce(), second.nonce()));
    }

    @Test
    void READY化はtenant不一致_SUMMARY以外_artifact不一致_行の状態不一致を拒否する() {
        DispatchIssueRequest request = DispatchClaimFixtures.request(7L, T0, nanos.get());
        AccountingTenantContextHolder.setTenantId("tenant-b");
        BusinessException tenant = assertThrows(BusinessException.class,
                () -> service.prepareReady(attempt(7L), request));
        assertEquals(403, tenant.getCode());
        AccountingTenantContextHolder.setTenantId(TENANT);

        var query = new com.ses.service.ai.copilot.CopilotRunService.CopilotAttemptRecord(1L,
                UUID.randomUUID().toString(), null, "QUERY", TENANT, attempt(7L).artifact(), java.util.Map.of());
        assertThrows(IllegalArgumentException.class, () -> service.prepareReady(query, request));
        assertThrows(IllegalArgumentException.class, () -> service.prepareReady(attempt(8L), request),
                "attemptのartifactと一致しない");
        when(runMapper.markDispatchReady(any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.prepareReady(attempt(7L), request));
    }

    @Test
    void 成功時は事前生成_ATTEMPT_runからDAY_MONTH_entry_CASの順に進みcommit後にだけpermitを返す() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(ClaimOutcome.Status.CLAIMED, outcome.status());
        assertEquals(1, outcome.transactionAttempts());
        ClaimPermit permit = outcome.permit();
        assertNotNull(permit);
        assertEquals(ticket.claimId(), permit.claimId());
        assertEquals(ticket.claimId(), permit.reservationId());
        assertEquals(T0, permit.claimedAt());
        assertFalse(permit.isConsumed());
        InOrder order = inOrder(ledger, auditor, runMapper, transactionManager);
        order.verify(ledger).ensureBuckets(eq(DispatchClaimFixtures.COST_SCOPE), eq(BucketKeys.of(T0)));
        order.verify(transactionManager).commit(any());
        order.verify(auditor).recordAttempt(any());
        order.verify(runMapper).lockDispatchRow(any());
        order.verify(ledger).lockBucket(AiExternalSendCostBucket.TYPE_DAY, DispatchClaimFixtures.COST_SCOPE,
                "2026-11-15");
        order.verify(ledger).reserve(same(dayBucket), eq(30), eq(10_000L), any(), eq(T0), any());
        order.verify(ledger).lockBucket(AiExternalSendCostBucket.TYPE_MONTH, DispatchClaimFixtures.COST_SCOPE,
                "2026-11");
        order.verify(ledger).reserve(same(monthBucket), eq(30), eq(100_000L), any(), eq(T0), any());
        order.verify(ledger).recordReservation(any());
        order.verify(runMapper).claimDispatch(any(), eq(LocalDateTime.of(2026, 11, 15, 1, 0)));
        order.verify(transactionManager).commit(any());
        verify(auditor, never()).recordResult(any());
        ArgumentCaptor<AiExternalSendCostEntry> entry = ArgumentCaptor.forClass(AiExternalSendCostEntry.class);
        verify(ledger).recordReservation(entry.capture());
        assertEquals(ticket.claimId(), entry.getValue().getReservationId());
        assertEquals("2026-11-15", entry.getValue().getDayKey());
        assertEquals(30, entry.getValue().getReservedYen());
        ArgumentCaptor<AiExternalSendAuditor.SendAudit> attempt = ArgumentCaptor.forClass(AiExternalSendAuditor.SendAudit.class);
        verify(auditor).recordAttempt(attempt.capture());
        assertEquals(ticket.claimId(), attempt.getValue().attemptId(), "ATTEMPTのidempotency_key = claim ID");
        assertNull(attempt.getValue().dayTotalYen(), "ATTEMPTは予約後累計を持たない");
    }

    @Test
    void commitがcallback完了後に失敗したらpermitを返さず読み直しCLAIMEDなら予約維持で再claimしない() {
        DispatchTicket ticket = prepare();
        DispatchRunRow claimed = DispatchClaimFixtures.readyRow(ticket);
        claimed.setDispatchState("CLAIMED");
        // 1回目はclaim transactionのlock、2回目はcommit不明後の排他lock付き読み直し。
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket), claimed);
        failSecondCommit();

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(ClaimOutcome.Status.UNCERTAIN, outcome.status());
        assertNull(outcome.permit());
        assertEquals(DispatchState.CLAIMED, outcome.observedState());
        AiExternalSendAuditor.SendAudit result = captureResult();
        assertEquals(CopilotDispatchClaimService.REASON_CLAIM_UNCERTAIN, result.reasonCode());
        assertEquals("UPPER_BOUND", result.costState());
        assertEquals(30, result.costYen());
        assertEquals(ClaimOutcome.Status.NOT_CLAIMED, service.claim(ticket).status());
        assertEquals(CopilotDispatchClaimService.REASON_ALREADY_ATTEMPTED, service.claim(ticket).reasonCode());
        verify(runMapper, times(2)).lockDispatchRow(any());
        verify(runMapper, never()).readDispatchRow(any());
        verify(runMapper, times(1)).claimDispatch(any(), any());
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(3)).getTransaction(definitions.capture());
        assertFalse(definitions.getAllValues().get(2).isReadOnly(), "排他lock付きの読み直しはread-only transactionにしない");
    }

    @Test
    void commit不明で読み直しがREADYならNOT_SENT_読めなければUNKNOWNとしpermitを返さない() {
        DispatchTicket ready = prepare();
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ready));
        failSecondCommit();
        ClaimOutcome readyOutcome = service.claim(ready);
        assertEquals(ClaimOutcome.Status.UNCERTAIN, readyOutcome.status());
        assertEquals(DispatchState.READY, readyOutcome.observedState());
        assertEquals("NOT_SENT", captureResult().costState());

        setUp();
        DispatchTicket unreadable = prepare();
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(unreadable))
                .thenThrow(new QueryTimeoutException("readback lock wait timeout"));
        failSecondCommit();
        ClaimOutcome unknown = service.claim(unreadable);
        assertEquals(ClaimOutcome.Status.UNCERTAIN, unknown.status());
        assertNull(unknown.observedState());
        assertNull(unknown.permit());
        assertEquals("UNKNOWN", captureResult().costState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dispatch_state", "dispatch_claim_id", "dispatch_provider", "dispatch_nonce_hash",
            "status_version", "status", "dispatch_claimed_at", "dispatch_card_digest", "dispatch_expires_at",
            "tenant_id", "row"})
    void 行の状態やbindingの一列でも発行時snapshotと違えばclaimせずSTALEで予約しない(String column) {
        DispatchTicket ticket = prepare();
        DispatchRunRow row = DispatchClaimFixtures.readyRow(ticket);
        switch (column) {
            case "dispatch_state" -> row.setDispatchState("CLAIMED");
            case "dispatch_claim_id" -> row.setDispatchClaimIdText(UUID.randomUUID().toString());
            case "dispatch_provider" -> row.setDispatchProvider("GEMINI");
            case "dispatch_nonce_hash" -> row.setDispatchNonceHash(new byte[32]);
            case "status_version" -> row.setStatusVersion(1);
            case "status" -> row.setStatus("FAILED");
            case "dispatch_claimed_at" -> row.setDispatchClaimedAt(LocalDateTime.of(2026, 11, 15, 1, 0));
            case "dispatch_card_digest" -> row.setDispatchCardDigest(new byte[32]);
            case "dispatch_expires_at" -> row.setDispatchExpiresAt(row.getDispatchExpiresAt().plusSeconds(1));
            case "tenant_id" -> row.setTenantId("tenant-b");
            case "row" -> row = null;
            default -> throw new IllegalArgumentException(column);
        }
        when(runMapper.lockDispatchRow(any())).thenReturn(row);

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(ClaimOutcome.Status.NOT_CLAIMED, outcome.status());
        assertEquals(CopilotDispatchClaimService.REASON_STALE, outcome.reasonCode());
        assertEquals(1, outcome.transactionAttempts(), "STALEは再試行しない");
        verify(ledger, never()).reserve(any(), anyInt(), anyLong(), any(), any(), any());
        verify(runMapper, never()).claimDispatch(any(), any());
        verify(transactionManager).rollback(any());
        AiExternalSendAuditor.SendAudit result = captureResult();
        assertEquals(409, result.status());
        assertEquals("NOT_SENT", result.costState());
        assertEquals("NOT_STARTED", result.outcome());
    }

    @Test
    void CAS更新が0件ならrollbackしSTALEとする() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));
        when(runMapper.claimDispatch(any(), any())).thenReturn(0);

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(CopilotDispatchClaimService.REASON_STALE, outcome.reasonCode());
        assertNull(outcome.permit());
        verify(transactionManager).rollback(any());
    }

    @Test
    void 日次上限超過は月次とentryへ進まず429のRESULTを残す() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));
        when(ledger.reserve(same(dayBucket), anyInt(), anyLong(), any(), any(), any()))
                .thenReturn(ReserveResult.LIMIT_EXCEEDED);

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(ExternalSendCostLedger.REASON_DAILY, outcome.reasonCode());
        assertEquals(1, outcome.transactionAttempts());
        verify(ledger, never()).lockBucket(eq(AiExternalSendCostBucket.TYPE_MONTH), anyString(), anyString());
        verify(ledger, never()).recordReservation(any());
        verify(runMapper, never()).claimDispatch(any(), any());
        assertEquals(429, captureResult().status());
    }

    @Test
    void 月次上限超過はentryへ進まずrollbackする() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));
        when(ledger.reserve(same(monthBucket), anyInt(), anyLong(), any(), any(), any()))
                .thenReturn(ReserveResult.LIMIT_EXCEEDED);

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(ExternalSendCostLedger.REASON_MONTHLY, outcome.reasonCode());
        verify(ledger, never()).recordReservation(any());
        verify(transactionManager).rollback(any());
    }

    @Test
    void bucket行の欠落はbucketを再準備して同じclaim_IDで再試行しATTEMPTを重複させない() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));
        when(ledger.lockBucket(eq(AiExternalSendCostBucket.TYPE_DAY), anyString(), anyString()))
                .thenReturn(null, dayBucket);

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(ClaimOutcome.Status.CLAIMED, outcome.status());
        assertEquals(2, outcome.transactionAttempts());
        verify(ledger, times(2)).ensureBuckets(eq(DispatchClaimFixtures.COST_SCOPE), any());
        verify(auditor, times(1)).recordAttempt(any());
        verify(runMapper, times(2)).lockDispatchRow(any());
    }

    @Test
    void deadlockが3回続けばLEDGER_UNAVAILABLEで止めATTEMPTとRESULTは一件ずつ() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any())).thenThrow(new DeadlockLoserDataAccessException("1213", null));

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(CopilotDispatchClaimService.REASON_LEDGER_UNAVAILABLE, outcome.reasonCode());
        assertEquals(CopilotDispatchClaimService.MAX_TRANSACTION_ATTEMPTS, outcome.transactionAttempts());
        verify(runMapper, times(3)).lockDispatchRow(any());
        verify(transactionManager, times(3)).rollback(any());
        verify(auditor, times(1)).recordAttempt(any());
        verify(auditor, times(1)).recordResult(any());
    }

    @Test
    void lock待ちtimeoutの後に成功すれば二回目でclaimする() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any()))
                .thenThrow(new CannotAcquireLockException("1205"))
                .thenReturn(DispatchClaimFixtures.readyRow(ticket));

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(ClaimOutcome.Status.CLAIMED, outcome.status());
        assertEquals(2, outcome.transactionAttempts());
    }

    @Test
    void 再試行しない例外はCLAIM_FAILEDで止めpermitを返さない() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));
        doThrow(new DataIntegrityViolationException("duplicate reservation")).when(ledger).recordReservation(any());

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(CopilotDispatchClaimService.REASON_CLAIM_FAILED, outcome.reasonCode());
        assertEquals(1, outcome.transactionAttempts());
        verify(runMapper, never()).claimDispatch(any(), any());
    }

    @Test
    void JST日付を跨いだらrollbackして新しいbucketを事前生成し二回目でclaimする() {
        Instant beforeMidnight = Instant.parse("2026-11-15T14:59:59Z");
        clock.set(beforeMidnight);
        DispatchTicket ticket = service.prepareReady(attempt(7L),
                DispatchClaimFixtures.request(7L, beforeMidnight, nanos.get()));
        // claim開始時のkey決定は11/15（JST 23:59:59.5）、transaction内のfresh Clockは11/16 JST。
        clock.then(Instant.parse("2026-11-15T14:59:59.500Z"));
        clock.then(Instant.parse("2026-11-15T15:00:00.100Z"));
        clock.then(Instant.parse("2026-11-15T15:00:00.200Z"));
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(ClaimOutcome.Status.CLAIMED, outcome.status());
        assertEquals(2, outcome.transactionAttempts());
        InOrder order = inOrder(ledger);
        order.verify(ledger).ensureBuckets(DispatchClaimFixtures.COST_SCOPE,
                BucketKeys.of(Instant.parse("2026-11-15T14:59:59.500Z")));
        order.verify(ledger).ensureBuckets(DispatchClaimFixtures.COST_SCOPE,
                BucketKeys.of(Instant.parse("2026-11-15T15:00:00.100Z")));
        verify(ledger).lockBucket(AiExternalSendCostBucket.TYPE_DAY, DispatchClaimFixtures.COST_SCOPE, "2026-11-16");
        verify(ledger, never()).lockBucket(AiExternalSendCostBucket.TYPE_DAY, DispatchClaimFixtures.COST_SCOPE,
                "2026-11-15");
        verify(auditor, times(1)).recordAttempt(any());
    }

    @Test
    void 時計が発行時刻より前に戻ったらCLOCK_INVALIDで予約しない() {
        DispatchTicket ticket = prepare();
        clock.set(T0.minusMillis(1));
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(CopilotDispatchClaimService.REASON_CLOCK_INVALID, outcome.reasonCode());
        verify(ledger, never()).reserve(any(), anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    void 永続高水位より1秒を超えて前の時刻はCLOCK_INVALIDで1秒以内は許す() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));
        when(ledger.clockHighWater(DispatchClaimFixtures.COST_SCOPE)).thenReturn(T0.plusMillis(1001));
        assertEquals(CopilotDispatchClaimService.REASON_CLOCK_INVALID, service.claim(ticket).reasonCode());

        setUp();
        DispatchTicket tolerated = prepare();
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(tolerated));
        when(ledger.clockHighWater(DispatchClaimFixtures.COST_SCOPE)).thenReturn(T0.plusMillis(1000));
        assertEquals(ClaimOutcome.Status.CLAIMED, service.claim(tolerated).status());
    }

    @Test
    void lock済みbucketの高水位がCLOCK_INVALIDならrollbackする() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));
        when(ledger.reserve(same(monthBucket), anyInt(), anyLong(), any(), any(), any()))
                .thenReturn(ReserveResult.CLOCK_INVALID);

        assertEquals(CopilotDispatchClaimService.REASON_CLOCK_INVALID, service.claim(ticket).reasonCode());
        verify(ledger, never()).recordReservation(any());
    }

    @Test
    void 壁時計の経過がmonotonic経過より1秒を超えて短ければCLOCK_INVALID() {
        DispatchTicket ticket = prepare();
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(5));
        clock.set(T0.plusSeconds(3));
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));

        assertEquals(CopilotDispatchClaimService.REASON_CLOCK_INVALID, service.claim(ticket).reasonCode());
    }

    @Test
    void attempt期限切れとcard有効期間外はclaimしない() {
        DispatchTicket expired = prepare();
        clock.set(expired.expiresAt());
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(2));
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(expired));
        assertEquals(CopilotDispatchClaimService.REASON_ATTEMPT_EXPIRED, service.claim(expired).reasonCode());

        setUp();
        DispatchIssueRequest base = DispatchClaimFixtures.request(7L, T0, nanos.get());
        DispatchIssueRequest futureCard = new DispatchIssueRequest(base.provider(), base.modelRevision(),
                base.region(), base.tier(), base.configHash(), base.artifactVersionId(), base.scopeHash(),
                base.inputHash(), base.preflightSnapshotHash(), base.requestSha256(), base.approvalDigest(),
                base.decisionId(), base.cardId(), base.cardVersion(), base.cardDigest(), T0.plusSeconds(1),
                T0.plusSeconds(3600), base.policyId(), base.policyVersion(), base.policyDigest(),
                base.destinationHost(), base.queryId(), base.legalEntityId(), base.promptBytes(), base.costScope(),
                base.upperBoundYen(), base.dailyLimitYen(), base.monthlyLimitYen(), base.attemptTtl(),
                base.deadlineNanos(), base.approvalSignedOn(), base.approvalExpiresOn());
        DispatchTicket notEffective = service.prepareReady(attempt(7L), futureCard);
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(notEffective));
        assertEquals(CopilotDispatchClaimService.REASON_CARD_NOT_EFFECTIVE, service.claim(notEffective).reasonCode());
    }

    @Test
    void 残deadlineが1秒未満なら事前生成もATTEMPTもtransactionも行わない() {
        DispatchTicket ticket = prepare();
        nanos.set(ticket.request().deadlineNanos() - TimeUnit.MILLISECONDS.toNanos(999));

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(CopilotDispatchClaimService.REASON_DEADLINE, outcome.reasonCode());
        assertEquals(0, outcome.transactionAttempts());
        verify(ledger, never()).ensureBuckets(any(), any());
        verifyNoInteractions(auditor);
    }

    @Test
    void transactionのtimeoutは残deadline以下かつ10秒以下へ切り下げる() {
        // monotonic時刻は進めず（壁時計との逆行判定に掛けない）、deadlineだけを残り3.5秒にする。
        long deadlineIn3500ms = nanos.get() - TimeUnit.MINUTES.toNanos(5) + TimeUnit.MILLISECONDS.toNanos(3500);
        DispatchTicket ticket = service.prepareReady(attempt(7L),
                DispatchClaimFixtures.request(7L, T0, deadlineIn3500ms));
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);

        assertTrue(service.claim(ticket).claimed());

        verify(transactionManager, atLeastOnce()).getTransaction(definitions.capture());
        assertTrue(definitions.getAllValues().stream().allMatch(definition -> definition.getTimeout() == 3));
    }

    @Test
    void ATTEMPT監査に失敗したらclaim_transactionを開始せずRESULTも記録しない() {
        DispatchTicket ticket = prepare();
        doThrow(new IllegalStateException("audit down")).when(auditor).recordAttempt(any());

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(CopilotDispatchClaimService.REASON_AUDIT_UNAVAILABLE, outcome.reasonCode());
        verify(runMapper, never()).lockDispatchRow(any());
        verify(auditor, never()).recordResult(any());
    }

    @Test
    void bucketの事前生成が再試行できない失敗ならATTEMPT前に止める() {
        DispatchTicket ticket = prepare();
        doThrow(new DataIntegrityViolationException("check")).when(ledger).ensureBuckets(any(), any());

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(CopilotDispatchClaimService.REASON_LEDGER_UNAVAILABLE, outcome.reasonCode());
        verifyNoInteractions(auditor);
        verify(runMapper, never()).lockDispatchRow(any());
    }

    @Test
    void transaction内からの呼出しは何も記録せずticketも消費しない() {
        DispatchTicket ticket = prepare();
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(CopilotDispatchClaimService.REASON_TRANSACTION_ACTIVE, service.claim(ticket).reasonCode());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNoInteractions(auditor);
        verify(ledger, never()).ensureBuckets(any(), any());
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));
        assertTrue(service.claim(ticket).claimed(), "transaction外からの一度目は実行できる");
    }

    @Test
    void tenant不一致と同じticketの再利用はclaimしない() {
        DispatchTicket ticket = prepare();
        AccountingTenantContextHolder.setTenantId("tenant-b");
        assertEquals(CopilotDispatchClaimService.REASON_CONTEXT, service.claim(ticket).reasonCode());
        AccountingTenantContextHolder.setTenantId(TENANT);
        assertEquals(CopilotDispatchClaimService.REASON_ALREADY_ATTEMPTED, service.claim(ticket).reasonCode(),
                "一度claimを開始したticketは再利用できない");
        verifyNoInteractions(auditor);
    }

    @Test
    void 再試行してよい失敗の判定は1213_1205_timeoutだけ() {
        assertTrue(CopilotDispatchClaimService.isTransient(new DeadlockLoserDataAccessException("x", null)));
        assertTrue(CopilotDispatchClaimService.isTransient(new CannotAcquireLockException("x")));
        assertTrue(CopilotDispatchClaimService.isTransient(new QueryTimeoutException("x")));
        assertTrue(CopilotDispatchClaimService.isTransient(new RuntimeException(new SQLException("x", "40001", 1213))));
        assertTrue(CopilotDispatchClaimService.isTransient(new RuntimeException(new SQLException("x", "HY000", 1205))));
        assertFalse(CopilotDispatchClaimService.isTransient(new DataIntegrityViolationException("x")));
        assertFalse(CopilotDispatchClaimService.isTransient(new IllegalStateException("x")));
        assertFalse(CopilotDispatchClaimService.isTransient(new TransactionSystemException("x")));
        // Connector/JのMySQLTimeoutExceptionはjava.sql.SQLTimeoutExceptionの派生（SQLTransientException）。
        assertTrue(CopilotDispatchClaimService.isTransient(new RuntimeException(new java.sql.SQLTimeoutException("x"))));
        // statement timeoutの後にrollbackも失敗した形（rollbackの例外が元のtimeoutを覆う）。
        assertTrue(CopilotDispatchClaimService.isTransient(rollbackFailedAfter(
                new QueryTimeoutException("x", new java.sql.SQLTimeoutException("cancelled")))));
        assertTrue(CopilotDispatchClaimService.isTransient(rollbackFailedAfter(
                new RuntimeException(new java.sql.SQLTimeoutException("cancelled")))));
        assertFalse(CopilotDispatchClaimService.isTransient(rollbackFailedAfter(
                new DataIntegrityViolationException("x"))), "元の例外が再試行できないものなら再試行しない");
        assertEquals("TransactionSystemException<-QueryTimeoutException", CopilotDispatchClaimService.failureType(
                rollbackFailedAfter(new QueryTimeoutException("x"))));
    }

    @Test
    void statement_timeoutの後にrollbackも失敗しても元の例外を辿って同じticketで再試行する() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any()))
                .thenThrow(new QueryTimeoutException("cancelled", new java.sql.SQLTimeoutException("cancelled")))
                .thenReturn(DispatchClaimFixtures.readyRow(ticket));
        failRollbackLikeBrokenConnection();

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(ClaimOutcome.Status.CLAIMED, outcome.status());
        assertEquals(2, outcome.transactionAttempts());
        verify(auditor, times(1)).recordAttempt(any());
    }

    @Test
    void statement_timeoutの後に残deadlineが1秒未満ならDEADLINEで止め予約せずNOT_SENTのRESULTを残す() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any())).thenAnswer(invocation -> {
            // lock待ちがtimeoutまで続き、その間にdeadlineの残りが1秒を切った。
            nanos.set(ticket.request().deadlineNanos() - TimeUnit.MILLISECONDS.toNanos(500));
            throw new QueryTimeoutException("cancelled", new java.sql.SQLTimeoutException("cancelled"));
        });
        failRollbackLikeBrokenConnection();

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(ClaimOutcome.Status.NOT_CLAIMED, outcome.status());
        assertEquals(CopilotDispatchClaimService.REASON_DEADLINE, outcome.reasonCode());
        assertEquals(1, outcome.transactionAttempts());
        assertNull(outcome.permit());
        verify(ledger, never()).recordReservation(any());
        verify(runMapper, never()).claimDispatch(any(), any());
        AiExternalSendAuditor.SendAudit result = captureResult();
        assertEquals(CopilotDispatchClaimService.REASON_DEADLINE, result.reasonCode());
        assertEquals("NOT_SENT", result.costState());
    }

    @Test
    void statement_timeoutとrollback失敗が3回続けばLEDGER_UNAVAILABLEでCLAIM_FAILEDにしない() {
        DispatchTicket ticket = prepare();
        when(runMapper.lockDispatchRow(any())).thenAnswer(invocation -> {
            throw new QueryTimeoutException("cancelled", new java.sql.SQLTimeoutException("cancelled"));
        });
        failRollbackLikeBrokenConnection();

        ClaimOutcome outcome = service.claim(ticket);

        assertEquals(CopilotDispatchClaimService.REASON_LEDGER_UNAVAILABLE, outcome.reasonCode());
        assertEquals(CopilotDispatchClaimService.MAX_TRANSACTION_ATTEMPTS, outcome.transactionAttempts());
        verify(auditor, times(1)).recordResult(any());
    }

    /** Hikariが壊れた接続として閉じた後のrollback失敗（DataSourceTransactionManagerの例外の形）。毎回新しい例外を投げる。 */
    private void failRollbackLikeBrokenConnection() {
        doAnswer(invocation -> {
            throw new TransactionSystemException("Could not roll back JDBC transaction",
                    new SQLException("Connection is closed", "08003"));
        }).when(transactionManager).rollback(any());
    }

    private static TransactionSystemException rollbackFailedAfter(RuntimeException application) {
        TransactionSystemException failure = new TransactionSystemException("Could not roll back JDBC transaction",
                new SQLException("Connection is closed", "08003"));
        failure.initApplicationException(application);
        return failure;
    }

    @Test
    void 承認の失効日当日はJST業務日で拒否し予約せずNOT_SENTのRESULTを残す() {
        // T0 = 2026-11-15T01:00Z（JST 11/15 10:00）。失効日11/15は当日から無効。
        assertApprovalRefused(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 15), T0);
        // UTCでは11/15だがJSTでは11/16 0:00。失効日11/16はJST業務日で判定するため無効。
        setUp();
        assertApprovalRefused(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 16),
                Instant.parse("2026-11-15T15:00:00Z"));
    }

    @Test
    void 承認の署名日前はclaimせず予約しない() {
        assertApprovalRefused(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 12, 31), T0);
    }

    @Test
    void 承認の署名日当日と失効日前日の有効期間内はclaimできる() {
        assertTrue(claimWithApproval(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 12, 31), T0).claimed(),
                "署名日当日");
        setUp();
        assertTrue(claimWithApproval(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 16), T0).claimed(),
                "失効日前日");
    }

    @Test
    void DAY_lockの待機で進んだ時刻を読み直して予約とclaim時刻に使い高水位の逆行と誤判定しない() {
        DispatchTicket ticket = prepare();
        Instant afterLock = T0.plusMillis(2500);
        clock.then(T0);          // claim開始時のbucket key
        clock.then(T0);          // run行lock後のfresh Clock
        clock.then(afterLock);   // DAY bucketのlock取得後（待機中に他のclaimが高水位を進め得る）
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));

        ClaimOutcome outcome = service.claim(ticket);

        assertTrue(outcome.claimed());
        assertEquals(afterLock, outcome.permit().claimedAt());
        verify(ledger).reserve(same(dayBucket), anyInt(), anyLong(), any(), eq(afterLock), any());
        verify(ledger).reserve(same(monthBucket), anyInt(), anyLong(), any(), eq(afterLock), any());
        verify(runMapper).claimDispatch(any(), eq(LocalDateTime.ofInstant(afterLock, ZoneOffset.UTC)));
    }

    @Test
    void DAY_lock取得後の時刻が前より戻るかattempt期限を過ぎていれば予約せずrollbackする() {
        DispatchTicket ticket = prepare();
        clock.then(T0.plusSeconds(1));
        clock.then(T0.plusSeconds(1));
        clock.then(T0.plusMillis(500));
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));
        assertEquals(CopilotDispatchClaimService.REASON_CLOCK_INVALID, service.claim(ticket).reasonCode());
        verify(ledger, never()).reserve(any(), anyInt(), anyLong(), any(), any(), any());

        setUp();
        DispatchTicket expiring = prepare();
        clock.then(T0);
        clock.then(T0);
        clock.then(expiring.expiresAt());
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(expiring));
        assertEquals(CopilotDispatchClaimService.REASON_ATTEMPT_EXPIRED, service.claim(expiring).reasonCode());
        verify(ledger, never()).reserve(any(), anyInt(), anyLong(), any(), any(), any());
        verify(transactionManager).rollback(any());
    }

    private ClaimOutcome claimWithApproval(LocalDate signedOn, LocalDate expiresOn, Instant at) {
        clock.set(at);
        DispatchTicket ticket = service.prepareReady(attempt(7L), DispatchClaimFixtures.withApproval(
                DispatchClaimFixtures.request(7L, at, nanos.get()), signedOn, expiresOn));
        when(runMapper.lockDispatchRow(any())).thenReturn(DispatchClaimFixtures.readyRow(ticket));
        return service.claim(ticket);
    }

    private void assertApprovalRefused(LocalDate signedOn, LocalDate expiresOn, Instant at) {
        ClaimOutcome outcome = claimWithApproval(signedOn, expiresOn, at);
        assertEquals(ClaimOutcome.Status.NOT_CLAIMED, outcome.status());
        assertEquals(CopilotDispatchClaimService.REASON_APPROVAL_NOT_EFFECTIVE, outcome.reasonCode());
        assertNull(outcome.permit());
        assertEquals(1, outcome.transactionAttempts(), "承認の失効は再試行しない");
        verify(ledger, never()).lockBucket(any(), any(), any());
        verify(ledger, never()).reserve(any(), anyInt(), anyLong(), any(), any(), any());
        verify(ledger, never()).recordReservation(any());
        verify(runMapper, never()).claimDispatch(any(), any());
        AiExternalSendAuditor.SendAudit result = captureResult();
        assertEquals(CopilotDispatchClaimService.REASON_APPROVAL_NOT_EFFECTIVE, result.reasonCode());
        assertEquals("NOT_SENT", result.costState());
        assertEquals(0, result.costYen());
        assertEquals("NOT_STARTED", result.outcome());
    }

    private DispatchTicket prepare() {
        return service.prepareReady(attempt(7L), DispatchClaimFixtures.request(7L, T0, nanos.get()));
    }

    private com.ses.service.ai.copilot.CopilotRunService.CopilotAttemptRecord attempt(long artifactVersionId) {
        return DispatchClaimFixtures.summaryAttempt(41L, UUID.randomUUID().toString(), TENANT, artifactVersionId);
    }

    /** 1回目（bucket事前生成）は成功、2回目（claim）はcallback完了後のcommitで失敗、以降は成功。 */
    private void failSecondCommit() {
        AtomicInteger commits = new AtomicInteger();
        doAnswer(invocation -> {
            if (commits.incrementAndGet() == 2) {
                throw new TransactionSystemException("simulated commit acknowledgement loss");
            }
            return null;
        }).when(transactionManager).commit(any());
    }

    private AiExternalSendAuditor.SendAudit captureResult() {
        ArgumentCaptor<AiExternalSendAuditor.SendAudit> result = ArgumentCaptor.forClass(AiExternalSendAuditor.SendAudit.class);
        verify(auditor).recordResult(result.capture());
        List<AiExternalSendAuditor.SendAudit> values = result.getAllValues();
        return values.get(values.size() - 1);
    }
}
