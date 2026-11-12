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
 */
@Service
@RequiredArgsConstructor
public class ReportScheduleClaimService {

    private final ReportScheduleMapper scheduleMapper;

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
        int changed = scheduleMapper.claimDue(due.getTenantId(), due.getId(), due.getNextRunAt(),
                due.getRetryScheduledAt(), due.getProcessingClaimedAt(), due.getProcessingLogicalRunAt(),
                due.getProcessingClaimToken(), due.getLastRunAt(),
                due.getFailureCount() == null ? 0 : due.getFailureCount(), logicalRunAt, token,
                normalizedClaimedAt, normalizedStaleBefore);
        return changed == 1 ? new Claim(token, normalizedClaimedAt, logicalRunAt) : null;
    }

    /** tokenは診断文字列やJSONへ出さない。 */
    public static final class Claim {
        private final String token;
        private final LocalDateTime claimedAt;
        private final LocalDateTime logicalRunAt;

        private Claim(String token, LocalDateTime claimedAt, LocalDateTime logicalRunAt) {
            this.token = token;
            this.claimedAt = claimedAt;
            this.logicalRunAt = logicalRunAt;
        }

        public String token() { return token; }
        public LocalDateTime claimedAt() { return claimedAt; }
        public LocalDateTime logicalRunAt() { return logicalRunAt; }

        @Override
        public String toString() {
            return "ReportScheduleClaim[owned=true]";
        }
    }
}
