package com.ses.service.migration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.entity.ContractDocument;
import com.ses.entity.Proposal;
import com.ses.entity.ResumeIngestion;
import com.ses.mapper.ContractDocumentMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.service.DocumentService;
import com.ses.service.impl.DocumentServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

/**
 * 既存ファイル群の法定文書台帳への移行スクリプト（T027）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentMigrationScript implements CommandLineRunner {

    private final DocumentService documentService;
    private final ResumeIngestionMapper resumeIngestionMapper;
    private final ProposalMapper proposalMapper;
    private final ContractDocumentMapper contractDocumentMapper;

    @Value("${app.upload.base-path:./uploads}")
    private String uploadBase;

    @Override
    public void run(String... args) {
        // コマンドライン引数に "--migrate-documents" が指定された場合のみ実行する
        boolean shouldRun = false;
        if (args != null) {
            for (String arg : args) {
                if ("--migrate-documents".equals(arg)) {
                    shouldRun = true;
                    break;
                }
            }
        }

        if (!shouldRun) {
            return;
        }

        log.info("[文書台帳移行] 既存ファイルの台帳移行処理を開始します...");
        int migratedResumes = migrateResumes();
        int migratedSkillSheets = migrateSkillSheets();
        int migratedContractDocs = migrateContractDocuments();

        log.info("[文書台帳移行] 移行完了: 職務経歴書={}件, スキルシート={}件, 契約PDF={}件",
                migratedResumes, migratedSkillSheets, migratedContractDocs);
    }

    public int migrateResumes() {
        log.info("[文書台帳移行] 職務経歴書の移行はS04-NOTE-1の決定待ちのためスキップします");
        return 0;
    }

    public int migrateSkillSheets() {
        log.info("[文書台帳移行] スキルシートの移行はS04-NOTE-1の決定待ちのためスキップします");
        return 0;
    }

    public int migrateContractDocuments() {
        int count = 0;
        List<ContractDocument> list = contractDocumentMapper.selectList(
                new LambdaQueryWrapper<ContractDocument>().isNotNull(ContractDocument::getPdfPath));

        for (ContractDocument d : list) {
            try {
                Path path = Paths.get(d.getPdfPath());
                if (!Files.exists(path)) {
                    continue;
                }

                byte[] bytes = Files.readAllBytes(path);
                if (d.getPdfSha256() != null && !d.getPdfSha256().isBlank()) {
                    String actualSha256 = DocumentServiceImpl.computeSha256(bytes);
                    if (!d.getPdfSha256().equalsIgnoreCase(actualSha256)) {
                        log.warn("[文書台帳移行] ハッシュ不一致により契約PDF移行をスキップ: docId={} expected={} actual={}",
                                d.getId(), d.getPdfSha256(), actualSha256);
                        continue;
                    }
                }

                DocumentRegisterRequest req = DocumentRegisterRequest.builder()
                        .documentType("CONTRACT")
                        .title("契約書PDF: ドキュメントID=" + d.getId())
                        .counterpartyType("CUSTOMER")
                        .transactionDate(d.getCreatedAt() != null ? d.getCreatedAt().toLocalDate() : LocalDate.now())
                        .direction("OUTGOING")
                        .originalName(path.getFileName().toString())
                        .contentType("application/pdf")
                        .sourceType("GENERATED")
                        .businessKey("CONTRACT:" + d.getContractId())
                        .versionDiscriminator("v1")
                        .targetType("CONTRACT")
                        .targetId(d.getContractId())
                        .build();

                try (InputStream is = new ByteArrayInputStream(bytes)) {
                    documentService.registerGenerated(req, is);
                    count++;
                }
            } catch (Exception e) {
                log.warn("[文書台帳移行] 契約PDFスキップ: docId={} error={}", d.getId(), e.getMessage());
            }
        }
        return count;
    }
}
