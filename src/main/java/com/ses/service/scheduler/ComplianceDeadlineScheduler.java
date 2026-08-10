package com.ses.service.scheduler;

import com.ses.service.ComplianceDeadlineService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * T065 B2: compliance期限通知の日次実行（抵触日/文書期限の90/60/30日前、例外承認の失効）。
 * 通知はdedupeKeyで冪等（同一期限・同一段階で1回）。
 */
@Component
@RequiredArgsConstructor
public class ComplianceDeadlineScheduler {

    private final ComplianceDeadlineService complianceDeadlineService;
    private final Clock clock;

    @Scheduled(cron = "0 30 6 * * *")
    @SchedulerLock(name = "complianceDeadlineNotification", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void runDeadlineCheck() {
        complianceDeadlineService.process(LocalDateTime.now(clock));
    }
}
