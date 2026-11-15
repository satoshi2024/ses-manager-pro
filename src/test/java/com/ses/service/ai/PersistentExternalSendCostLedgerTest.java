package com.ses.service.ai;

import com.ses.config.AiConfig;
import com.ses.entity.AiExternalSendCostBucket;
import com.ses.entity.AiExternalSendCostEntry;
import com.ses.mapper.AiExternalSendCostBucketMapper;
import com.ses.mapper.AiExternalSendCostEntryMapper;
import com.ses.service.ai.PersistentExternalSendCostLedger.BucketKeys;
import com.ses.service.ai.PersistentExternalSendCostLedger.ReserveResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** NF08 永続費用ledgerの分岐（mock mapper）。条件付きUPDATE・lock・一意制約の実挙動はMySQL gateで確認する。 */
class PersistentExternalSendCostLedgerTest {

    private static final Instant NOW = Instant.parse("2026-11-15T01:00:00Z");
    private static final Duration TOLERANCE = Duration.ofSeconds(1);

    private AiExternalSendCostBucketMapper bucketMapper;
    private AiExternalSendCostEntryMapper entryMapper;
    private PersistentExternalSendCostLedger ledger;

    @BeforeEach
    void setUp() {
        bucketMapper = mock(AiExternalSendCostBucketMapper.class);
        entryMapper = mock(AiExternalSendCostEntryMapper.class);
        ledger = new PersistentExternalSendCostLedger(bucketMapper, entryMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void 永続ledgerとin_memory_ledgerは方式を区別して示す() {
        assertEquals(ExternalSendCostLedger.Kind.PERSISTENT, ledger.kind());
        AiExternalSendBudgetLedger memory = new AiExternalSendBudgetLedger(
                new AiExternalSendApproval.CostLimitSnapshot(10, 100, 1000, java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ONE, 4096, 0), Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(ExternalSendCostLedger.Kind.MEMORY, memory.kind());
        assertEquals(AiConfig.COST_LEDGER_PERSISTENT, new AiConfig().getExternalSendCostLedger(), "既定はpersistent");
    }

    @Test
    void 更新とlockを伴う操作は呼出側transactionの外では実行しない() {
        BucketKeys keys = BucketKeys.of(NOW);
        assertThrows(IllegalStateException.class, () -> ledger.ensureBuckets("gemini", keys));
        assertThrows(IllegalStateException.class, () -> ledger.clockHighWater("gemini"));
        assertThrows(IllegalStateException.class, () -> ledger.reserve(AiExternalSendCostBucket.TYPE_DAY, "gemini",
                keys.dayKey(), 10, 100, new byte[32], NOW, TOLERANCE));
        assertThrows(IllegalStateException.class, () -> ledger.recordReservation(new AiExternalSendCostEntry()));
        verifyNoInteractions(bucketMapper, entryMapper);
    }

    @Test
    void bucketの欠落と上限超過と高水位の逆行を区別する() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertEquals(ReserveResult.BUCKET_MISSING, ledger.reserve(AiExternalSendCostBucket.TYPE_DAY, "gemini",
                "2026-11-15", 10, 100, new byte[32], NOW, TOLERANCE));

        AiExternalSendCostBucket bucket = bucket(7L, LocalDateTime.ofInstant(NOW.plusMillis(1001), ZoneOffset.UTC));
        when(bucketMapper.lockBucket("gemini", "DAY", "2026-11-15")).thenReturn(bucket);
        assertEquals(ReserveResult.CLOCK_INVALID, ledger.reserve(AiExternalSendCostBucket.TYPE_DAY, "gemini",
                "2026-11-15", 10, 100, new byte[32], NOW, TOLERANCE));
        verify(bucketMapper, never()).reserve(any(), anyLong(), anyLong(), any(), any());

        bucket.setClockHighWaterAt(LocalDateTime.ofInstant(NOW.plusMillis(1000), ZoneOffset.UTC));
        when(bucketMapper.reserve(eq(7L), eq(10L), eq(100L), any(), any())).thenReturn(0, 1);
        assertEquals(ReserveResult.LIMIT_EXCEEDED, ledger.reserve(AiExternalSendCostBucket.TYPE_DAY, "gemini",
                "2026-11-15", 10, 100, new byte[32], NOW, TOLERANCE));
        assertEquals(ReserveResult.RESERVED, ledger.reserve(AiExternalSendCostBucket.TYPE_DAY, "gemini",
                "2026-11-15", 10, 100, new byte[32], NOW, TOLERANCE));
    }

    @Test
    void 費用予約見積りは1円以上で適用範囲は固定文字集合だけ() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalArgumentException.class, () -> ledger.reserve(AiExternalSendCostBucket.TYPE_DAY,
                "gemini", "2026-11-15", 0, 100, new byte[32], NOW, TOLERANCE));
        assertThrows(IllegalArgumentException.class, () -> ledger.reserve(AiExternalSendCostBucket.TYPE_DAY,
                "Gemini Prod", "2026-11-15", 1, 100, new byte[32], NOW, TOLERANCE));
        AiExternalSendCostEntry zero = new AiExternalSendCostEntry();
        zero.setReservedYen(0);
        assertThrows(IllegalArgumentException.class, () -> ledger.recordReservation(zero));
        verifyNoInteractions(entryMapper);
    }

    @Test
    void 業務日と月のkeyはJSTで決まりUTCの日付に引きずられない() {
        BucketKeys beforeMidnight = BucketKeys.of(Instant.parse("2026-11-30T14:59:59.999Z"));
        BucketKeys afterMidnight = BucketKeys.of(Instant.parse("2026-11-30T15:00:00Z"));
        assertEquals("2026-11-30", beforeMidnight.dayKey());
        assertEquals("2026-11", beforeMidnight.monthKey());
        assertEquals("2026-12-01", afterMidnight.dayKey());
        assertEquals("2026-12", afterMidnight.monthKey());
        assertThrows(IllegalArgumentException.class,
                () -> new BucketKeys(LocalDate.of(2026, 11, 30), YearMonth.of(2026, 12)));
    }

    @Test
    void 合計は現在のJST日と月のbucketを読みbucketが無ければ0円() {
        AiExternalSendCostBucket day = bucket(1L, null);
        day.setCommittedYen(120L);
        when(bucketMapper.selectBucket("gemini", "DAY", "2026-11-15")).thenReturn(day);
        ExternalSendCostLedger.Totals totals = ledger.totals("gemini");
        assertEquals(LocalDate.of(2026, 11, 15), totals.day());
        assertEquals(120L, totals.dayYen());
        assertEquals(YearMonth.of(2026, 11), totals.month());
        assertEquals(0L, totals.monthYen());
    }

    private static AiExternalSendCostBucket bucket(long id, LocalDateTime highWater) {
        AiExternalSendCostBucket bucket = new AiExternalSendCostBucket();
        bucket.setId(id);
        bucket.setCommittedYen(0L);
        bucket.setClockHighWaterAt(highWater);
        return bucket;
    }
}
