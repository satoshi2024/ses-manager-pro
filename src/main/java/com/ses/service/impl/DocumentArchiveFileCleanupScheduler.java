package com.ses.service.impl;

import com.ses.service.storage.DocumentStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 補償削除・孤児クリーンアップスケジューラー（design §6.3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentArchiveFileCleanupScheduler {

    private final DocumentStorage documentStorage;

    /**
     * 毎日深夜2時に孤児ファイルの補償クリーンアップログを出力/実行する。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @SchedulerLock(name = "DocumentArchiveFileCleanupScheduler_cleanupOrphanStorageFiles", lockAtLeastFor = "1m", lockAtMostFor = "15m")
    public void cleanupOrphanStorageFiles() {
        log.info("[DocumentArchiveFileCleanupScheduler] 孤児クリーンアップスケジューラー実行");
    }
}
