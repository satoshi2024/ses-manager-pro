package com.ses.service.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Document;
import com.ses.entity.DocumentDisposalRequest;
import com.ses.mapper.DocumentDisposalRequestMapper;
import com.ses.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 保存年数経過文書の自動廃棄候補抽出バッチジョブ（T026）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentRetentionJob {

    private final DocumentMapper documentMapper;
    private final DocumentDisposalRequestMapper documentDisposalRequestMapper;

    /**
     * 毎日深夜 02:00 に実行され、保存期限が切れた非ホールド文書を廃棄申請候補（PENDING）に追加する。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void processExpiredDocuments() {
        log.info("[保存期限ジョブ] 保存期限経過文書のチェックを開始します");

        LocalDate today = LocalDate.now();

        List<Document> expiredDocs = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .le(Document::getRetentionUntil, today)
                .eq(Document::getLegalHoldFlag, 0)
                .eq(Document::getStatus, "CONFIRMED"));

        int createdCount = 0;
        for (Document doc : expiredDocs) {
            Long existingCount = documentDisposalRequestMapper.selectCount(new LambdaQueryWrapper<DocumentDisposalRequest>()
                    .eq(DocumentDisposalRequest::getDocumentId, doc.getId())
                    .in(DocumentDisposalRequest::getStatus, "PENDING", "APPROVED"));

            if (existingCount == 0) {
                DocumentDisposalRequest req = new DocumentDisposalRequest();
                req.setDocumentId(doc.getId());
                req.setRequestedBy(-1L); // システム自動抽出
                req.setReason("法定保存年数経過による自動廃棄候補抽出");
                req.setStatus("PENDING");
                req.setCreatedAt(LocalDateTime.now());
                documentDisposalRequestMapper.insert(req);
                createdCount++;
            }
        }

        log.info("[保存期限ジョブ] 抽出完了: 対象文書数={} 新規廃棄候補作成数={}", expiredDocs.size(), createdCount);
    }
}
