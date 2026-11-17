package com.ses.service.scheduler;

import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * due snapshotに対するclaim CASを独立transaction（REQUIRES_NEW）で実行し、commit成功後にのみ内部tokenを返す。
 *
 * <p>transaction境界はSpring proxyでのみ成立する。schedulerは本beanをDI経由で受け取り、手動 {@code new} や
 * 同一class内の自己呼出しでproxyを迂回しない（unit testで実体を直接使う場合はtransaction境界を検証対象にしない）。
 *
 * <p>期限切れleaseの引き継ぎは、completionに至らなかった前の実行を連続失敗1回として数える（review P3-6）。
 * 引き継げば上限に達するsnapshotは {@link #claim} せず、{@link #stopAtRetryLimit} で停止する。
 */
@Service
@RequiredArgsConstructor
public class ReportScheduleClaimService {

    private final ReportScheduleMapper scheduleMapper;

    /** due snapshotが期限切れleaseの引き継ぎ（占有中の行）か。占有の有無は {@code processing_claimed_at} だけで判定する。 */
    public static boolean isStaleTakeover(ReportSchedule due) {
        return due != null && due.getProcessingClaimedAt() != null;
    }

    /** 引き継げば連続失敗（引き継ぎを含む）が上限に達する期限切れleaseか。 */
    public static boolean takeoverReachesRetryLimit(ReportSchedule due) {
        return isStaleTakeover(due)
                && failureCount(due) + 1 >= ReportScheduleMapper.MAX_CONSECUTIVE_FAILURES;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Claim claim(ReportSchedule due, LocalDateTime logicalRunAt,
                       LocalDateTime claimedAt, LocalDateTime staleBefore) {
        if (due == null || due.getId() == null || due.getTenantId() == null
                || logicalRunAt == null || claimedAt == null || staleBefore == null) {
            return null;
        }
        // MySQL DATETIMEは秒精度で、端数は丸められる。保存値とcompletion照合値がずれないよう秒へ切り捨てる。
        LocalDateTime normalizedClaimedAt = claimedAt.truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime normalizedStaleBefore = staleBefore.truncatedTo(ChronoUnit.SECONDS);
        String token = UUID.randomUUID().toString();
        int expectedFailureCount = failureCount(due);
        int changed = scheduleMapper.claimDue(due.getTenantId(), due.getId(), due.getNextRunAt(),
                due.getRetryScheduledAt(), due.getProcessingClaimedAt(), due.getProcessingLogicalRunAt(),
                due.getProcessingClaimToken(), due.getLastRunAt(),
                expectedFailureCount, logicalRunAt, token,
                normalizedClaimedAt, normalizedStaleBefore);
        if (changed != 1) {
            return null;
        }
        // 引き継ぎclaimは同じUPDATEで連続失敗回数を1加算している（claimDueのSQL）。claim後に回数を変えるのは
        // 所有者のcompletionだけなので、この値がcompletion時点のDB値になる。
        int failureCountAfterClaim = expectedFailureCount + (isStaleTakeover(due) ? 1 : 0);
        return new Claim(token, normalizedClaimedAt, logicalRunAt, failureCountAfterClaim);
    }

    /**
     * 引き継げば連続失敗が上限に達する期限切れleaseを、引き継がずに停止する（review P3-6）。停止が成立した場合だけtrue。
     * falseは入力不足・上限未到達・他workerが先に状態を変えた（CAS 0件）のいずれかで、どの場合も行を変更しない。
     *
     * @param retryAt 管理者が再開した時に同一論理runを再実行する時刻（failure completionの上限到達時と同じ扱い）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean stopAtRetryLimit(ReportSchedule due, LocalDateTime logicalRunAt, LocalDateTime staleBefore,
                                    LocalDateTime retryAt, String errorMessage) {
        if (due == null || due.getId() == null || due.getTenantId() == null || logicalRunAt == null
                || staleBefore == null || retryAt == null || !takeoverReachesRetryLimit(due)) {
            return false;
        }
        int changed = scheduleMapper.markTakeoverExhausted(due.getTenantId(), due.getId(), due.getNextRunAt(),
                due.getRetryScheduledAt(), due.getProcessingClaimedAt(), due.getProcessingLogicalRunAt(),
                due.getProcessingClaimToken(), due.getLastRunAt(), failureCount(due), logicalRunAt,
                retryAt.truncatedTo(ChronoUnit.SECONDS), staleBefore.truncatedTo(ChronoUnit.SECONDS), errorMessage);
        return changed == 1;
    }

    private static int failureCount(ReportSchedule due) {
        return due.getFailureCount() == null ? 0 : due.getFailureCount();
    }

    /** tokenは診断文字列やJSONへ出さない。 */
    public static final class Claim {
        private final String token;
        private final LocalDateTime claimedAt;
        private final LocalDateTime logicalRunAt;
        private final int failureCount;

        private Claim(String token, LocalDateTime claimedAt, LocalDateTime logicalRunAt, int failureCount) {
            this.token = token;
            this.claimedAt = claimedAt;
            this.logicalRunAt = logicalRunAt;
            this.failureCount = failureCount;
        }

        public String token() { return token; }
        public LocalDateTime claimedAt() { return claimedAt; }
        public LocalDateTime logicalRunAt() { return logicalRunAt; }
        /** claim成立後のDB上の連続失敗回数（引き継ぎで加算した1回を含む）。 */
        public int failureCount() { return failureCount; }

        @Override
        public String toString() {
            return "ReportScheduleClaim[owned=true]";
        }
    }
}
