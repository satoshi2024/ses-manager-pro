package com.ses.service.scheduler;

import com.ses.service.servicedesk.ServiceSlaMonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * サービスデスク SLA 違反監視・エスカレーション定期スケジューラ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceSlaScheduler {

    private final ServiceSlaMonitoringService slaMonitoringService;
    private final Clock clock;

    @Scheduled(cron = "${servicedesk.sla.cron:0 */5 * * * *}")
    @SchedulerLock(name = "serviceSlaMonitoring", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void runSlaMonitoring() {
        processSlaMonitoring(LocalDateTime.now(clock));
    }

    /**
     * テストおよび手動実行用エントリーポイント
     */
    public int processSlaMonitoring(LocalDateTime asOf) {
        log.info("SLA 違反監視スケジューラ実行開始: asOf={}", asOf);
        int breachedCount = slaMonitoringService.checkSlaBreaches(asOf);
        log.info("SLA 違反監視スケジューラ実行完了: 更新件数={}", breachedCount);
        return breachedCount;
    }
}
