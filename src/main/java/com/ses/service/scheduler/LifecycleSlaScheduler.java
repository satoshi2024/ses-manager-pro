package com.ses.service.scheduler;

import com.ses.service.lifecycle.LifecycleSlaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * ライフサイクル SLA 期日監視スケジューラー
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LifecycleSlaScheduler {

    private final LifecycleSlaService slaService;

    @Scheduled(cron = "0 0 8 * * *") // 毎朝8時実行
    @SchedulerLock(name = "lifecycleSlaCheck", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void runSlaCheck() {
        processSlaCheck(LocalDate.now());
    }

    /**
     * テストおよび手動実行用エントリーポイント
     */
    public int processSlaCheck(LocalDate asOf) {
        return slaService.processSlaCheck(asOf);
    }
}
