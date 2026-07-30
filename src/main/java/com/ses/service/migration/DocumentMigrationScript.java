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
        int count = 0;
        List<ResumeIngestion> list = resumeIngestionMapper.selectList(
                new LambdaQueryWrapper<ResumeIngestion>()
                        .isNotNull(ResumeIngestion::getStoredFileName)
                        .ne(ResumeIngestion::getStatus, "廃棄"));

        for (ResumeIngestion r : list) {
            try {
                Path path = Paths.get(uploadBase, "resumes", r.getStoredFileName());
                if (!Files.exists(path)) {
                    continue;
                }
                DocumentRegisterRequest req = DocumentRegisterRequest.builder()
                        .documentType("RESUME")
                        .title("職務経歴書: " + (r.getOriginalFileName() != null ? r.getOriginalFileName() : r.getStoredFileName()))
                        .counterpartyType("ENGINEER")
                        .transactionDate(r.getCreatedAt() != null ? r.getCreatedAt().toLocalDate() : LocalDate.now())
                        .direction("INCOMING")
                        .originalName(r.getOriginalFileName() != null ? r.getOriginalFileName() : r.getStoredFileName())
                        .contentType("application/octet-stream")
                        .sourceType("RECEIVED")
                        .businessKey("RESUME_INGESTION:" + r.getId())
                        .versionDiscriminator("v1")
                        .targetType("ENGINEER")
                        .targetId(r.getId())
                        .build();

                try (InputStream is = Files.newInputStream(path)) {
                    documentService.registerReceived(req, is);
                    count++;
                }
            } catch (Exception e) {
                log.warn("[文書台帳移行] 職務経歴書スキップ: id={} error={}", r.getId(), e.getMessage());
            }
        }
        return count;
    }

    public int migrateSkillSheets() {
        int count = 0;
        List<Proposal> list = proposalMapper.selectList(
                new LambdaQueryWrapper<Proposal>().isNotNull(Proposal::getSkillSheetPath));

        for (Proposal p : list) {
            try {
                Path path = Paths.get(uploadBase, "skillsheets", p.getSkillSheetPath());
                if (!Files.exists(path)) {
                    continue;
                }
                DocumentRegisterRequest req = DocumentRegisterRequest.builder()
                        .documentType("SKILL_SHEET")
                        .title("スキルシート: 提案ID=" + p.getId())
                        .counterpartyType("CUSTOMER")
                        .transactionDate(p.getCreatedAt() != null ? p.getCreatedAt().toLocalDate() : LocalDate.now())
                        .direction("OUTGOING")
                        .originalName(path.getFileName().toString())
                        .contentType("application/octet-stream")
                        .sourceType("GENERATED")
                        .businessKey("PROPOSAL_SKILL_SHEET:" + p.getId())
                        .versionDiscriminator("v1")
                        .targetType("PROPOSAL")
                        .targetId(p.getId())
                        .build();

                try (InputStream is = Files.newInputStream(path)) {
                    documentService.registerGenerated(req, is);
                    count++;
                }
            } catch (Exception e) {
                log.warn("[文書台帳移行] スキルシートスキップ: proposalId={} error={}", p.getId(), e.getMessage());
            }
        }
        return count;
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
                DocumentRegisterRequest req = DocumentRegisterRequest.builder()
                        .documentType("CONTRACT")
                        .title("契約書PDF: ドキュメントID=" + d.getId())
                        .counterpartyType("CUSTOMER")
                        .transactionDate(d.getCreatedAt() != null ? d.getCreatedAt().toLocalDate() : LocalDate.now())
                        .direction("OUTGOING")
                        .originalName(path.getFileName().toString())
                        .contentType("application/pdf")
                        .sourceType("GENERATED")
                        .businessKey("CONTRACT_DOCUMENT:" + d.getId())
                        .versionDiscriminator("v1")
                        .targetType("CONTRACT")
                        .targetId(d.getContractId())
                        .build();

                try (InputStream is = Files.newInputStream(path)) {
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
