package com.ses.config;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.enums.DispatchState;
import com.ses.entity.ContractDocument;
import com.ses.mapper.ContractDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

/**
 * HFP-02: 既存 t_contract_document 行の配送工程 backfill（分類のみ）。
 *
 * <p>外部APIを一切呼ばず、曖昧な行を自動再送しない。既存形状はdesign §5.1の表どおり分類する:
 * <ul>
 *   <li>外部ID無し・下書き → {@code NONE}（変更不要）</li>
 *   <li>外部ID有り・送信中/確認中 → {@code RECONCILIATION_REQUIRED}（provider GET待ち）</li>
 *   <li>締結済/完了・artifact path有り → hash再計算でsigned/certificate hashを記録し、
 *       文書台帳への移行候補（archive document id NULL）として {@code COMPLETED}</li>
 *   <li>hash/path/外部IDの矛盾 → {@code RECONCILIATION_REQUIRED}＋finding code記録で停止</li>
 * </ul>
 * 一度分類した行（dispatch_state != NONE）には触れない（冪等）。
 */
@Component
@ConditionalOnProperty(name = "app.contract-document.backfill-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ContractDocumentDispatchBackfill implements ApplicationRunner {

    private static final Set<String> CONFIRMING = Set.of("送信中", "確認中", "先方確認中");
    private static final Set<String> COMPLETED = Set.of("締結済", "完了");

    private final ContractDocumentMapper mapper;

    @Override
    public void run(ApplicationArguments args) {
        List<ContractDocument> legacy = mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContractDocument>()
                        .eq(ContractDocument::getDispatchState, DispatchState.NONE.name())
                        .and(w -> w.isNotNull(ContractDocument::getCloudsignDocumentId)
                                .or()
                                .in(ContractDocument::getStatus, union(CONFIRMING, COMPLETED))));
        int classified = 0;
        for (ContractDocument doc : legacy) {
            try {
                if (classify(doc)) {
                    classified++;
                }
            } catch (Exception e) {
                log.warn("[契約書backfill] 配送工程の分類に失敗: docId={} error={}", doc.getId(), e.getMessage());
                markFinding(doc, "BACKFILL_ERROR");
            }
        }
        if (classified > 0) {
            log.info("[契約書backfill] 配送工程を分類: {}件", classified);
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.HashSet<String> u = new java.util.HashSet<>(a);
        u.addAll(b);
        return u;
    }

    private boolean classify(ContractDocument doc) {
        String externalId = doc.getCloudsignDocumentId();
        String status = doc.getStatus();
        boolean hasExternal = externalId != null && !externalId.isBlank();

        if ("下書き".equals(status)) {
            // 外部ID無し・下書き = NONEのまま。外部ID有り・下書き = 矛盾
            return hasExternal && markFinding(doc, "BACKFILL_CONTRADICTION");
        }
        // 外部ID無しで確認中/締結済: provider連携の痕跡が無いため触らない（安全側でNONEのまま）
        if (!hasExternal) {
            return false;
        }
        if (CONFIRMING.contains(status)) {
            return markFinding(doc, "BACKFILL_LEGACY_CONFIRMING");
        }
        if (COMPLETED.contains(status)) {
            return classifyCompleted(doc);
        }
        return markFinding(doc, "BACKFILL_CONTRADICTION");
    }

    private boolean classifyCompleted(ContractDocument doc) {
        String signedPath = doc.getSignedPdfPath();
        String certPath = doc.getCertificatePath();
        if (signedPath == null || signedPath.isBlank()) {
            return markFinding(doc, "BACKFILL_CONTRADICTION");
        }
        String signedHash = hashOf(signedPath);
        if (signedHash == null) {
            return markFinding(doc, "BACKFILL_FILE_UNREADABLE");
        }
        String certHash = null;
        if (certPath != null && !certPath.isBlank()) {
            certHash = hashOf(certPath);
        }
        // 移行候補化: archive document idは未設定のまま、hashを記録してCOMPLETEDへ
        mapper.update(null, new LambdaUpdateWrapper<ContractDocument>()
                .eq(ContractDocument::getId, doc.getId())
                .eq(ContractDocument::getDispatchState, DispatchState.NONE.name())
                .eq(ContractDocument::getVersion, safeVersion(doc))
                .set(ContractDocument::getDispatchState, DispatchState.COMPLETED.name())
                .set(ContractDocument::getSignedPdfSha256, signedHash)
                .set(ContractDocument::getCertificateSha256, certHash)
                .set(ContractDocument::getLastProviderErrorCode, null)
                .set(ContractDocument::getVersion, safeVersion(doc) + 1));
        return true;
    }

    private boolean markFinding(ContractDocument doc, String code) {
        mapper.update(null, new LambdaUpdateWrapper<ContractDocument>()
                .eq(ContractDocument::getId, doc.getId())
                .eq(ContractDocument::getDispatchState, DispatchState.NONE.name())
                .eq(ContractDocument::getVersion, safeVersion(doc))
                .set(ContractDocument::getDispatchState, DispatchState.RECONCILIATION_REQUIRED.name())
                .set(ContractDocument::getLastProviderErrorCode, code)
                .set(ContractDocument::getVersion, safeVersion(doc) + 1));
        log.warn("[契約書backfill] 曖昧な既存行を要確認に分類: docId={} externalId={} status={} code={}",
                doc.getId(), externalIdMasked(doc.getCloudsignDocumentId()), doc.getStatus(), code);
        return true;
    }

    private static int safeVersion(ContractDocument doc) {
        return doc.getVersion() == null ? 0 : doc.getVersion();
    }

    private static String externalIdMasked(String id) {
        if (id == null) {
            return null;
        }
        return id.length() > 6 ? id.substring(0, 3) + "..." : "***";
    }

    private static String hashOf(String path) {
        try {
            Path p = Paths.get(path);
            if (!Files.isRegularFile(p)) {
                return null;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder();
            for (byte v : digest) {
                sb.append(String.format("%02x", v));
            }
            return sb.toString();
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }
}
