package com.ses.service.scheduler;

import com.ses.service.servicedesk.ServiceRequestAttachmentCompensationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** サービスデスク添付業務リンク補償の再試行。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceRequestAttachmentCompensationScheduler {

    static final int BATCH_SIZE = 100;

    private final ServiceRequestAttachmentCompensationService compensationService;

    /**
     * 定期実行の入口。ShedLock の method proxy はプリミティブ戻り値のメソッドをロックできず
     * {@code LockingNotSupportedException} を投げるため、戻り値は必ず {@code void} にする。
     * 件数が必要な場合は {@link #retryDueBatch()} を使う。
     */
    @Scheduled(cron = "${servicedesk.attachment.compensation-cron:0 */5 * * * *}")
    @SchedulerLock(name = "serviceRequestAttachmentCompensation", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void retryDue() {
        retryDueBatch();
    }

    /** 再試行期限を迎えた補償を1バッチ分処理し、完了件数を返す（ロックなし）。 */
    public int retryDueBatch() {
        int completed = compensationService.retryDue(BATCH_SIZE);
        log.info("サービスデスク添付補償再試行完了: completed={}", completed);
        return completed;
    }
}
