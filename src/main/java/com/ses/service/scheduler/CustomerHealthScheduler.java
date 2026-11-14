package com.ses.service.scheduler;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.service.servicedesk.CustomerHealthService;
import com.ses.service.servicedesk.SnapshotExecutionContext;
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
    private final TenantAwareBatchRunner tenantAwareBatchRunner;
    /** NF-02機能スイッチ。Spring管理外で生成した単体テスト対象ではnull（従来どおり実行）。 */
    @org.springframework.beans.factory.annotation.Autowired
    private FeatureSwitches featureSwitches;

    @Scheduled(cron = "${customerhealth.snapshot.cron:0 0 2 * * *}")
    @SchedulerLock(name = "customerHealthSnapshotDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void runDailySnapshot() {
        if (!FeatureSwitches.allows(featureSwitches, Feature.NF02)) {
            log.debug("[機能スイッチ] NF02がOFFのため顧客ヘルス日次スナップショットをスキップしました");
            return;
        }
        processDailySnapshot(YearMonth.now(clock.getZone()).toString());
    }

    /**
     * テストおよび手動実行用エントリーポイント
     */
    public void processDailySnapshot(String targetMonth) {
        log.info("顧客ヘルス日次スナップショットバッチ実行開始: targetMonth={}", targetMonth);
        tenantAwareBatchRunner.run(tenantId -> {
            log.debug("顧客ヘルス日次スナップショットtenant処理開始: tenantId={}, targetMonth={}",
                    tenantId, targetMonth);
            customerHealthService.generateMonthlySnapshot(targetMonth, "日次定期バッチによるスナップショット更新",
                    SnapshotExecutionContext.systemScheduler());
        });
        log.info("顧客ヘルス日次スナップショットバッチ実行完了: targetMonth={}", targetMonth);
    }
}
