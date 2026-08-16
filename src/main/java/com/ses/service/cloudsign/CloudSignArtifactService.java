package com.ses.service.cloudsign;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.enums.CloudSignErrorCode;
import com.ses.common.enums.DispatchState;
import com.ses.common.enums.FileKind;
import com.ses.config.CloudSignProperties;
import com.ses.dto.cloudsign.PdfDownload;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.entity.Contract;
import com.ses.entity.ContractDocument;
import com.ses.mapper.ContractDocumentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.service.DocumentService;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.List;

/**
 * 締結済みPDF・合意締結証明書の安全回収（HFP-02-06）。
 *
 * <p>status=2（締結済）確認後、送信時に保存したfile IDのPDFと/certificateのPDFを個別取得し、
 * quarantine → size/MIME/magic/EOF検証 → FileKind.CONTRACT_PDFでscan → SHA-256 →
 * DocumentService(文書台帳)のatomic storage/ledger登録 → archive IDと別hashをCAS保存する
 * （HFP-02-AC-07-01〜06）。scanner/ledger/storageの欠落はfail-closedで公開しない。
 */
@Slf4j
@Service
public class CloudSignArtifactService {

    private static final String CONTENT_TYPE_PDF = "application/pdf";

    private final ContractDocumentMapper mapper;
    private final ContractMapper contractMapper;
    private final CloudSignApiClient api;
    private final CloudSignRateLimiter rateLimiter;
    private final CloudSignProperties properties;
    private final CloudSignMonitor monitor;
    private final com.ses.mapper.SysUserMapper sysUserMapper;
    private final ObjectProvider<DocumentService> documentServiceProvider;
    private final ObjectProvider<FileScanner> fileScannerProvider;
    private final TransactionTemplate transactionTemplate;

    public CloudSignArtifactService(ContractDocumentMapper mapper,
                                    ContractMapper contractMapper,
                                    CloudSignApiClient api,
                                    CloudSignRateLimiter rateLimiter,
                                    CloudSignProperties properties,
                                    CloudSignMonitor monitor,
                                    com.ses.mapper.SysUserMapper sysUserMapper,
                                    ObjectProvider<DocumentService> documentServiceProvider,
                                    ObjectProvider<FileScanner> fileScannerProvider,
                                    TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.contractMapper = contractMapper;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.monitor = monitor;
        this.sysUserMapper = sysUserMapper;
        this.documentServiceProvider = documentServiceProvider;
        this.fileScannerProvider = fileScannerProvider;
        this.transactionTemplate = transactionTemplate;
    }

    /** artifact未回収の締結済行をbatch処理する（poll schedulerから呼ばれる）。 */
    public int collectPending(int limit) {
        if (!properties.isEnabled()) {
            return 0;
        }
        List<ContractDocument> pending = mapper.selectList(new LambdaQueryWrapper<ContractDocument>()
                .eq(ContractDocument::getDispatchState, DispatchState.COMPLETED.name())
                .and(w -> w.isNull(ContractDocument::getSignedArchiveDocumentId)
                        .or().isNull(ContractDocument::getCertificateArchiveDocumentId))
                .orderByAsc(ContractDocument::getId)
                .last("LIMIT " + Math.max(1, limit)));
        int processed = 0;
        for (ContractDocument doc : pending) {
            try {
                collectFor(doc);
                processed++;
            } catch (RuntimeException e) {
                log.warn("[契約書artifact] 回収失敗をbatch全体へ波及させない: docId={} error={}",
                        doc.getId(), safeError(e), e);
            }
        }
        return processed;
    }

    private void collectFor(ContractDocument doc) {
        if (doc.getSignedArchiveDocumentId() == null) {
            collectSigned(doc);
            // signed処理がversionを進めた可能性があるため、certificateは最新versionで再読込する
            doc = mapper.selectById(doc.getId());
        }
        if (doc != null && doc.getCertificateArchiveDocumentId() == null) {
            collectCertificate(doc);
        }
    }

    // ------------------------------------------------------------------
    // signed PDF
    // ------------------------------------------------------------------

