package com.ses.service.lifecycle;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.service.ExternalAccountRevocationDispatcher;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 登録後・送信前の停止を回収するNF01専用入口。新しいthread/executorを作らない。 */
@Component
@RequiredArgsConstructor
public class ExternalAccountRevocationScheduler {
    private final ExternalAccountRevocationDispatcher dispatcher;
    private final TenantAwareBatchRunner tenantRunner;
    private final FeatureSwitches featureSwitches;

    @Scheduled(fixedDelay = 60000)
    @SchedulerLock(name = "externalAccountRevocationOutbox", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1S")
    public void dispatchRevocations() {
        if (!featureSwitches.isEnabled(Feature.NF01)) return;
        tenantRunner.run(tenant -> dispatcher.dispatchPending());
    }
}
