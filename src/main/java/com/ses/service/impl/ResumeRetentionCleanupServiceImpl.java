package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.entity.ResumeIngestion;
import com.ses.mapper.ResumeIngestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PII (個人情報)の保持期限管理サービス。
 * 指定期間を超過した確定済・却下ジョブの extracted_text をクリアする。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeRetentionCleanupServiceImpl {

    private final ResumeIngestionMapper resumeIngestionMapper;

    /** 最小保持日数 (デフォルト: 30日) */
    @Value("${app.resume.retention-days:30}")
    private int retentionDays;

    /**
     * 毎日午前2時に実行。
     * 保持期限を超えた確定済・却下ジョブの extracted_text をNULLにする。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredExtractedText() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
        log.info("PIIクリアバッチ開始: retentionDays={}, threshold={}", retentionDays, threshold);

        // 期限切れの確定済/却下ジョブを取得
        List<ResumeIngestion> targets = resumeIngestionMapper.selectList(
                new LambdaQueryWrapper<ResumeIngestion>()
                        .in(ResumeIngestion::getStatus, List.of("確定済", "却下"))
                        .isNotNull(ResumeIngestion::getExtractedText)
                        .lt(ResumeIngestion::getUpdatedAt, threshold));

        if (targets.isEmpty()) {
            log.info("PIIクリア対象なし");
            return;
        }

        for (ResumeIngestion job : targets) {
            if ("却下".equals(job.getStatus())) {
                // 却下ジョブは論理削除（パージ）
                resumeIngestionMapper.deleteById(job.getId());
                log.info("却下ジョブをパージしました: jobId={}", job.getId());
            } else {
                // 確定済ジョブは抽出テキスト（PII）のみクリア
                resumeIngestionMapper.update(null, new LambdaUpdateWrapper<ResumeIngestion>()
                        .eq(ResumeIngestion::getId, job.getId())
                        .set(ResumeIngestion::getExtractedText, null));
            }
        }

        log.info("PII extracted_text を{}件クリアしました。", targets.size());
    }
}
