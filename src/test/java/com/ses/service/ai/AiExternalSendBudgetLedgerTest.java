package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** NF08: 外部送信費用は日次/月次上限で予約時に拒否し、精算・取消は一度だけ反映する。 */
class AiExternalSendBudgetLedgerTest {

    private static final AiExternalSendApproval.CostLimitSnapshot LIMIT = new AiExternalSendApproval.CostLimitSnapshot(
            10, 20, 30, BigDecimal.ONE, BigDecimal.ONE, 4096, 0);

    @Test
    void 日次上限を超える予約は429で拒否し累計を変えない() {
        AiExternalSendBudgetLedger ledger = new AiExternalSendBudgetLedger(LIMIT, fixed("2026-11-13T01:00:00Z"));

        ledger.reserve(10);
        ledger.reserve(10);
        assertEquals(AiExternalSendBudgetLedger.REASON_DAILY, ledger.exceededReason(1));
        BusinessException ex = assertThrows(BusinessException.class, () -> ledger.reserve(1));

        assertEquals(429, ex.getCode());
        assertEquals(AiExternalSendBudgetLedger.REASON_DAILY, ex.getMessage());
        assertEquals(20, ledger.totals().dayYen());
        assertEquals(20, ledger.totals().monthYen());
    }

    @Test
    void 月次上限は日を跨いでも累計しJST日付で日次だけ戻る() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-13T01:00:00Z"));
        AiExternalSendBudgetLedger ledger = new AiExternalSendBudgetLedger(LIMIT, clock);
        ledger.reserve(10);
        ledger.reserve(10);

        // 2026-11-13 23:59 JST はまだ同じ日
        clock.set(Instant.parse("2026-11-13T14:59:00Z"));
        assertEquals(AiExternalSendBudgetLedger.REASON_DAILY, ledger.exceededReason(1));
        // 2026-11-14 00:00 JST で日次が戻る
        clock.set(Instant.parse("2026-11-13T15:00:00Z"));
        assertNull(ledger.exceededReason(10));
        ledger.reserve(10);
        assertEquals(AiExternalSendBudgetLedger.REASON_MONTHLY, ledger.exceededReason(1));
        assertEquals(AiExternalSendBudgetLedger.REASON_MONTHLY,
                assertThrows(BusinessException.class, () -> ledger.reserve(1)).getMessage());
        // 翌月 1日 JST で月次も戻る
        clock.set(Instant.parse("2026-11-30T15:00:00Z"));
        assertNull(ledger.exceededReason(10));
        assertEquals(0, ledger.totals().monthYen());
    }

    @Test
    void 未送信の取消は予約を戻し二重取消を無視する() {
        AiExternalSendBudgetLedger ledger = new AiExternalSendBudgetLedger(LIMIT, fixed("2026-11-13T01:00:00Z"));
        AiExternalSendBudgetLedger.Reservation reservation = ledger.reserve(10);

        ledger.release(reservation);
        ledger.release(reservation);
        ledger.settle(reservation, 5);

        assertFalse(reservation.isOpen());
        assertEquals(0, ledger.totals().dayYen());
    }

    @Test
    void 実費精算は差額を反映し実費不明なら上限見積りを維持する() {
        AiExternalSendBudgetLedger ledger = new AiExternalSendBudgetLedger(LIMIT, fixed("2026-11-13T01:00:00Z"));
        AiExternalSendBudgetLedger.Reservation known = ledger.reserve(10);
        AiExternalSendBudgetLedger.Reservation unknown = ledger.reserve(5);

        ledger.settle(known, 3);
        ledger.settle(unknown, null);

        assertEquals(8, ledger.totals().dayYen());
        assertEquals(8, ledger.totals().monthYen());
    }

    @Test
    void 実費が見積りを超えた場合も実費で計上する() {
        AiExternalSendBudgetLedger ledger = new AiExternalSendBudgetLedger(LIMIT, fixed("2026-11-13T01:00:00Z"));
        AiExternalSendBudgetLedger.Reservation reservation = ledger.reserve(5);

        ledger.settle(reservation, 12);

        assertEquals(12, ledger.totals().dayYen());
        assertEquals(AiExternalSendBudgetLedger.REASON_DAILY, ledger.exceededReason(9));
    }

    @Test
    void 時計が戻っても日次と月次の累計を0へ戻さない() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-14T01:00:00Z"));
        AiExternalSendBudgetLedger ledger = new AiExternalSendBudgetLedger(LIMIT, clock);
        ledger.reserve(10);
        ledger.reserve(10);

        // NTP補正等で前日（2026-11-13 JST）へ戻っても、記録済みの日付の累計で判定する。
        clock.set(Instant.parse("2026-11-13T01:00:00Z"));
        assertEquals(AiExternalSendBudgetLedger.REASON_DAILY, ledger.exceededReason(1));
        assertEquals(AiExternalSendBudgetLedger.REASON_DAILY,
                assertThrows(BusinessException.class, () -> ledger.reserve(1)).getMessage());
        assertEquals(20, ledger.totals().dayYen());
        assertEquals(java.time.LocalDate.of(2026, 11, 14), ledger.totals().day());

        // 前月（2026-10 JST）へ戻っても月次累計を戻さない。
        clock.set(Instant.parse("2026-10-31T01:00:00Z"));
        assertEquals(20, ledger.totals().monthYen());
        assertEquals(java.time.YearMonth.of(2026, 11), ledger.totals().month());

        // 時計が再び進めば、記録済みの日付より後になった時点で日次だけ戻る。
        clock.set(Instant.parse("2026-11-14T15:00:00Z"));
        assertNull(ledger.exceededReason(10));
        assertEquals(20, ledger.totals().monthYen());
    }

    @Test
    void 時計が戻った後の予約も精算と取消を正しく反映する() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-14T01:00:00Z"));
        AiExternalSendBudgetLedger ledger = new AiExternalSendBudgetLedger(LIMIT, clock);
        AiExternalSendBudgetLedger.Reservation before = ledger.reserve(5);
        clock.set(Instant.parse("2026-11-13T01:00:00Z"));
        AiExternalSendBudgetLedger.Reservation after = ledger.reserve(5);

        ledger.release(after);
        ledger.settle(before, 2);

        assertEquals(2, ledger.totals().dayYen());
        assertEquals(2, ledger.totals().monthYen());
    }

    @Test
    void 負の予約額は受け付けない() {
        AiExternalSendBudgetLedger ledger = new AiExternalSendBudgetLedger(LIMIT, fixed("2026-11-13T01:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> ledger.reserve(-1));
    }

    private static Clock fixed(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    /** テスト用の可変時計。 */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
