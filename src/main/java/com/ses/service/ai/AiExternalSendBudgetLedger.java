package com.ses.service.ai;

import com.ses.common.exception.BusinessException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 外部送信費用のJST日次/月次累計。送信前に上限見積りを予約し、応答後にusageの実費へ精算する。
 * <p>制約: 単一プロセス内のメモリ計数であり、再起動で累計は0に戻り、複数instance間でも共有しない。
 * 永続化（再起動跨ぎ・instance間共有）は利用者決定Q-11により専用の永続費用ledger table（DDL、B2c-1と同じ
 * migration。版番号は未割当で暫定V191）で行う。設計は
 * {@code .kiro/roadmap/2026-10-18-snf-nf08-c2-b2-technical-plan.md} の「永続費用ledger」節を参照。
 * それまでの運用は単一instanceとし、provider側の硬い上限（GCP API quota。budget alertは通知だけで止めない）を併用する。</p>
 * <p>時計が戻った場合は記録済みの日付・月を維持し、累計を0へ戻さない（時計の逆行で上限を回避させない）。</p>
 */
public final class AiExternalSendBudgetLedger {

    public static final String REASON_DAILY = "AI_EXTERNAL_SEND_DAILY_COST_LIMIT";
    public static final String REASON_MONTHLY = "AI_EXTERNAL_SEND_MONTHLY_COST_LIMIT";

    private final AiExternalSendApproval.CostLimitSnapshot limit;
    private final Clock clock;
    private LocalDate day;
    private long dayTotalYen;
    private YearMonth month;
    private long monthTotalYen;

    public AiExternalSendBudgetLedger(AiExternalSendApproval.CostLimitSnapshot limit, Clock clock) {
        this.limit = Objects.requireNonNull(limit, "費用上限が必要です");
        this.clock = clock == null ? Clock.system(AiExternalSendApproval.BUSINESS_ZONE) : clock;
    }

    /** 読取専用の事前判定。超過する場合は拒否理由、余裕がある場合はnullを返す。 */
    public synchronized String exceededReason(int yen) {
        roll();
        return reasonFor(yen);
    }

    /** 上限見積りを原子的に予約する。日次・月次のどちらかを超える場合は429で拒否する。 */
    public synchronized Reservation reserve(int yen) {
        if (yen < 0) {
            throw new IllegalArgumentException("予約額は0円以上である必要があります");
        }
        roll();
        String reason = reasonFor(yen);
        if (reason != null) {
            throw BusinessException.of(429, reason);
        }
        dayTotalYen += yen;
        monthTotalYen += yen;
        return new Reservation(day, month, yen);
    }

    /** 未送信が確定した予約を戻す。同じ予約の二重精算は無視する。 */
    public synchronized void release(Reservation reservation) {
        if (reservation == null || !reservation.close()) {
            return;
        }
        roll();
        adjust(reservation, -reservation.reservedYen());
    }

    /**
     * 送信済み（または送信の可能性あり）の予約を精算する。実費が不明（null）なら上限見積りを費用として維持する。
     * 実費が見積りを超えた場合も実費で計上し、以降の予約で上限超過として拒否させる。
     */
    public synchronized void settle(Reservation reservation, Integer actualYen) {
        if (reservation == null || !reservation.close()) {
            return;
        }
        if (actualYen == null || actualYen < 0) {
            return;
        }
        roll();
        adjust(reservation, (long) actualYen - reservation.reservedYen());
    }

    public synchronized Totals totals() {
        roll();
        return new Totals(day, dayTotalYen, month, monthTotalYen);
    }

    private void adjust(Reservation reservation, long deltaYen) {
        if (reservation.day().equals(day)) {
            dayTotalYen = Math.max(0, dayTotalYen + deltaYen);
        }
        if (reservation.month().equals(month)) {
            monthTotalYen = Math.max(0, monthTotalYen + deltaYen);
        }
    }

    private String reasonFor(int yen) {
        if (dayTotalYen + yen > limit.dailyYen()) {
            return REASON_DAILY;
        }
        if (monthTotalYen + yen > limit.monthlyYen()) {
            return REASON_MONTHLY;
        }
        return null;
    }

    /**
     * JST日付・月が進んだ場合だけ累計を切り替える。時計が戻った場合は記録済みの（より新しい）日付・月の累計を
     * そのまま使い、0へ戻さない（fail-closed）。
     */
    private void roll() {
        LocalDate today = AiExternalSendApproval.businessDate(clock);
        if (day == null || today.isAfter(day)) {
            day = today;
            dayTotalYen = 0;
        }
        YearMonth current = YearMonth.from(day);
        if (month == null || current.isAfter(month)) {
            month = current;
            monthTotalYen = 0;
        }
    }

    /** 1回の外部送信の予約。精算・取消は一度だけ有効。 */
    public static final class Reservation {
        private final LocalDate day;
        private final YearMonth month;
        private final int reservedYen;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Reservation(LocalDate day, YearMonth month, int reservedYen) {
            this.day = day;
            this.month = month;
            this.reservedYen = reservedYen;
        }

        public LocalDate day() { return day; }
        public YearMonth month() { return month; }
        public int reservedYen() { return reservedYen; }
        public boolean isOpen() { return open.get(); }

        private boolean close() {
            return open.compareAndSet(true, false);
        }
    }

    /** 現在のJST日・月の累計（円）。 */
    public record Totals(LocalDate day, long dayYen, YearMonth month, long monthYen) {
    }
}
