package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesCsvParser;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.DocumentService;
import com.ses.service.impl.StagedImportDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 同期raw CSV ingress。認可/admission、bounded temp、CLEAN stage、短いmetadata commitを順に実行する。
 * 全phaseは一つの総期限（{@link ImportIngressDeadline#TOTAL_BUDGET}）を共有する。
 */
@Service
public class ImportCsvIngestionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportCsvIngestionService.class);
    private static final long MAX_RAW_BYTES = 64L * 1024 * 1024;
    private final ImportMetadataAuthorizationService authorization;
    private final ImportMetadataService metadata;
    private final ImportAdmissionPort admission;
    private final DocumentService documents;
    private final ImportCsvUploadCommitService commit;
    private final ImportRawTempFileStore tempFiles;
    private final LongSupplier nanoClock;
    private final Duration totalBudget;

    @Autowired
    public ImportCsvIngestionService(ImportMetadataAuthorizationService authorization, ImportMetadataService metadata,
                                    ImportAdmissionPort admission, DocumentService documents,
                                    ImportCsvUploadCommitService commit, ImportRawTempFileStore tempFiles) {
        this(authorization, metadata, admission, documents, commit, tempFiles, System::nanoTime,
                ImportIngressDeadline.TOTAL_BUDGET);
    }

    /** test用。clockと総期限を差し替え、実時間のsleepなしで期限分岐を再現する。 */
    ImportCsvIngestionService(ImportMetadataAuthorizationService authorization, ImportMetadataService metadata,
                              ImportAdmissionPort admission, DocumentService documents,
                              ImportCsvUploadCommitService commit, ImportRawTempFileStore tempFiles,
                              LongSupplier nanoClock, Duration totalBudget) {
        this.authorization = authorization;
        this.metadata = metadata;
        this.admission = admission;
        this.documents = documents;
        this.commit = commit;
        this.tempFiles = Objects.requireNonNull(tempFiles, "tempFiles");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.totalBudget = Objects.requireNonNull(totalBudget, "totalBudget");
    }

    public ImportUploadJobDetail upload(ImportRawUploadHeaders headers, InputStreamSupplier bodySupplier) {
        ImportIngressDeadline deadline = ImportIngressDeadline.start(nanoClock, totalBudget);
        if (headers == null || headers.sourceCode() == null || headers.encoding() == null || bodySupplier == null) {
            throw BusinessException.of(400, "error.import.invalidUploadHeaders");
        }
        // 外側transactionへ参加するとcommit timeout（総期限）が無視されるため、body読取前に拒否する。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("raw CSV ingressは外側transactionなしで呼び出す必要があります");
        }
        ImportAuthorizedContext before = authorization.requireCurrentAuthorization();
        ImportUploadSource source = metadata.resolveActiveUploadSource(headers.sourceCode());
        requireSameContext(before, authorization.requireCurrentAuthorization());
        ImportAdmissionToken token = admission.admitBeforeBody(before, source, ImportEntity.CUSTOMER,
                SesImportV1Schema.VERSION, headers.encoding());
        requirePreAdmissionMatch(token, before, source, headers);
        requireSameContext(before, authorization.requireCurrentAuthorization());
        deadline.check("admission");

        Path temp = null;
        StagedImportDocument staged = null;
        boolean committed = false;
        boolean preserveStagedForOutcomeCheck = false;
        try {
            temp = tempFiles.create();
            RawDigest raw = copyBounded(bodySupplier.open(), temp, deadline);
            SesCsvParser.ParseSummary parsed;
            try (InputStream input = new DeadlineCheckedInputStream(Files.newInputStream(temp), deadline, "parse")) {
                parsed = new SesCsvParser().parse(input, "upload.csv", ImportEntity.CUSTOMER,
                        headers.encoding(), ignored -> { });
            }
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(raw.sha256()),
                    HexFormat.of().parseHex(parsed.sourceSha256()))) {
                throw BusinessException.of(409, "error.import.sourceHashMismatch");
            }
            ImportAdmissionManifest content = admission.admitContent(token, raw.sha256());
            requireContentManifest(token, content, raw.sha256());
            deadline.check("contentAdmission");

            ImportAuthorizedContext current = authorization.requireCurrentAuthorization();
            requireSameContext(before, current);
            ImportUploadSource currentSource = metadata.resolveActiveUploadSource(headers.sourceCode());
            if (currentSource.id() != source.id() || currentSource.version() != source.version()) {
                throw BusinessException.of(409, "error.import.sourceVersionChanged");
            }
            ImportUploadJobDetail existing = metadata.findOrReuseExistingUpload(source, headers.encoding(), raw.sha256());
            deadline.check("reuseLookup");
            if (existing != null) return existing;

            DocumentRegisterRequest documentRequest = DocumentRegisterRequest.builder()
                    .tenantId(before.tenantId()).legalEntityId(before.legalEntityId())
                    .documentType("IMPORT").title("CSV import")
                    .direction("INCOMING").sourceType("RECEIVED")
                    .originalName("upload.csv").contentType("text/csv")
                    .createdBy(before.actorUserId()).build();
            staged = documents.stageReceivedForImport(documentRequest, temp, raw.size(), raw.sha256(),
                    () -> deadline.check("stage"));
            deadline.check("stage");
            ImportAuthorizedContext beforeCommit = authorization.requireCurrentAuthorization();
            requireSameContext(before, beforeCommit);
            // commit開始前に残りを確定する。ここでの超過はcommit未開始なので、stage破棄の対象になる。
            int commitTimeoutSeconds = deadline.requireTransactionSeconds("commit");
            ImportUploadJobDetail result;
            try {
                result = commit.commit(token, content, staged, commitTimeoutSeconds);
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
                // 総期限由来のtransaction/query timeoutは、結果不確定でstageを保全したまま期限超過として返す。
                if (deadline.expired() || ImportIngressDeadline.isTimeoutFailure(uncertainCommit)) {
                    throw deadline.exceeded("commit");
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
                // 削除失敗は例外にせず通知・回収経路へ回す。成功結果や元の失敗を上書きしない。
                tempFiles.deleteOrReport(temp);
            }
        }
    }

    private static RawDigest copyBounded(InputStream input, Path target, ImportIngressDeadline deadline)
            throws IOException {
        if (input == null) throw BusinessException.of(400, "error.import.uploadReadFailed");
        try (InputStream source = new DeadlineCheckedInputStream(input, deadline, "bodyRead");
             var output = Files.newOutputStream(target)) {
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

    /**
     * read前後でingress総期限を確認する。clientが少量ずつ送り続けるslow bodyでも、各readの復帰時点で打ち切る。
     * 1回のblocking read自体はservlet containerのsocket read timeoutが上限になる。
     */
    private static final class DeadlineCheckedInputStream extends FilterInputStream {
        private final ImportIngressDeadline deadline;
        private final String phase;

        private DeadlineCheckedInputStream(InputStream input, ImportIngressDeadline deadline, String phase) {
            super(input);
            this.deadline = deadline;
            this.phase = phase;
        }

        @Override
        public int read() throws IOException {
            deadline.check(phase);
            int value = super.read();
            deadline.check(phase);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            deadline.check(phase);
            int count = super.read(buffer, offset, length);
            deadline.check(phase);
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            deadline.check(phase);
            long skipped = super.skip(count);
            deadline.check(phase);
            return skipped;
        }
    }

    @FunctionalInterface
    public interface InputStreamSupplier {
        InputStream open() throws IOException;
    }
}
