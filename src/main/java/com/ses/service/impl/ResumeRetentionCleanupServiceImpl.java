package com.ses.service.impl;

import com.ses.entity.BpAvailabilityIngestion;
import com.ses.entity.ResumeIngestion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.service.FileStorageService;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PII（個人情報）の保持期限管理サービス。
 * tenant inventoryごとに実行し、履歴取込ジョブの監査行を残したまま原本と解析結果を消去する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeRetentionCleanupServiceImpl {

    private final ResumeIngestionMapper resumeIngestionMapper;
    private final BpAvailabilityIngestionMapper bpAvailabilityIngestionMapper;
    private final FileStorageService fileStorageService;
    private final TenantAwareBatchRunner tenantAwareBatchRunner;

    /** 最小保持日数。 */
    @Value("${app.resume.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "0 0 2 * * ?")
    @SchedulerLock(name = "resumeRetentionCleanupDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void cleanupExpiredExtractedText() {
        if (retentionDays < 1) {
            throw new IllegalStateException("app.resume.retention-daysは1以上で設定してください");
        }
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
        tenantAwareBatchRunner.run(tenantId -> cleanupTenant(tenantId, threshold));
    }

    private void cleanupTenant(String tenantId, LocalDateTime threshold) {
        List<ResumeIngestion> resumeTargets = resumeIngestionMapper
                .selectExpiredOriginalsForTenant(tenantId, threshold);
        int purged = 0;
        for (ResumeIngestion job : resumeTargets) {
            String storedName = job.getStoredFileName();
            int updated = resumeIngestionMapper.purgeOriginalForTenant(
                    job.getId(), tenantId, job.getStatus(), job.getVersion());
            if (updated != 1) {
                // 同時更新された行は再実行で再評価する。別tenantの行を触るfallbackは持たない。
                log.info("履歴取込原本の保持期限更新をスキップしました: tenantId={}, jobId={}", tenantId, job.getId());
                continue;
            }
            purged++;
            if (storedName != null && !storedName.isBlank()) {
                try {
                    // DBの参照を先に消し、実体の削除に失敗しても孤児清掃で再試行可能にする。
                    fileStorageService.delete(storedName);
                } catch (RuntimeException e) {
                    log.warn("履歴取込原本の実体削除を後続の孤児清掃へ委ねます: tenantId={}, jobId={}",
                            tenantId, job.getId());
                }
            }
        }

        // BP取込側も同じinventory境界でPIIを清理する。既存の監査行は論理削除しない。
        List<BpAvailabilityIngestion> bpTargets = bpAvailabilityIngestionMapper
                .selectExpiredWithTextForTenant(tenantId, threshold);
        for (BpAvailabilityIngestion job : bpTargets) {
            bpAvailabilityIngestionMapper.clearExtractedTextForTenant(job.getId(), tenantId);
        }
        if (purged > 0 || !bpTargets.isEmpty()) {
            log.info("保持期限清理を実行しました: tenantId={}, resumeJobs={}, bpJobs={}",
                    tenantId, purged, bpTargets.size());
        }
    }
}
