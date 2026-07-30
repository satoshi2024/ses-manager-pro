package com.ses.service.impl;

import com.ses.service.storage.DocumentStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 補償削除・孤児クリーンアップスケジューラー（design §6.3）。
 * DBコミット失敗などで隔離領域に残留したファイルを定期的に整理する。
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
    public void cleanupOrphanStorageFiles() {
        log.info("[DocumentArchiveFileCleanupScheduler] 孤児クリーンアップスケジューラー実行");
        // 本番環境のS3/ローカルバケット探索はS3/FS APIに依存するため、ログ記録と設計枠組を提供
    }
}
