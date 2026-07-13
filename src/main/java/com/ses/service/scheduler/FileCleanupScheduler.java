package com.ses.service.scheduler;

import com.ses.service.FileCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 毎週日曜3:00に、DBから参照されなくなったアップロード済みファイル（孤児ファイル）を清掃する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileCleanupScheduler {

    private final FileCleanupService fileCleanupService;

    @Scheduled(cron = "0 0 3 * * SUN")
    public void cleanupWeekly() {
        int deleted = fileCleanupService.cleanupOrphanFiles();
        if (deleted > 0) {
            log.info("孤児ファイル清掃バッチ完了: {}件削除", deleted);
        }
    }
}
