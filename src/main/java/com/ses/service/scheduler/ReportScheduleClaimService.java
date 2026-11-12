package com.ses.service.scheduler;

import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/** claim UPDATEのcommit確認後にのみschedulerへ内部tokenを返す。 */
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
        String token = UUID.randomUUID().toString();
        int changed = scheduleMapper.claimDue(due.getTenantId(), due.getId(), due.getNextRunAt(),
                due.getRetryScheduledAt(), due.getProcessingClaimedAt(), due.getProcessingLogicalRunAt(),
                due.getProcessingClaimToken(), due.getLastRunAt(),
                due.getFailureCount() == null ? 0 : due.getFailureCount(), logicalRunAt, token,
                claimedAt, staleBefore);
        return changed == 1 ? new Claim(token, claimedAt, logicalRunAt) : null;
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