    private boolean collectSigned(ContractDocument doc) {
        if (!isCompleted(doc)) {
            return false;
        }
        String fileId = doc.getCloudsignFileId();
        if (fileId == null || fileId.isBlank()) {
            // 送信時file IDが無い: 同一性を証明できないため停止（provider再送・新規取得しない）
            recordFinding(doc, "SIGNED_NO_FILE_ID");
            return false;
        }
        // legacy: ローカル signed_pdf_path（backfillがhash再計算済み）があればローカル移行を優先
        if (doc.getSignedPdfPath() != null && !doc.getSignedPdfPath().isBlank()) {
            if (migrateLocalSigned(doc)) {
                return true;
            }
        }
        rateLimiter.acquire();
        PdfDownload download;
        try {
            download = api.downloadFile(doc.getCloudsignDocumentId(), fileId);
        } catch (CloudSignApiException e) {
            monitor.recordError(e.getCode());
            recordFinding(doc, "SIGNED_DOWNLOAD_FAILED:" + e.getCode().name());
            return false;
        }
        return storeArtifact(doc, download, ArtifactKind.SIGNED, "signed-" + doc.getId() + ".pdf",
                doc.getSignedPdfSha256(), doc.getSignedArchiveDocumentId(),
                "SIGNED_PDF", "SIGNED", "OUTGOING");
    }

    /** legacy signed_pdf_path（backfillがrecomputeしたhash）を台帳へ移行候補として登録する。 */
    private boolean migrateLocalSigned(ContractDocument doc) {
        try {
            Path local = Paths.get(doc.getSignedPdfPath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(local)) {
                recordFinding(doc, "SIGNED_LOCAL_MISSING");
                return false;
            }
            String localHash = sha256Hex(Files.readAllBytes(local));
            if (doc.getSignedPdfSha256() != null && !doc.getSignedPdfSha256().equals(localHash)) {
                recordFinding(doc, "SIGNED_LOCAL_HASH_MISMATCH");
                return false;
            }
            return storeLocal(local, localHash, doc, ArtifactKind.SIGNED, "signed-" + doc.getId() + ".pdf",
                    doc.getSignedPdfSha256(), doc.getSignedArchiveDocumentId(),
                    "SIGNED_PDF", "SIGNED", "OUTGOING");
        } catch (Exception e) {
            recordFinding(doc, "SIGNED_LOCAL_UNREADABLE");
            return false;
        }
    }

    // ------------------------------------------------------------------
    // certificate
    // ------------------------------------------------------------------

    private boolean collectCertificate(ContractDocument doc) {
        if (!isCompleted(doc)) {
            return false;
        }
        if (doc.getCertificatePath() != null && !doc.getCertificatePath().isBlank()) {
            if (migrateLocalCertificate(doc)) {
                return true;
            }
        }
        rateLimiter.acquire();
        PdfDownload download;
        try {
            download = api.downloadCertificate(doc.getCloudsignDocumentId());
        } catch (CloudSignApiException e) {
            monitor.recordError(e.getCode());
            recordFinding(doc, "CERT_DOWNLOAD_FAILED:" + e.getCode().name());
            return false;
        }
        return storeArtifact(doc, download, ArtifactKind.CERTIFICATE,
                "certificate-" + doc.getId() + ".pdf",
                doc.getCertificateSha256(), doc.getCertificateArchiveDocumentId(),
                "ESIGN_CERT", "CERTIFICATE", "INCOMING");
    }

    private boolean migrateLocalCertificate(ContractDocument doc) {
        try {
            Path local = Paths.get(doc.getCertificatePath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(local)) {
                recordFinding(doc, "CERT_LOCAL_MISSING");
                return false;
            }
            String localHash = sha256Hex(Files.readAllBytes(local));
            if (doc.getCertificateSha256() != null && !doc.getCertificateSha256().equals(localHash)) {
                recordFinding(doc, "CERT_LOCAL_HASH_MISMATCH");
                return false;
            }
            return storeLocal(local, localHash, doc, ArtifactKind.CERTIFICATE,
                    "certificate-" + doc.getId() + ".pdf",
                    doc.getCertificateSha256(), doc.getCertificateArchiveDocumentId(),
                    "ESIGN_CERT", "CERTIFICATE", "INCOMING");
        } catch (Exception e) {
            recordFinding(doc, "CERT_LOCAL_UNREADABLE");
            return false;
        }
    }

    // ------------------------------------------------------------------
    // common pipeline
    // ------------------------------------------------------------------

