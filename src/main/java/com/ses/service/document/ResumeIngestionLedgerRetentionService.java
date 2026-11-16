package com.ses.service.document;

import com.ses.common.util.LogRedaction;
import com.ses.entity.Document;
import com.ses.mapper.DocumentMapper;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 履歴・スキルシート取込の原本を purge した後、文書台帳の副本（{@code RESUME_INGESTION}）の保存期限を取込原本に合わせる
 * （2026-11-16 依頼者決定。Privacy・文書 owner の署名待ち。plan §3.4.1 D4）。
 *
 * <p>取込原本は {@code app.resume.retention-days} で purge されるが、upload 時に文書台帳へ登録した副本（別の実体 file）は
 * 取引日を持たないため保存期限が NULL のままで、処分候補にならず無期限に残っていた。本 service は、原本を purge 済みの
 * ジョブに link した副本について次だけを行い、処分そのものは既存の経路に任せる（方式 (a)）。
 * <ul>
 *   <li>DRAFT の副本は既存の {@link DocumentEligibilityService#confirm} で CONFIRMED にし、保存期限を当日にする</li>
 *   <li>CONFIRMED・AMENDED・CANCELLED で保存期限が未設定または当日より後の副本は、状態を変えず保存期限を当日に早める
 *       （版 CAS。CONFIRMED は D3 の自動抽出、AMENDED・CANCELLED は手動の廃棄申請の対象になる）。当日以前なら変えない</li>
 *   <li>文書の legal hold 中の副本は何も変えず WARN を出す（解除後の実行で拾い直す）</li>
 * </ul>
 * 判定は文書行を lock して読み直した値で行う（lock 前に読んだ hold・状態を使わない）。
 * 処分候補の作成は {@code DocumentRetentionJob}（02:30。履歴の清理 02:00 の後）の
 * {@link DocumentEligibilityService#registerRetentionDisposalCandidate}、物理削除は管理者の承認と
 * {@code DocumentServiceImpl.executeDisposal} で行う。どちらも lock 後に legal hold を再確認し、NF07 hold の gate（A1H）も
 * 同じ D3・D1 に入るため、本 service は hold の判定を迂回しない。副本ごとに短い transaction で処理し、1件の失敗で
 * 残りや取込原本の purge を止めない。照会は ID の keyset で全件を進めるため、hold 中・失敗し続ける副本が先頭に溜まっても
 * 後ろの副本は処理される。処理済みの副本は次の照会に出ないため、再実行しても重ねて変更しない。
 *
 * <p>失敗は metric {@value #FAILURE_METRIC}（tag {@code stage}: 副本ごとの失敗 {@code document}、照会自体の失敗
 * {@code batch}）に数える。1件でも増えたら調べる（deploy-runbook の V197 行）。
 */
@Slf4j
@Service
public class ResumeIngestionLedgerRetentionService {

    /** 1回の照会で扱う副本の件数（keyset で次の page へ進む）。 */
    static final int PAGE_SIZE = 500;

    /** 失敗件数の metric 名。 */
    public static final String FAILURE_METRIC = "ses.resume.ledger.retention.failures";

    /** 副本ごとの失敗（lock 待ちの timeout・版の不一致等）。 */
    public static final String STAGE_DOCUMENT = "document";

    /** 副本の照会自体の失敗。 */
    public static final String STAGE_BATCH = "batch";

    /** 保存期限を合わせる副本の状態（DISPOSED は処分済みのため対象外）。 */
    private static final Set<String> ALIGNABLE_STATUSES = Set.of("DRAFT", "CONFIRMED", "AMENDED", "CANCELLED");

    private final DocumentMapper documentMapper;
    private final DocumentEligibilityService documentEligibilityService;
    private final AuthorizationEpochFence fence;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    /** 1 page の件数（試験で小さくできるよう final にしない）。 */
    private int pageSize = PAGE_SIZE;

    public ResumeIngestionLedgerRetentionService(DocumentMapper documentMapper,
                                                 DocumentEligibilityService documentEligibilityService,
                                                 AuthorizationEpochFence fence,
                                                 PlatformTransactionManager transactionManager,
                                                 ObjectProvider<MeterRegistry> meterRegistry) {
        this.documentMapper = documentMapper;
        this.documentEligibilityService = documentEligibilityService;
        this.fence = fence;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /**
     * tenant 内の、原本を purge 済みのジョブに link した副本の保存期限を {@code retentionUntil} に合わせる。
     *
     * @return 件数（保存期限を合わせた件数・hold で見送った件数・失敗した件数）
     * @throws RuntimeException 副本の照会自体に失敗した（metric の {@code batch} に数えてから投げる）
     */
    public Result alignPurgedCopies(String tenantId, LocalDate retentionUntil) {
        Objects.requireNonNull(retentionUntil, "retentionUntil");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant の指定が必要です");
        }
        int limit = Math.max(1, pageSize);
        int aligned = 0;
        int held = 0;
        int failed = 0;
        long afterId = 0L;
        while (true) {
            List<Long> page;
            try {
                page = documentMapper.selectResumeCopiesAwaitingRetention(tenantId, retentionUntil, afterId, limit);
            } catch (RuntimeException e) {
                recordFailure(STAGE_BATCH);
                throw e;
            }
            for (Long documentId : page) {
                try {
                    Outcome outcome = transactionTemplate.execute(status -> alignOne(tenantId, documentId, retentionUntil));
                    if (outcome == Outcome.ALIGNED) {
                        aligned++;
                    } else if (outcome == Outcome.HELD) {
                        held++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    recordFailure(STAGE_DOCUMENT);
                    log.warn("[履歴取込] 文書台帳の副本の保存期限を合わせられませんでした。次の実行で再試行します: tenantId={} "
                            + "documentId={} exceptionType={}", tenantId, documentId, LogRedaction.exceptionType(e));
                }
            }
            if (page.size() < limit) {
                break;
            }
            afterId = page.get(page.size() - 1);
        }
        return new Result(aligned, held, failed);
    }

    private Outcome alignOne(String tenantId, long documentId, LocalDate retentionUntil) {
        // 文書行を先に lock してから読み直し、legal hold・状態・版は lock 後の値で判定する
        AuthorizationEpochAnchor.Document anchor = AuthorizationEpochAnchor.document(tenantId, documentId);
        fence.lockForUpdate(anchor);
        Document current = documentMapper.selectResumeCopyForRetention(documentId, tenantId);
        if (current == null || !"RESUME_INGESTION".equals(current.getDocumentType())
                || (current.getDeletedFlag() != null && current.getDeletedFlag() != 0)
                || !ALIGNABLE_STATUSES.contains(current.getStatus())) {
            return Outcome.SKIPPED;
        }
        if (current.getLegalHoldFlag() != null && current.getLegalHoldFlag() == 1) {
            log.warn("[履歴取込] 文書台帳の副本は legal hold 中のため保存期限を合わせず処分対象にしません: tenantId={} documentId={}",
                    tenantId, documentId);
            return Outcome.HELD;
        }
        if ("DRAFT".equals(current.getStatus())) {
            // 既存の確定（DRAFT→CONFIRMED と保存期限の設定。版 CAS・文書行の lock）を使う
            documentEligibilityService.confirm(current, retentionUntil);
            return Outcome.ALIGNED;
        }
        if (current.getRetentionUntil() != null && !current.getRetentionUntil().isAfter(retentionUntil)) {
            return Outcome.SKIPPED;
        }
        // 状態は変えず保存期限だけを早める（CONFIRMED は D3 の自動抽出、AMENDED・CANCELLED は手動の廃棄申請の対象になる）
        return documentEligibilityService.alignRetention(current, retentionUntil) ? Outcome.ALIGNED : Outcome.SKIPPED;
    }

    private void recordFailure(String stage) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Counter.builder(FAILURE_METRIC)
                    .description("履歴取込原本の purge に合わせた文書台帳の副本の保存期限の失敗件数")
                    .tag("stage", stage)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException metricFailure) {
            log.warn("[履歴取込] 副本の保存期限の失敗 metric を記録できませんでした: exceptionType={}",
                    LogRedaction.exceptionType(metricFailure));
        }
    }

    private enum Outcome {
        ALIGNED,
        HELD,
        SKIPPED
    }

    /** 実行結果の件数。 */
    public record Result(int aligned, int held, int failed) {
    }
}
