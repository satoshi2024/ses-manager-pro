package com.ses.service.impl;

import com.ses.common.util.LogRedaction;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.entity.ResumeIngestion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.privacy.production.provider.IngestionTextProvider;
import com.ses.privacy.production.retention.hold.HoldSubjectResolver;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldPath;
import com.ses.privacy.production.retention.hold.PrivacyHoldPathGuard;
import com.ses.service.FileStorageService;
import com.ses.service.document.ResumeIngestionLedgerRetentionService;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PII（個人情報）の保持期限管理サービス。
 * tenant inventoryごとに実行し、履歴取込ジョブの監査行を残したまま原本と解析結果を消去する。
 *
 * <p>原本の purge の後、文書台帳の副本（{@code RESUME_INGESTION}）の保存期限も当日に合わせ、既存の処分経路
 * （{@code DocumentRetentionJob} の廃棄候補 → 管理者の承認・執行）の対象にする（2026-11-16 依頼者決定。Privacy・文書 owner の
 * 署名待ち）。副本の処理は原本の purge とは別の短い transaction で行い、失敗しても purge を止めない。
 * 本 class は transaction を持たない（副本ごとの transaction を呼出し側の transaction に参加させないため）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeRetentionCleanupServiceImpl {

    private final ResumeIngestionMapper resumeIngestionMapper;
    private final BpAvailabilityIngestionMapper bpAvailabilityIngestionMapper;
    private final FileStorageService fileStorageService;
    private final TenantAwareBatchRunner tenantAwareBatchRunner;
    private final ObjectProvider<ResumeIngestionLedgerRetentionService> ledgerRetentionProvider;
    /** NF07 保全hold の gate（D4・D5。A1H-2）。 */
    private final PrivacyHoldPathGuard privacyHoldPathGuard;

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

    /**
     * 1 tenant 分の清理（test から tenant を限定して呼べるよう package-private）。
     *
     * <p>NF07 A1H-2（D4・D5）: 回の初めに保全 hold の gate を1回だけ照会し（D4 と D5 で共有する snapshot）、hold に当たる・
     * 分類が UNKNOWN・gate が判定できない行は消さずに件数を警告する。本人は F2 provider の宣言で導出する（履歴取込は
     * {@code converted_engineer_id} と {@code candidate_id} の和集合、BP 取込は {@code converted_availability_id}）。
     * どの本人にも帰属しない行は SUBJECT hold では止めず、DATA_ELEMENT hold のときだけ止める（§3.4.3 規則4）。
     * hold 機構を使っていない tenant では増える照会はこの1回だけで、清理の結果は変わらない。
     */
    void cleanupTenant(String tenantId, LocalDateTime threshold) {
        PrivacyHoldPathGuard.Batch resumeHold = privacyHoldPathGuard.openBatch(PrivacyHoldPath.D4, tenantId);
        PrivacyHoldPathGuard.Batch bpHold = resumeHold.forPath(PrivacyHoldPath.D5);
        try {
            cleanupTenant(tenantId, threshold, resumeHold, bpHold);
        } finally {
            resumeHold.close();
            bpHold.close();
        }
    }

    private void cleanupTenant(String tenantId, LocalDateTime threshold, PrivacyHoldPathGuard.Batch resumeHold,
                               PrivacyHoldPathGuard.Batch bpHold) {
        List<ResumeIngestion> resumeTargets = resumeIngestionMapper
                .selectExpiredOriginalsForTenant(tenantId, threshold);
        int purged = 0;
        for (ResumeIngestion job : resumeTargets) {
            String storedName = job.getStoredFileName();
            // 原本の実体と抽出本文を同じ UPDATE で消すため、行が持つ element のどちらかが止まれば行ごと残す
            List<String> elements = storedName != null && !storedName.isBlank()
                    ? List.of(IngestionTextProvider.RESUME_ORIGINAL, IngestionTextProvider.RESUME_TEXT)
                    : List.of(IngestionTextProvider.RESUME_TEXT);
            PrivacyHoldGate.Verdict verdict = resumeHold.rowVerdict(elements,
                    HoldSubjectResolver.RESUME_INGESTION_TABLE, job.getId());
            if (verdict != PrivacyHoldGate.Verdict.ALLOW) {
                resumeHold.skip(verdict);
                continue;
            }
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
        alignLedgerCopies(tenantId);

        // BP取込側も同じinventory境界でPIIを清理する。既存の監査行は論理削除しない。
        List<BpAvailabilityIngestion> bpTargets = bpAvailabilityIngestionMapper
                .selectExpiredWithTextForTenant(tenantId, threshold);
        int cleared = 0;
        for (BpAvailabilityIngestion job : bpTargets) {
            PrivacyHoldGate.Verdict verdict = bpHold.rowVerdict(List.of(IngestionTextProvider.BP_TEXT),
                    HoldSubjectResolver.BP_INGESTION_TABLE, job.getId());
            if (verdict != PrivacyHoldGate.Verdict.ALLOW) {
                bpHold.skip(verdict);
                continue;
            }
            bpAvailabilityIngestionMapper.clearExtractedTextForTenant(job.getId(), tenantId);
            cleared++;
        }
        if (purged > 0 || cleared > 0) {
            log.info("保持期限清理を実行しました: tenantId={}, resumeJobs={}, bpJobs={}",
                    tenantId, purged, cleared);
        }
    }

    /**
     * 原本を purge 済みのジョブに link した文書台帳の副本の保存期限を当日に合わせる。今回 purge したものに限らず、
     * 前回までに合わせられなかった副本（hold の解除後・一時的な失敗）も毎回拾い直す。失敗しても原本の purge は戻さない。
     */
    private void alignLedgerCopies(String tenantId) {
        ResumeIngestionLedgerRetentionService ledgerRetention = ledgerRetentionProvider.getIfAvailable();
        if (ledgerRetention == null) {
            return;
        }
        try {
            ResumeIngestionLedgerRetentionService.Result result =
                    ledgerRetention.alignPurgedCopies(tenantId, LocalDate.now());
            if (result.failed() > 0) {
                // 副本ごとのWARNだけではDB障害等の系統的な失敗が埋もれるため、DocumentRetentionJob と同じく件数をERRORで1件残す
                log.error("履歴取込原本の purge に合わせた文書台帳の副本の処理に失敗した副本があります。次の実行で再試行します: "
                        + "tenantId={}, failed={}, aligned={}, held={}", tenantId, result.failed(), result.aligned(),
                        result.held());
            } else if (result.aligned() > 0 || result.held() > 0) {
                log.info("履歴取込原本の purge に合わせて文書台帳の副本を処分対象にしました: tenantId={}, aligned={}, held={}",
                        tenantId, result.aligned(), result.held());
            }
        } catch (RuntimeException e) {
            log.error("文書台帳の副本の保存期限を合わせられませんでした。次の実行で再試行します: tenantId={}, exceptionType={}",
                    tenantId, LogRedaction.exceptionType(e));
        }
    }
}