    private boolean storeArtifact(ContractDocument doc, PdfDownload download, ArtifactKind kind,
                                  String fileName, String existingHash, Long existingArchiveId,
                                  String documentType, String sourceType, String direction) {
        if (download == null) {
            recordFinding(doc, kind.code + "_DOWNLOAD_NULL");
            return false;
        }
        Path temp = download.tempPath();
        try {
            String validation = validatePdf(temp, download.sizeBytes(), download.contentType());
            if (validation != null) {
                recordFinding(doc, kind.code + "_INVALID:" + validation);
                return false;
            }
            FileScanResult scan = scanQuarantine(temp, doc);
            if (scan == null || scan.status() != FileScanResult.Status.CLEAN) {
                // scanner不在/失敗/感染: fail-closed。quarantineのまま公開しない
                recordFinding(doc, kind.code + "_SCAN_REJECTED:" + (scan == null ? "UNAVAILABLE" : scan.status().name()));
                return false;
            }
            String hash = sha256Hex(Files.readAllBytes(temp));
            if (existingHash != null && existingHash.equals(hash) && existingArchiveId != null) {
                // 同一hashかつ台帳登録済み: no-op（再取得しない・二重登録しない）
                log.info("[契約書artifact] 同一hashの再取得はno-op: docId={} kind={}", doc.getId(), kind.code);
                return true;
            }
            if (existingHash != null && !existingHash.equals(hash)) {
                // 相違hash: 既存版を上書きせずfinding（integrity alert）
                recordFinding(doc, "ARTIFACT_HASH_CHANGED:" + kind.code);
                return false;
            }
            // 同一hashでもarchive未登録（crash復旧等）は台帳登録を進める（REV-010）
            DocumentService ledger = documentServiceProvider.getIfAvailable();
            if (ledger == null) {
                recordFinding(doc, kind.code + "_LEDGER_UNAVAILABLE");
                return false;
            }
            Long archiveId = withSystemPrincipal(() -> {
                try (InputStream in = Files.newInputStream(temp)) {
                    com.ses.entity.Document registered = ledger.registerReceived(
                            registerRequest(doc, fileName, documentType, sourceType, direction, hash, kind), in);
                    return registered.getId();
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            if (archiveId == null) {
                recordFinding(doc, kind.code + "_LEDGER_NO_ID");
                return false;
            }
            boolean saved = saveArtifactIds(doc, kind, hash, archiveId);
            if (!saved) {
                // storage成功・DB失敗: orphan safety windowで補償（design §7.2 step 7）
                recordFinding(doc, "ARTIFACT_DB_SAVE_FAILED:" + kind.code);
                return false;
            }
            log.info("[契約書artifact] 回収完了: docId={} kind={} hashPrefix={} archiveId={}",
                    doc.getId(), kind.code, hash.substring(0, 8), archiveId);
            return true;
        } catch (Exception e) {
            log.warn("[契約書artifact] storeArtifact例外: kind={} docId={}", kind.code, doc.getId(), e);
            recordFinding(doc, kind.code + "_FAILED");
            return false;
        } finally {
            deleteQuietly(temp);
        }
    }

    private boolean storeLocal(Path local, String hash, ContractDocument doc, ArtifactKind kind,
                               String fileName, String existingHash, Long existingArchiveId,
                               String documentType, String sourceType, String direction) {
        try {
            if (existingHash != null && existingHash.equals(hash)) {
                // 同一hash: ただしarchive id未登録なので登録だけ進める（no-opではない）
            }
            FileScanResult scan = scanQuarantine(local, doc);
            if (scan == null || scan.status() != FileScanResult.Status.CLEAN) {
                recordFinding(doc, kind.code + "_SCAN_REJECTED:" + (scan == null ? "UNAVAILABLE" : scan.status().name()));
                return false;
            }
            DocumentService ledger = documentServiceProvider.getIfAvailable();
            if (ledger == null) {
                recordFinding(doc, kind.code + "_LEDGER_UNAVAILABLE");
                return false;
            }
            Long archiveId = withSystemPrincipal(() -> {
                try (InputStream in = Files.newInputStream(local)) {
                    com.ses.entity.Document registered = ledger.registerReceived(
                            registerRequest(doc, fileName, documentType, sourceType, direction, hash, kind), in);
                    return registered.getId();
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            boolean saved = saveArtifactIds(doc, kind, hash, archiveId);
            if (!saved) {
                recordFinding(doc, "ARTIFACT_DB_SAVE_FAILED:" + kind.code);
                return false;
            }
            log.info("[契約書artifact] legacy artifact移行: docId={} kind={} hashPrefix={} archiveId={}",
                    doc.getId(), kind.code, hash.substring(0, 8), archiveId);
            return true;
        } catch (Exception e) {
            recordFinding(doc, kind.code + "_LOCAL_FAILED");
            return false;
        }
    }

    private String validatePdf(Path path, long size, String contentType) {
        if (size <= 0 || size > properties.getMaxPdfBytes()) {
            return "SIZE";
        }
        if (contentType == null || contentType.isBlank()
                || !contentType.toLowerCase().startsWith(CONTENT_TYPE_PDF)) {
            // 実際のcontent-type値はPIIではないが、finding codeを40桁以内に保つため値は含めない
            return "CONTENT_TYPE";
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (!FileKind.CONTRACT_PDF.isMagicBytesAllowed("pdf", bytes)) {
                return "MAGIC_EOF";
            }
        } catch (Exception e) {
            return "UNREADABLE";
        }
        return null;
    }

    private FileScanResult scanQuarantine(Path path, ContractDocument doc) {
        FileScanner scanner = fileScannerProvider.getIfAvailable();
        if (scanner == null) {
            monitor.recordResultUnknown();
            return FileScanResult.unavailable("scanner is not configured");
        }
        try {
            return scanner.scan(path, FileKind.CONTRACT_PDF);
        } catch (RuntimeException e) {
            monitor.recordResultUnknown();
            return FileScanResult.unavailable("scanner failed");
        }
    }

    private DocumentRegisterRequest registerRequest(ContractDocument doc, String fileName,
                                                    String documentType, String sourceType,
                                                    String direction, String hash, ArtifactKind kind) {
        Contract contract = contractMapper.selectById(doc.getContractId());
        return DocumentRegisterRequest.builder()
                .documentType(documentType)
                .title(kind.label + ": " + (contract != null && contract.getContractNo() != null
                        ? contract.getContractNo() : "ID:" + doc.getContractId()))
                .documentNo(contract != null ? contract.getContractNo() : null)
                .counterpartyType("CUSTOMER")
                .counterpartyId(contract != null ? contract.getCustomerId() : null)
                .transactionDate(doc.getCompletedAt() != null ? doc.getCompletedAt().toLocalDate()
                        : LocalDate.now())
                .amount(contract != null ? contract.getSellingPrice() : null)
                .direction(direction)
                .originalName(fileName)
                .contentType(CONTENT_TYPE_PDF)
                .sourceType(sourceType)
                .businessKey("CONTRACT:" + doc.getContractId())
                .versionDiscriminator(kind.code.toLowerCase() + "-" + hash.substring(0, 16))
                .externalId(doc.getCloudsignDocumentId())
                .targetType("CONTRACT")
                .targetId(doc.getContractId())
                .build();
    }

    /** archive IDとhashのCAS保存。DB失敗時はstorage orphan（safety window補償）。 */
    private boolean saveArtifactIds(ContractDocument doc, ArtifactKind kind, String hash, Long archiveId) {
        Integer updated = transactionTemplate.execute(status -> mapper.casArtifactSave(
                doc.getId(), safeVersion(doc), DispatchState.COMPLETED.name(),
                kind == ArtifactKind.SIGNED ? hash : null,
                kind == ArtifactKind.SIGNED ? archiveId : null,
                kind == ArtifactKind.CERTIFICATE ? hash : null,
                kind == ArtifactKind.CERTIFICATE ? archiveId : null));
        return updated != null && updated == 1;
    }

    private void recordFinding(ContractDocument doc, String code) {
        // last_provider_error_codeはVARCHAR(40)。超過分は安全に切詰める
        String safeCode = code == null ? "UNKNOWN"
                : code.length() > 40 ? code.substring(0, 40) : code;
        Integer updated = transactionTemplate.execute(status -> mapper.casStatusFinding(
                doc.getId(), safeVersion(doc), doc.getDispatchState(),
                doc.getCloudsignStatus(), doc.getStatus(), safeCode));
        if (updated != null && updated == 1) {
            log.warn("[契約書artifact] finding: docId={} code={}", doc.getId(), safeCode);
        }
    }

    // ------------------------------------------------------------------
    // download（source/signed/certificateを分離。scopeはcontrollerが親契約で検証）
    // ------------------------------------------------------------------

    /** 締結済みPDFを文書台帳から開く。archive未登録のlegacyはローカルpathの安全なreadのみ。 */
    public ArtifactDownload downloadSigned(ContractDocument doc) {
        if (doc.getSignedArchiveDocumentId() != null) {
            DocumentService ledger = documentServiceProvider.getIfAvailable();
            if (ledger == null) {
                throw com.ses.common.exception.BusinessException.of("error.contract.document.fileNotFound");
            }
            InputStream in = ledger.download(doc.getSignedArchiveDocumentId(), null);
            return new ArtifactDownload(in, "signed-" + doc.getId() + ".pdf", CONTENT_TYPE_PDF);
        }
        return readLegacyLocal(doc.getSignedPdfPath(), "signed-" + doc.getId() + ".pdf");
    }

    /** 合意締結証明書を文書台帳から開く。 */
    public ArtifactDownload downloadCertificate(ContractDocument doc) {
        if (doc.getCertificateArchiveDocumentId() != null) {
            DocumentService ledger = documentServiceProvider.getIfAvailable();
            if (ledger == null) {
                throw com.ses.common.exception.BusinessException.of("error.contract.document.fileNotFound");
            }
            InputStream in = ledger.download(doc.getCertificateArchiveDocumentId(), null);
            return new ArtifactDownload(in, "certificate-" + doc.getId() + ".pdf", CONTENT_TYPE_PDF);
        }
        return readLegacyLocal(doc.getCertificatePath(), "certificate-" + doc.getId() + ".pdf");
    }

    /** legacy pathの安全なread（upload root内の正規化pathのみ）。 */
    private ArtifactDownload readLegacyLocal(String rawPath, String fileName) {
        if (rawPath == null || rawPath.isBlank()) {
            throw com.ses.common.exception.BusinessException.of("error.contract.document.fileNotFound");
        }
        try {
            Path root = Paths.get(properties.getLegacyReadBasePath()).toAbsolutePath().normalize();
            Path target = Paths.get(rawPath).toAbsolutePath().normalize();
            if (!target.startsWith(root)) {
                throw com.ses.common.exception.BusinessException.of("error.contract.document.fileNotFound");
            }
            if (!Files.isRegularFile(target)) {
                throw com.ses.common.exception.BusinessException.of("error.contract.document.fileNotFound");
            }
            return new ArtifactDownload(Files.newInputStream(target), fileName, CONTENT_TYPE_PDF);
        } catch (java.io.IOException e) {
            throw com.ses.common.exception.BusinessException.of("error.contract.document.fileNotFound");
        }
    }

    /** 送信原本PDF（ローカル生成物）。metadata PUBLISHED/CLEAN確認は呼び出し側の既存download経路で行う。 */
    public record ArtifactDownload(InputStream stream, String fileName, String contentType) {
    }

    private boolean isCompleted(ContractDocument doc) {
        return DispatchState.COMPLETED.name().equals(doc.getDispatchState())
                && doc.getCloudsignStatus() != null && doc.getCloudsignStatus() == 2;
    }

    /**
     * scheduler（認証なし）から文書台帳へ登録するためのsystem principal。
     * 台帳のcreated_byはNOT NULLのため、seed管理者（username=admin）のIDをシステム操作者として設定する。
     * 監査上は「システム回収」として文書ID/操作IDが追跡可能。
     */
    private Long systemUserId() {
        try {
            com.ses.entity.SysUser admin = sysUserMapper.selectByUsername("admin");
            if (admin != null && admin.getId() != null) {
                return admin.getId();
            }
        } catch (RuntimeException ignored) {
            // 解決不能時はseed既定IDへフォールバック（V2でadmin=1）
        }
        return 1L;
    }

    private <T> T withSystemPrincipal(java.util.function.Supplier<T> action) {
        org.springframework.security.core.Authentication previous =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        try {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                            String.valueOf(systemUserId()), null, java.util.List.of()));
            return action.get();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // temp file cleanup failureは無視（quarantine残はscanner/orphan経路で回収）
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte v : digest) {
            sb.append(String.format("%02x", v));
        }
        return sb.toString();
    }

    private static int safeVersion(ContractDocument doc) {
        return doc.getVersion() == null ? 0 : doc.getVersion();
    }

    private static String safeError(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private enum ArtifactKind {
        SIGNED("SIGNED", "署名済 PDF"),
        CERTIFICATE("CERT", "合意締結証明書");

        final String code;
        final String label;

        ArtifactKind(String code, String label) {
            this.code = code;
            this.label = label;
        }
    }
}
