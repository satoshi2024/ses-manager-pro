package com.ses.service.scheduler;

import com.ses.service.AssetAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 資産ライフサイクル・期限監視スケジューラ
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AssetLifecycleScheduler {

    private final AssetAlertService assetAlertService;

    /**
     * 毎日午前9時に返却期限超過およびリース満了接近を監視
     */
    @Scheduled(cron = "0 0 9 * * ?")
    public void runDailyAssetChecks() {
        log.info("Running daily asset lifecycle check job...");
        try {
            int overdueCount = assetAlertService.checkOverdueAssignments();
            int leaseCount = assetAlertService.checkExpiringLeases();
            log.info("Daily asset check finished: overdueAlerts={}, leaseAlerts={}", overdueCount, leaseCount);
        } catch (Exception e) {
            log.error("Failed to execute daily asset lifecycle check job", e);
        }
    }
}
