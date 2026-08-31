package com.ses.service.scheduler;

import com.ses.service.servicedesk.CustomerHealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;

/**
 * 顧客ヘルススコア・月次スナップショット日次バッチスケジューラ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerHealthScheduler {

    private final CustomerHealthService customerHealthService;
    private final Clock clock;

    @Scheduled(cron = "${customerhealth.snapshot.cron:0 0 2 * * *}")
    @SchedulerLock(name = "customerHealthSnapshotDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void runDailySnapshot() {
        processDailySnapshot(YearMonth.now(clock.getZone()).toString());
    }

    /**
     * テストおよび手動実行用エントリーポイント
     */
    public void processDailySnapshot(String targetMonth) {
        log.info("顧客ヘルス日次スナップショットバッチ実行開始: targetMonth={}", targetMonth);
        customerHealthService.generateMonthlySnapshot(targetMonth, "日次定期バッチによるスナップショット更新");
        log.info("顧客ヘルス日次スナップショットバッチ実行完了: targetMonth={}", targetMonth);
    }
}
