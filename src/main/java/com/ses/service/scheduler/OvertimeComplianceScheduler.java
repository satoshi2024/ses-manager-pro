package com.ses.service.scheduler;

import com.ses.service.attendance.overtime.OvertimeComplianceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * 時間外コンプライアンスの日次判定（design §5.3 scheduler principal）。
 * test profileでは {@code app.scheduling.enabled=false} のためcronは起動しない。
 * 手動/Demoは {@link OvertimeComplianceService#evaluateApprovedOrClosedMonths} を呼ぶ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OvertimeComplianceScheduler {

    private final OvertimeComplianceService overtimeComplianceService;

    @Scheduled(cron = "0 30 7 * * *")
    @SchedulerLock(name = "overtimeComplianceDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void evaluateDaily() {
        YearMonth target = YearMonth.now();
        try {
            int count = overtimeComplianceService.evaluateApprovedOrClosedMonths(target);
            log.info("[時間外compliance] 日次判定完了: month={}, processed={}", target, count);
        } catch (RuntimeException e) {
            log.warn("[時間外compliance] 日次判定で例外: month={}, error={}", target, e.getMessage());
        }
    }
}
