package com.ses.service.cloudsign;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 送信後状態のpolling scheduler（HFP-02-05）。
 * ShedLockで複数instanceでも一回だけ動かし、active行（SENT/結果不明）を古い順にbatch処理する。
 * 一行の失敗はbatch全体へ波及させない。poll停止はmonitorのalert判定で検出する。
 * {@code cloudsign.enabled=false} の間はkill switchとして何も処理しない。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CloudSignPollingScheduler {

    private final CloudSignSyncService syncService;
    private final com.ses.config.CloudSignProperties properties;
    private final CloudSignMonitor monitor;

    @Scheduled(cron = "${cloudsign.poll-cron:0 */2 * * * *}")
    @SchedulerLock(name = "cloudsignPoll", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void pollProviderStatus() {
        if (!properties.isEnabled()) {
            return;
        }
        monitor.recordPollStart();
        long start = System.currentTimeMillis();
        try {
            int processed = syncService.pollDue(properties.getPollBatchSize());
            monitor.recordPollSuccess(System.currentTimeMillis() - start);
            log.info("[契約書poll] status同期完了: {}件 {}ms", processed,
                    System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            monitor.recordPollFailure();
            log.warn("[契約書poll] scheduler run中に例外: error={}", e.getMessage());
        }
        if (monitor.isPollingStale(Duration.ofMinutes(10))) {
            monitor.recordPollingStop();
            log.error("[契約書poll] ALERT: pollingが長時間成功していません (last={}ms) {}",
                    monitor.snapshot().get("lastPollSuccessAt"), monitor.snapshot().get("lastPollDurationMs"));
        }
    }
}
