package com.ses.service.ai;

import com.ses.entity.AiExternalSendCostBucket;
import com.ses.entity.AiExternalSendCostEntry;
import com.ses.mapper.AiExternalSendCostBucketMapper;
import com.ses.mapper.AiExternalSendCostEntryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * NF08 外部送信費用の<b>永続ledger</b>（V194、{@link ExternalSendCostLedger.Kind#PERSISTENT}、利用者決定Q-11）。
 * <p>cost_scope（provider単位、全tenant・全承認で共有）ごとのJST日次・月次bucketと、送信1回ごとの予約entryで上限を守る。
 * 予約は {@code committed_yen + 見積り <= 上限} の条件付きUPDATEで原子的に行い、プロセス内の計数を上限に使わない。</p>
 * <p>本classはtransactionを自分で開始しない。mutationとlockを伴う読取りは呼出側（{@code CopilotDispatchClaimService}）の
 * transaction内でだけ受け付け、lock順 run → DAY → MONTH → entry は呼出側が固定する。bucket行の存在保証
 * （{@link #ensureBuckets}）もclaimとは別の短いtransactionで呼出側が開始する。</p>
 * <p>時刻はアプリの {@link Clock} だけを使い、DBの {@code NOW()}・自動 {@code updated_at} を基準にしない。
 * 高水位（{@code clock_high_water_at}）は増やす方向にだけ更新し、時計が戻っても下げない。</p>
 * <p>B2c-1ではgatewayへ接続しない（B2c-2）。精算（RESERVED → SETTLED / KEPT_UPPER_BOUND）はB2dで追加する。</p>
 */
@Service
public class PersistentExternalSendCostLedger implements ExternalSendCostLedger {

    /** bucket行が無い（事前生成の後に消えた等）。再試行可能。 */
    public static final String REASON_BUCKET_MISSING = "LEDGER_BUCKET_MISSING";

    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AiExternalSendCostBucketMapper bucketMapper;
    private final AiExternalSendCostEntryMapper entryMapper;
    private final Clock clock;

    @Autowired
    public PersistentExternalSendCostLedger(AiExternalSendCostBucketMapper bucketMapper,
                                            AiExternalSendCostEntryMapper entryMapper, Clock clock) {
        this.bucketMapper = Objects.requireNonNull(bucketMapper);
        this.entryMapper = Objects.requireNonNull(entryMapper);
        this.clock = clock == null ? Clock.system(AiExternalSendApproval.BUSINESS_ZONE) : clock;
    }

    @Override
    public Kind kind() {
        return Kind.PERSISTENT;
    }

    /** 現在のJST業務日・月の累計（lockしない読取り）。bucketが無ければ0円。 */
    @Override
    public Totals totals(String costScope) {
        BucketKeys keys = BucketKeys.of(clock.instant());
        AiExternalSendCostBucket day = bucketMapper.selectBucket(costScope, AiExternalSendCostBucket.TYPE_DAY,
                keys.dayKey());
        AiExternalSendCostBucket month = bucketMapper.selectBucket(costScope, AiExternalSendCostBucket.TYPE_MONTH,
                keys.monthKey());
        return new Totals(keys.day(), committed(day), keys.month(), committed(month));
    }

    /** DAY・MONTHの2行を事前生成する（claimとは別の短いtransaction内で呼ぶ）。 */
    public void ensureBuckets(String costScope, BucketKeys keys) {
        requireTransaction();
        bucketMapper.ensureBuckets(requireScope(costScope), keys.dayKey(), keys.monthKey());
    }

    /** 同じcost_scopeの全bucketの時刻高水位（UTC）。履歴が無ければnull。claim transaction内で呼ぶ。 */
    public Instant clockHighWater(String costScope) {
        requireTransaction();
        LocalDateTime highWater = bucketMapper.selectClockHighWater(requireScope(costScope));
        return highWater == null ? null : highWater.toInstant(ZoneOffset.UTC);
    }

    /**
     * lock順の2番目・3番目（DAY → MONTH）のlock取得。bucket行を排他lockして読む。行が無ければnull
     * （再試行可能なbucket欠落）。呼出側はlockの待機中に進んだ時刻を、取得後にClockから読み直してから
     * {@link #reserve} へ渡す（待機が長いと、先にcommitした予約が高水位を進めているため）。
     */
    public AiExternalSendCostBucket lockBucket(String bucketType, String costScope, String bucketKey) {
        requireTransaction();
        return bucketMapper.lockBucket(requireScope(costScope), bucketType, bucketKey);
    }

    /**
     * {@link #lockBucket} でlockしたbucket行へ、上限内でだけ予約を積む。
     *
     * @param locked     同じtransactionでlock済みの行（nullならBUCKET_MISSING）
     * @param observedAt lock取得後に読んだアプリClockの観測時刻（UTC、マイクロ秒）。高水位の更新と逆行検査に使う
     * @param tolerance  高水位より前の観測をどこまで許すか（計画 R-6: 1秒）
     */
    public ReserveResult reserve(AiExternalSendCostBucket locked, int yen, long limitYen, byte[] approvalDigest,
                                 Instant observedAt, Duration tolerance) {
        requireTransaction();
        if (yen < 1) {
            throw new IllegalArgumentException("費用予約見積りは1円以上である必要があります");
        }
        if (limitYen < 0) {
            throw new IllegalArgumentException("費用上限は0円以上である必要があります");
        }
        AiExternalSendCostBucket bucket = locked;
        if (bucket == null) {
            return ReserveResult.BUCKET_MISSING;
        }
        if (bucket.getClockHighWaterAt() != null
                && observedAt.plus(tolerance).isBefore(bucket.getClockHighWaterAt().toInstant(ZoneOffset.UTC))) {
            return ReserveResult.CLOCK_INVALID;
        }
        int updated = bucketMapper.reserve(bucket.getId(), yen, limitYen, approvalDigest,
                LocalDateTime.ofInstant(observedAt, ZoneOffset.UTC));
        if (updated == 1) {
            return ReserveResult.RESERVED;
        }
        if (updated == 0) {
            return ReserveResult.LIMIT_EXCEEDED;
        }
        throw new IllegalStateException("費用bucketの予約件数が不正です");
    }

    /** lock順の最後（entry）。claim IDを予約IDとしてRESERVEDのentryを作る。 */
    public void recordReservation(AiExternalSendCostEntry entry) {
        requireTransaction();
        if (entry == null || entry.getReservedYen() == null || entry.getReservedYen() < 1) {
            throw new IllegalArgumentException("費用予約entryが不正です");
        }
        if (entryMapper.insertReserved(entry) != 1) {
            throw new IllegalStateException("費用予約entryを作成できません");
        }
    }

    /** 予約entryの読取り（claim IDの36文字表記で照会）。 */
    public AiExternalSendCostEntry findReservation(String reservationId) {
        return entryMapper.selectByReservationId(reservationId);
    }

    private static long committed(AiExternalSendCostBucket bucket) {
        return bucket == null || bucket.getCommittedYen() == null ? 0L : bucket.getCommittedYen();
    }

    private static String requireScope(String costScope) {
        if (costScope == null || !costScope.matches("[a-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("費用上限の適用範囲が不正です");
        }
        return costScope;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("永続費用ledgerの更新は呼出側のtransaction内でだけ行います");
        }
    }

    /** 予約・高水位の判定結果。 */
    public enum ReserveResult { RESERVED, LIMIT_EXCEEDED, BUCKET_MISSING, CLOCK_INVALID }

    /** JST業務日と月のbucket key（アプリClockから一度だけ決める。DBのNOW()は使わない）。 */
    public record BucketKeys(LocalDate day, YearMonth month) {
        public BucketKeys {
            Objects.requireNonNull(day);
            Objects.requireNonNull(month);
            if (!YearMonth.from(day).equals(month)) {
                throw new IllegalArgumentException("日と月のbucketが一致しません");
            }
        }

        public static BucketKeys of(Instant instant) {
            LocalDate day = LocalDate.ofInstant(instant, AiExternalSendApproval.BUSINESS_ZONE);
            return new BucketKeys(day, YearMonth.from(day));
        }

        public String dayKey() {
            return DAY_KEY.format(day);
        }

        public String monthKey() {
            return MONTH_KEY.format(month);
        }
    }
}
