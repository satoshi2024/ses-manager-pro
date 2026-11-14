package com.ses.service.scheduler;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.service.servicedesk.ServiceRequestAttachmentCompensationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * サービスデスク添付業務リンク補償の再試行。
 * NF-02がOFFの間は再試行しない（補償台帳はRETRYのまま残り、ON復帰後の次回実行で再開する）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceRequestAttachmentCompensationScheduler {

    private final ServiceRequestAttachmentCompensationService compensationService;
    /** NF-02機能スイッチ。Spring管理外で生成した単体テスト対象ではnull（従来どおり実行）。 */
    @org.springframework.beans.factory.annotation.Autowired
    private FeatureSwitches featureSwitches;

    @Scheduled(cron = "${servicedesk.attachment.compensation-cron:0 */5 * * * *}")
    @SchedulerLock(name = "serviceRequestAttachmentCompensation", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public int retryDue() {
        if (!FeatureSwitches.allows(featureSwitches, Feature.NF02)) {
            log.debug("[機能スイッチ] NF02がOFFのためサービスデスク添付補償の再試行をスキップしました");
            return 0;
        }
        int completed = compensationService.retryDue(100);
        log.info("サービスデスク添付補償再試行完了: completed={}", completed);
        return completed;
    }
}
