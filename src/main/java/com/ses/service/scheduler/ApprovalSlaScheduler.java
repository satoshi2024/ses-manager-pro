package com.ses.service.scheduler;

import com.ses.service.approval.ApprovalSlaService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** 承認step SLA超過を定期検出するscheduler。 */
@Component
@RequiredArgsConstructor
public class ApprovalSlaScheduler {

    private final ApprovalSlaService approvalSlaService;
    private final Clock clock;

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "approvalSlaEscalation", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void runOverdueCheck() {
        processOverdue(LocalDateTime.now(clock));
    }

    /** schedulerと同じtransaction経路をテスト/Demoから明示時刻で起動する。 */
    public int processOverdue(LocalDateTime asOf) {
        return approvalSlaService.escalateOverdue(asOf);
    }
}
