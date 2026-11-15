package com.ses.service.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.util.LogRedaction;
import com.ses.entity.Document;
import com.ses.mapper.DocumentMapper;
import com.ses.service.document.DocumentEligibilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 保存年数経過文書の自動廃棄候補抽出バッチジョブ（T026）。
 *
 * <p>NF01 F3: 廃棄申請の登録は文書の証跡利用可否を変えるため、{@link DocumentEligibilityService} を通して
 * 文書ごとの短いtransactionで行う（文書行のX lock → 条件の再確認 → 申請insert → epochの増分）。本jobは
 * tenant contextを持たずに全tenantの文書を扱うため、各文書の増分と照合は文書行自身のtenantで行う。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentRetentionJob {

    private final DocumentMapper documentMapper;
    private final DocumentEligibilityService documentEligibilityService;

    /**
     * 毎日深夜 02:30 に実行され、保存期限が切れた非ホールド文書を廃棄申請候補（PENDING）に追加する。
     */
    @Scheduled(cron = "0 30 2 * * ?")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "DocumentRetentionJob_processExpiredDocuments", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void processExpiredDocuments() {
        log.info("[保存期限ジョブ] 保存期限経過文書のチェックを開始します");

        LocalDate today = LocalDate.now();

        List<Document> expiredDocs = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .le(Document::getRetentionUntil, today)
                .eq(Document::getLegalHoldFlag, 0)
                .eq(Document::getStatus, "CONFIRMED"));

        int createdCount = 0;
        int failedCount = 0;
        for (Document doc : expiredDocs) {
            try {
                // 抽出時の行は古い可能性があるため、条件はserviceがlock取得後に読み直して再確認する。
                if (documentEligibilityService.registerRetentionDisposalCandidate(doc.getTenantId(), doc.getId(), today)) {
                    createdCount++;
                }
            } catch (RuntimeException failure) {
                // 1文書の失敗（epoch上限・行の消失等）で残りの文書の抽出を止めない。
                failedCount++;
                log.warn("[保存期限ジョブ] 廃棄候補の登録に失敗したため次の文書へ進みます: documentId={} exceptionType={}",
                        doc.getId(), LogRedaction.exceptionType(failure));
            }
        }

        log.info("[保存期限ジョブ] 抽出完了: 対象文書数={} 新規廃棄候補作成数={} 失敗数={}",
                expiredDocs.size(), createdCount, failedCount);
    }
}
