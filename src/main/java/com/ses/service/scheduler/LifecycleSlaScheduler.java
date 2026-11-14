package com.ses.service.scheduler;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
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
    private final TenantAwareBatchRunner tenantAwareBatchRunner;
    /** NF-01機能スイッチ。Spring管理外で生成した単体テスト対象ではnull（従来どおり実行）。 */
    @org.springframework.beans.factory.annotation.Autowired
    private FeatureSwitches featureSwitches;

    @Scheduled(cron = "0 0 8 * * *") // 毎朝8時実行
    @SchedulerLock(name = "lifecycleSlaCheck", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void runSlaCheck() {
        if (!FeatureSwitches.allows(featureSwitches, Feature.NF01)) {
            log.debug("[機能スイッチ] NF01がOFFのためライフサイクルSLA監視をスキップしました");
            return;
        }
        processSlaCheck(LocalDate.now());
    }

    /**
     * テストおよび手動実行用エントリーポイント
     */
    public int processSlaCheck(LocalDate asOf) {
        return tenantAwareBatchRunner.runAndSum(tenant -> slaService.processSlaCheck(asOf));
    }
}
