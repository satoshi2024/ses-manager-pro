package com.ses.privacy.production.dsar;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * DSAR の開示 package の削除（NF07-A2b、production-bridge-phased-plan.md §3.5.2「期限超過・交付記録で PURGED」）。毎時実行する。
 *
 * <p>{@code purge_after} を過ぎた package と、終了状態（CONFIRMED・BLOCKED・CANCELLED 等）の case に残った package を PURGED にし、
 * 実体を消す（{@link PrivacyExportPackageService#purgeDue}）。機能スイッチ {@code NF07_DSAR} では止めない（OFF に戻した後も期限を
 * 超えて package を残さない。I-9。{@code FeatureSwitchRouteCoverageTest} の UNGATED_SCHEDULERS）。package が1件も無ければ照会1回で
 * 終わる。test profile は {@code app.scheduling.enabled=false} で定期実行そのものが止まる。
 */
public class PrivacyExportPackagePurgeScheduler {

    private final PrivacyExportPackageService packageService;

    public PrivacyExportPackagePurgeScheduler(PrivacyExportPackageService packageService) {
        this.packageService = Objects.requireNonNull(packageService, "packageService");
    }

    /**
     * 定期実行の入口。ShedLock の PROXY_METHOD は primitive を返す method を lock できないため {@code void} にする
     * （件数は {@link PrivacyExportPackageService#purgeDue} が返す）。
     */
    @Scheduled(cron = "0 15 * * * ?", zone = "Asia/Tokyo")
    @SchedulerLock(name = "privacyDsarPackagePurgeHourly", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void purgeHourly() {
        packageService.purgeDue(null);
    }
}
