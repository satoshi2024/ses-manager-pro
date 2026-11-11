package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesCsvParser;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.DocumentService;
import com.ses.service.impl.StagedImportDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 同期raw CSV ingress。認可/admission、bounded temp、CLEAN stage、短いmetadata commitを順に実行する。 */
@Service
public class ImportCsvIngestionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportCsvIngestionService.class);
    private static final long MAX_RAW_BYTES = 64L * 1024 * 1024;
    private final ImportMetadataAuthorizationService authorization;
    private final ImportMetadataService metadata;
    private final ImportAdmissionPort admission;
    private final DocumentService documents;
    private final ImportCsvUploadCommitService commit;

    public ImportCsvIngestionService(ImportMetadataAuthorizationService authorization, ImportMetadataService metadata,
                                    ImportAdmissionPort admission, DocumentService documents,
                                    ImportCsvUploadCommitService commit) {
        this.authorization = authorization;
        this.metadata = metadata;
        this.admission = admission;
        this.documents = documents;
        this.commit = commit;
    }

    public ImportUploadJobDetail upload(ImportRawUploadHeaders headers, InputStreamSupplier bodySupplier) {
        if (headers == null || headers.sourceCode() == null || headers.encoding() == null || bodySupplier == null) {
            throw BusinessException.of(400, "error.import.invalidUploadHeaders");
        }
        ImportAuthorizedContext before = authorization.requireCurrentAuthorization();
        ImportUploadSource source = metadata.resolveActiveUploadSource(headers.sourceCode());
        requireSameContext(before, authorization.requireCurrentAuthorization());
        ImportAdmissionToken token = admission.admitBeforeBody(before, source, ImportEntity.CUSTOMER,
                SesImportV1Schema.VERSION, headers.encoding());
        requirePreAdmissionMatch(token, before, source, headers);
        requireSameContext(before, authorization.requireCurrentAuthorization());

        Path temp = null;
        StagedImportDocument staged = null;
        boolean committed = false;
        boolean preserveStagedForOutcomeCheck = false;
        try {
            temp = Files.createTempFile("ses-import-", ".csv");
            RawDigest raw = copyBounded(bodySupplier.open(), temp);
            SesCsvParser.ParseSummary parsed;
            try (InputStream input = Files.newInputStream(temp)) {
                parsed = new SesCsvParser().parse(input, "upload.csv", ImportEntity.CUSTOMER,
                        headers.encoding(), ignored -> { });
            }
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(raw.sha256()),
                    HexFormat.of().parseHex(parsed.sourceSha256()))) {
                throw BusinessException.of(409, "error.import.sourceHashMismatch");
            }
            ImportAdmissionManifest content = admission.admitContent(token, raw.sha256());
            requireContentManifest(token, content, raw.sha256());

            ImportAuthorizedContext current = authorization.requireCurrentAuthorization();
            requireSameContext(before, current);
            ImportUploadSource currentSource = metadata.resolveActiveUploadSource(headers.sourceCode());
            if (currentSource.id() != source.id() || currentSource.version() != source.version()) {
                throw BusinessException.of(409, "error.import.sourceVersionChanged");
            }
            ImportUploadJobDetail existing = metadata.findOrReuseExistingUpload(source, headers.encoding(), raw.sha256());
            if (existing != null) return existing;

            DocumentRegisterRequest documentRequest = DocumentRegisterRequest.builder()
                    .tenantId(before.tenantId()).legalEntityId(before.legalEntityId())
                    .documentType("IMPORT").title("CSV import")
                    .direction("INCOMING").sourceType("RECEIVED")
                    .originalName("upload.csv").contentType("text/csv")
                    .createdBy(before.actorUserId()).build();
            staged = documents.stageReceivedForImport(documentRequest, temp, raw.size(), raw.sha256());
            ImportAuthorizedContext beforeCommit = authorization.requireCurrentAuthorization();
            requireSameContext(before, beforeCommit);
            ImportUploadJobDetail result;
            try {
                result = commit.commit(token, content, staged);
            } catch (BusinessException conflict) {
                if (conflict.getCode() != 409) throw conflict;
                try {
                    ImportUploadJobDetail ours = metadata.findCommittedUploadForStage(source, headers.encoding(),
                            raw.sha256(), staged);
                    if (ours != null) {
                        committed = true;
                        return ours;
                    }
                } catch (RuntimeException readbackFailure) {
                    preserveStagedForOutcomeCheck = true;
                    log.error("Import conflict readback is uncertain; retain staged object for reconciliation: operationId={} storageKey={} failureClass={}",
                            staged.operationId(), staged.storageKey(), readbackFailure.getClass().getSimpleName());
                    throw conflict;
                }
                ImportUploadJobDetail winner;
                try {
                    winner = metadata.findOrReuseExistingUpload(source, headers.encoding(), raw.sha256());
                } catch (RuntimeException readbackFailure) {
                    preserveStagedForOutcomeCheck = true;
                    log.error("Import conflict winner lookup is uncertain; retain staged object for reconciliation: operationId={} storageKey={} failureClass={}",
                            staged.operationId(), staged.storageKey(), readbackFailure.getClass().getSimpleName());
                    throw conflict;
                }
                if (winner == null) throw conflict;
                try {
                    if (metadata.isUploadJobForStage(winner.jobId(), source, raw.sha256(), staged, before)) {
                        committed = true;
                    }
                } catch (RuntimeException identityFailure) {
                    preserveStagedForOutcomeCheck = true;
                    log.error("Import conflict identity readback is uncertain; retain staged object for reconciliation: operationId={} storageKey={} failureClass={}",
                            staged.operationId(), staged.storageKey(), identityFailure.getClass().getSimpleName());
                    throw conflict;
                }
                return winner;
            } catch (RuntimeException uncertainCommit) {
                try {
                    ImportUploadJobDetail ours = metadata.findCommittedUploadForStage(source, headers.encoding(),
                            raw.sha256(), staged);
                    if (ours != null) {
                        committed = true;
                        return ours;
                    }
                    ImportUploadJobDetail winner = metadata.findOrReuseExistingUpload(source, headers.encoding(),
                            raw.sha256());
                    if (winner != null) {
                        if (metadata.isUploadJobForStage(winner.jobId(), source, raw.sha256(), staged, before)) {
                            committed = true;
                        }
                        return winner;
                    }
                } catch (RuntimeException readbackFailure) {
                    preserveStagedForOutcomeCheck = true;
                    log.error("Import metadata commit outcome is uncertain; retain staged object for reconciliation: operationId={} storageKey={} failureClass={}",
                            staged.operationId(), staged.storageKey(), readbackFailure.getClass().getSimpleName());
                }
                if (!preserveStagedForOutcomeCheck) {
                    preserveStagedForOutcomeCheck = true;
                    log.error("Import metadata commit outcome is uncertain and no committed row is visible; retain staged object for reconciliation: operationId={} storageKey={} failureClass={}",
                            staged.operationId(), staged.storageKey(), uncertainCommit.getClass().getSimpleName());
                }
                throw uncertainCommit;
            }
            committed = true;
            return result;
        } catch (IOException failure) {
            throw BusinessException.of(400, "error.import.uploadReadFailed");
        } finally {
            try {
                if (staged != null && !committed && !preserveStagedForOutcomeCheck) {
                    documents.discardImportStage(staged);
                }
            } finally {
                if (temp != null) {
                    try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
                }
            }
        }
    }

    private static RawDigest copyBounded(InputStream input, Path target) throws IOException {
        if (input == null) throw BusinessException.of(400, "error.import.uploadReadFailed");
        try (InputStream source = input; var output = Files.newOutputStream(target)) {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            byte[] buffer = new byte[8192];
            long count = 0;
            while (true) {
                long remaining = MAX_RAW_BYTES - count;
                if (remaining == 0) {
                    if (source.read() != -1) throw BusinessException.of(413, "error.import.uploadTooLarge");
                    break;
                }
                int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                count += read;
            }
            return new RawDigest(count, HexFormat.of().formatHex(digest.digest()));
        }
    }

    private static void requirePreAdmissionMatch(ImportAdmissionToken token, ImportAuthorizedContext context,
                                                 ImportUploadSource source, ImportRawUploadHeaders headers) {
        if (token == null || token.fixtureKey() == null || token.fixtureKey().isBlank()
                || !context.tenantId().equals(token.tenantId()) || context.actorUserId() != token.actorUserId()
                || context.legalEntityId() != token.legalEntityId()
                || token.sourceSystemId() != source.id() || token.sourceVersion() != source.version()
                || token.entity() != ImportEntity.CUSTOMER || !SesImportV1Schema.VERSION.equals(token.schemaVersion())
                || token.encoding() != headers.encoding()) {
            throw BusinessException.of(403, "error.import.admissionMismatch");
        }
    }

    private static void requireContentManifest(ImportAdmissionToken token, ImportAdmissionManifest manifest,
                                               String sha256) {
        if (manifest == null || !token.fixtureKey().equals(manifest.fixtureKey())
                || !token.tenantId().equals(manifest.tenantId()) || token.legalEntityId() != manifest.legalEntityId()
                || token.sourceSystemId() != manifest.sourceSystemId() || token.sourceVersion() != manifest.sourceVersion()
                || manifest.entity() != ImportEntity.CUSTOMER || !SesImportV1Schema.VERSION.equals(manifest.schemaVersion())
                || token.encoding() != manifest.encoding() || !"A1A_CONTENT".equals(manifest.phase())
                || !sha256.equals(manifest.sourceSha256())
                || manifest.documentId() != null || manifest.documentVersionId() != null
                || manifest.documentVersionNo() != null || manifest.documentSha256() != null) {
            throw BusinessException.of(403, "error.import.admissionMismatch");
        }
    }

    private static void requireSameContext(ImportAuthorizedContext expected, ImportAuthorizedContext actual) {
        if (!expected.equals(actual)) throw BusinessException.of(403, "error.forbidden");
    }

    private record RawDigest(long size, String sha256) { }

    @FunctionalInterface
    public interface InputStreamSupplier {
        InputStream open() throws IOException;
    }
}
