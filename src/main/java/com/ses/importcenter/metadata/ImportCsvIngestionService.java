package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.LogRedaction;
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
import java.io.OutputStream;
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
 *
 * <p>総期限（B-NF06-1）：{@link ImportIngressDeadline#TOTAL_BUDGET}（120秒）を{@link #upload}の最初の文で開始し、
 * 全phaseで共有する。HTTP受信・servlet filter・controllerまでの時間は含まない。これは
 * 「phase境界・read loopでの期限確認」と「各操作自身のtimeout」を組み合わせたsoft deadlineで、超過を検出した時点で
 * 503（{@code error.import.uploadDeadlineExceeded}）を返す。単一のブロッキング操作は途中で中断できないため、最悪超過量と
 * 対象外の操作は{@link ImportIngressDeadline}に列挙する（硬い上限化は後続課題）。
 *
 * <p>上限超過（B-NF06-4）：chunked転送等でfilterのContent-Length検査を通過したbodyが64MiBを超えた場合も、
 * filterと同じHTTP 413（{@code error.import.uploadTooLarge}）で返す。
 *
 * <p>metadata commitの失敗は、未commitが確定しているもの（transaction開始失敗・コールバック内失敗・rollback）と
 * COMMIT処理自体の失敗（結果不確定）とに分け、前者は本requestのstageを破棄し、後者だけreadbackのうえ保全する。
 * 保全したstageと補償削除に失敗したstageは{@link ImportStageReconciliationLedger}へ記録し、
 * {@link ImportStageReconciler}が後から回収する（A-NF06-2）。
 *
 * <p>別法人（B-NF06-5）：同一identityのjobが別法人に属する場合は、内容の存在を判定させないよう
 * 認可context変化と同じ汎用403（{@code error.forbidden}）で拒否する。
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
    private final ImportStageReconciliationLedger stageLedger;
    private final LongSupplier nanoClock;
    private final Duration totalBudget;

    @Autowired
    public ImportCsvIngestionService(ImportMetadataAuthorizationService authorization, ImportMetadataService metadata,
                                    ImportAdmissionPort admission, DocumentService documents,
                                    ImportCsvUploadCommitService commit, ImportRawTempFileStore tempFiles,
                                    ImportStageReconciliationLedger stageLedger) {
        this(authorization, metadata, admission, documents, commit, tempFiles, stageLedger, System::nanoTime,
                ImportIngressDeadline.TOTAL_BUDGET);
    }

    /** test用。clockと総期限を差し替え、実時間のsleepなしで期限分岐を再現する。 */
    ImportCsvIngestionService(ImportMetadataAuthorizationService authorization, ImportMetadataService metadata,
                              ImportAdmissionPort admission, DocumentService documents,
                              ImportCsvUploadCommitService commit, ImportRawTempFileStore tempFiles,
                              ImportStageReconciliationLedger stageLedger, LongSupplier nanoClock,
                              Duration totalBudget) {
        this.authorization = authorization;
        this.metadata = metadata;
        this.admission = admission;
        this.documents = documents;
        this.commit = commit;
        this.tempFiles = Objects.requireNonNull(tempFiles, "tempFiles");
        this.stageLedger = Objects.requireNonNull(stageLedger, "stageLedger");
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
        Throwable primaryFailure = null;
        try {
            temp = tempFiles.create();
            RawDigest raw = copyBounded(openBody(bodySupplier), temp, deadline);
            SesCsvParser.ParseSummary parsed;
            try (InputStream input = new DeadlineCheckedInputStream(Files.newInputStream(temp), deadline,
                    "parse", false)) {
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
            ImportUploadJobDetail existing;
            try {
                existing = metadata.findOrReuseExistingUpload(source, headers.encoding(), raw.sha256());
            } catch (ImportIdentityOwnedByOtherLegalEntityException otherLegalEntity) {
                throw crossLegalEntityRejection("reuseLookup", otherLegalEntity);
            }
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
            } catch (ImportCommitOutcomeUnknownException unknown) {
                if (neverInserted(staged)) {
                    // metadata行のIDが未採番ならinsertに到達しておらず、未commitが確定している。
                    log.warn("[取込] COMMIT結果不明として通知されたがmetadata未insertのため、未commitとしてstageを破棄します: "
                            + "operationId={} failureClass={}", staged.operationId(), LogRedaction.exceptionType(unknown));
                    throw notCommittedFailure(deadline, unknown);
                }
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
                        // identity uniqueにより別jobが確定済みなら本stageのjobはcommitされ得ない。
                        if (metadata.isUploadJobForStage(winner.jobId(), source, raw.sha256(), staged, before)) {
                            committed = true;
                        }
                        return winner;
                    }
                    preserveStagedForOutcomeCheck = true;
                    log.error("[取込] COMMIT結果が不確定で本stageの行も見えないため、stageを照合用に保全します: "
                                    + "operationId={} storageKey={} failureClass={}", staged.operationId(),
                            staged.storageKey(), LogRedaction.exceptionType(unknown.getCause()));
                } catch (ImportIdentityOwnedByOtherLegalEntityException otherLegalEntity) {
                    // 同一identityのjobが別法人でcommit済みなら、identity一意制約により本stageのjobはcommitされ得ない。
                    // stageはfinallyで破棄し、別法人のjobの存在は汎用403で隠す。
                    throw crossLegalEntityRejection("commitReadback", otherLegalEntity);
                } catch (RuntimeException readbackFailure) {
                    preserveStagedForOutcomeCheck = true;
                    log.error("[取込] COMMIT結果が不確定でreadbackにも失敗したため、stageを照合用に保全します: "
                                    + "operationId={} storageKey={} failureClass={} readbackFailureClass={}",
                            staged.operationId(), staged.storageKey(), LogRedaction.exceptionType(unknown.getCause()),
                            LogRedaction.exceptionType(readbackFailure));
                }
                // 保全したstageは照合台帳へ記録し、ImportStageReconcilerが後から行方を確定・回収する（A-NF06-2）。
                stageLedger.recordRetained(before.tenantId(), staged);
                if (deadline.expired() || ImportIngressDeadline.isTimeoutFailure(unknown)) {
                    BusinessException exceeded = deadline.exceeded("commit");
                    exceeded.addSuppressed(unknown);
                    throw exceeded;
                }
                throw unknown;
            } catch (BusinessException conflict) {
                // BusinessExceptionはtransaction開始前の検証かコールバック内（rollback済み）で発生し、未commitが確定している。
                if (conflict.getCode() != 409) throw conflict;
                try {
                    ImportUploadJobDetail ours = metadata.findCommittedUploadForStage(source, headers.encoding(),
                            raw.sha256(), staged);
                    if (ours != null) {
                        committed = true;
                        return ours;
                    }
                    ImportUploadJobDetail winner = metadata.findOrReuseExistingUpload(source, headers.encoding(),
                            raw.sha256());
                    if (winner == null) throw conflict;
                    if (metadata.isUploadJobForStage(winner.jobId(), source, raw.sha256(), staged, before)) {
                        committed = true;
                    }
                    return winner;
                } catch (ImportIdentityOwnedByOtherLegalEntityException otherLegalEntity) {
                    // 一意性競合の相手が別法人のjob。409（内容存在の示唆）ではなく汎用403で返し、stageはfinallyで破棄する。
                    throw crossLegalEntityRejection("conflictReadback", otherLegalEntity);
                } catch (RuntimeException readbackFailure) {
                    if (readbackFailure == conflict) throw conflict;
                    // 本stageのtransactionはrollback済みのため、winnerを確認できなくてもstageはfinallyで破棄してよい。
                    log.warn("[取込] 一意性競合後のreadbackに失敗しました。本stageは未commit確定のため破棄します: "
                                    + "operationId={} readbackFailureClass={}", staged.operationId(),
                            LogRedaction.exceptionType(readbackFailure));
                    conflict.addSuppressed(readbackFailure);
                    throw conflict;
                }
            } catch (RuntimeException notCommitted) {
                // transaction開始失敗（CannotCreateTransactionException）、書込gate・lock待ちのtimeout
                // （QueryTimeoutException/CannotAcquireLockException等）、insert前後の例外はCOMMIT未発行で未commitが確定している。
                log.warn("[取込] metadata commitは未commitで確定したため、本requestのstageを破棄します: "
                        + "operationId={} failureClass={}", staged.operationId(), LogRedaction.exceptionType(notCommitted));
                throw notCommittedFailure(deadline, notCommitted);
            }
            committed = true;
            return result;
        } catch (ClientBodyReadException | SesCsvParser.CsvFormatException clientFailure) {
            BusinessException mapped = BusinessException.of(400, "error.import.uploadReadFailed");
            primaryFailure = mapped;
            throw mapped;
        } catch (IOException serverFailure) {
            // 一時ファイルの作成・書込・再読込はserver側の障害。client入力の誤りとして400にしない。
            log.error("[取込] raw CSV一時ファイルの入出力に失敗しました: exceptionType={}",
                    LogRedaction.exceptionType(serverFailure));
            BusinessException mapped = BusinessException.of(500, "error.import.tempStorageFailed");
            primaryFailure = mapped;
            throw mapped;
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                if (staged != null && !committed && !preserveStagedForOutcomeCheck) {
                    discardOwnedStage(staged, primaryFailure, before.tenantId());
                }
            } finally {
                // 削除失敗は例外にせず通知・回収経路へ回す。成功結果や元の失敗を上書きしない。
                tempFiles.deleteOrReport(temp);
            }
        }
    }

    /**
     * 本requestが所有するstageを破棄する。元の失敗がある場合は補償削除の失敗で上書きせずsuppressedとして残す。
     * 成功応答の途中（別winnerを返す場合）だけは補償失敗を隠さず投げ、500で返す（B-NF06-5で受容済み）。
     * どちらの経路でも残ったobjectは照合台帳へ記録し、metric {@code ses.import.stage.discard.failures}でアラートできるようにする。
     */
    private void discardOwnedStage(StagedImportDocument staged, Throwable primaryFailure, String tenantId) {
        try {
            documents.discardImportStage(staged);
        } catch (RuntimeException discardFailure) {
            if (primaryFailure == null) {
                stageLedger.recordDiscardFailure(tenantId, staged, "winner");
                log.error("[取込] 別jobを返す途中でstage補償削除に失敗したため500を返します（アラート対象。objectは照合台帳から回収）: "
                                + "operationId={} discardFailureClass={}", staged.operationId(),
                        LogRedaction.exceptionType(discardFailure));
                throw discardFailure;
            }
            stageLedger.recordDiscardFailure(tenantId, staged, "failure");
            primaryFailure.addSuppressed(discardFailure);
            log.error("[取込] 失敗requestのstage補償削除にも失敗しました。元の失敗を優先して返します: "
                            + "operationId={} failureClass={} discardFailureClass={}", staged.operationId(),
                    LogRedaction.exceptionType(primaryFailure), LogRedaction.exceptionType(discardFailure));
        }
    }

    /**
     * 同一identityのjobが別法人にある場合の拒否。認可context変化（{@link #requireSameContext}）と同じcode・keyにし、
     * 別法人のjob・内容の有無を応答から判定できないようにする。ログにも別法人の情報は出さない。
     */
    private static BusinessException crossLegalEntityRejection(String phase,
                                                               ImportIdentityOwnedByOtherLegalEntityException cause) {
        log.warn("[取込] 同一内容のjobが現在の法人の範囲外にあるため、存在を開示しない汎用403で拒否します: phase={}", phase);
        BusinessException rejection = BusinessException.of(403, "error.forbidden");
        rejection.addSuppressed(cause);
        return rejection;
    }

    private static RuntimeException notCommittedFailure(ImportIngressDeadline deadline, RuntimeException failure) {
        if (deadline.expired() || ImportIngressDeadline.isTimeoutFailure(failure)) {
            BusinessException exceeded = deadline.exceeded("commit");
            exceeded.addSuppressed(failure);
            return exceeded;
        }
        return failure;
    }

    private static boolean neverInserted(StagedImportDocument staged) {
        return staged.document() == null || staged.version() == null
                || staged.document().getId() == null || staged.version().getId() == null;
    }

    private static InputStream openBody(InputStreamSupplier bodySupplier) throws ClientBodyReadException {
        try {
            return bodySupplier.open();
        } catch (IOException failure) {
            throw new ClientBodyReadException(failure);
        }
    }

    private static RawDigest copyBounded(InputStream input, Path target, ImportIngressDeadline deadline)
            throws IOException {
        if (input == null) throw BusinessException.of(400, "error.import.uploadReadFailed");
        try (InputStream source = new DeadlineCheckedInputStream(input, deadline, "bodyRead", true);
             OutputStream output = Files.newOutputStream(target)) {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            byte[] buffer = new byte[8192];
            long count = 0;
            while (true) {
                long remaining = MAX_RAW_BYTES - count;
                if (remaining == 0) {
                    // chunked等でfilterのContent-Length検査を通過した超過。filter層と同じHTTP 413で返す
                    // （SafeErrorPolicy.safeHttpCodeとGlobalExceptionHandlerが413を許可する。B-NF06-4）。
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

    /** client body（servlet request stream）の読取失敗。server側の一時ファイル障害と区別して400にする。 */
    private static final class ClientBodyReadException extends IOException {
        private static final long serialVersionUID = 1L;

        private ClientBodyReadException(IOException cause) {
            super("取込bodyの読取に失敗しました", cause);
        }
    }

    /**
     * read前後でingress総期限を確認する。clientが少量ずつ送り続けるslow bodyでも、各readの復帰時点で打ち切る。
     * 1回のblocking read自体は中断できず、servlet containerのsocket read timeoutが上限になる。
     * {@code clientSource}がtrueの場合、下位streamのIOExceptionをclient由来として{@link ClientBodyReadException}へ包む。
     */
    private static final class DeadlineCheckedInputStream extends FilterInputStream {
        private final ImportIngressDeadline deadline;
        private final String phase;
        private final boolean clientSource;

        private DeadlineCheckedInputStream(InputStream input, ImportIngressDeadline deadline, String phase,
                                           boolean clientSource) {
            super(input);
            this.deadline = deadline;
            this.phase = phase;
            this.clientSource = clientSource;
        }

        @Override
        public int read() throws IOException {
            deadline.check(phase);
            int value;
            try {
                value = super.read();
            } catch (IOException failure) {
                throw classify(failure);
            }
            deadline.check(phase);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            deadline.check(phase);
            int count;
            try {
                count = super.read(buffer, offset, length);
            } catch (IOException failure) {
                throw classify(failure);
            }
            deadline.check(phase);
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            deadline.check(phase);
            long skipped;
            try {
                skipped = super.skip(count);
            } catch (IOException failure) {
                throw classify(failure);
            }
            deadline.check(phase);
            return skipped;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } catch (IOException failure) {
                throw classify(failure);
            }
        }

        private IOException classify(IOException failure) {
            if (!clientSource || failure instanceof ClientBodyReadException) return failure;
            return new ClientBodyReadException(failure);
        }
    }

    @FunctionalInterface
    public interface InputStreamSupplier {
        InputStream open() throws IOException;
    }
}
