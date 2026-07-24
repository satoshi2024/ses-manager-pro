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
    private final com.ses.mapper.BpAvailabilityIngestionMapper bpAvailabilityIngestionMapper;

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
            log.info("レジュメPIIクリア対象なし");
        } else {
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
            log.info("レジュメPII extracted_text を{}件クリアしました。", targets.size());
        }

        // 外部要員空き状況のPIIクリア
        List<com.ses.entity.BpAvailabilityIngestion> bpTargets = bpAvailabilityIngestionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.BpAvailabilityIngestion>()
                        .in(com.ses.entity.BpAvailabilityIngestion::getStatus, List.of("確定済", "却下"))
                        .isNotNull(com.ses.entity.BpAvailabilityIngestion::getExtractedText)
                        .lt(com.ses.entity.BpAvailabilityIngestion::getUpdatedAt, threshold));
        
        if (bpTargets.isEmpty()) {
            log.info("外部要員空き状況PIIクリア対象なし");
        } else {
            for (com.ses.entity.BpAvailabilityIngestion job : bpTargets) {
                if ("却下".equals(job.getStatus())) {
                    bpAvailabilityIngestionMapper.deleteById(job.getId());
                    log.info("外部要員却下ジョブをパージしました: jobId={}", job.getId());
                } else {
                    bpAvailabilityIngestionMapper.update(null, new LambdaUpdateWrapper<com.ses.entity.BpAvailabilityIngestion>()
                            .eq(com.ses.entity.BpAvailabilityIngestion::getId, job.getId())
                            .set(com.ses.entity.BpAvailabilityIngestion::getExtractedText, null));
                }
            }
            log.info("外部要員PII extracted_text を{}件クリアしました。", bpTargets.size());
        }
    }
}
