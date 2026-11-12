package com.ses.importcenter.metadata;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.DocumentService;
import com.ses.service.impl.StagedImportDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * raw CSV ingressのservice単体分岐。clockは注入値で進め、実時間のsleepは使わない。
 * stage fixtureは実stageと同じくcommit前はID未採番で、commitがinsertまで進んだ場合だけ{@link #markInserted}で採番する。
 */
class ImportCsvIngestionServiceTest {
    private static final ImportAuthorizedContext CONTEXT = new ImportAuthorizedContext("tenant-test", 31, 41);
    private static final ImportUploadSource SOURCE = new ImportUploadSource(51, "fixture-a", 3);
    private static final ImportAuthorizedContext OTHER_CONTEXT = new ImportAuthorizedContext("tenant-test", 31, 42);
    private static final byte[] CSV = "externalKey,companyName\nC-SYN-1,架空会社\n".getBytes(StandardCharsets.UTF_8);
    private static final String CSV_HASH = sha256(CSV);
    private static final Duration BUDGET = Duration.ofSeconds(120);
    private static final ImportUploadJobDetail UPLOADED = new ImportUploadJobDetail(101, "UPLOADED", "CUSTOMER",
            CSV_HASH.substring(0, 12), 1, "UTF8");

    @TempDir Path uploadRoot;

    private final AtomicLong clock = new AtomicLong(5_000_000_000L);
    private SimpleMeterRegistry meterRegistry;
    private ImportMetadataAuthorizationService authorization;
    private ImportMetadataService metadata;
    private DocumentService documents;
    private ImportCsvUploadCommitService commit;
    private Logger tempStoreLogger;
    private ListAppender<ILoggingEvent> tempStoreLogs;
    private Logger ingestionLogger;
    private ListAppender<ILoggingEvent> ingestionLogs;

    @BeforeEach
    void prepareCollaborators() {
        meterRegistry = new SimpleMeterRegistry();
        authorization = mock(ImportMetadataAuthorizationService.class);
        metadata = mock(ImportMetadataService.class);
        documents = mock(DocumentService.class);
        commit = mock(ImportCsvUploadCommitService.class);
        tempStoreLogger = (Logger) LoggerFactory.getLogger(ImportRawTempFileStore.class);
        tempStoreLogs = new ListAppender<>();
        tempStoreLogs.start();
        tempStoreLogger.addAppender(tempStoreLogs);
        ingestionLogger = (Logger) LoggerFactory.getLogger(ImportCsvIngestionService.class);
        ingestionLogs = new ListAppender<>();
        ingestionLogs.start();
        ingestionLogger.addAppender(ingestionLogs);
    }

    @AfterEach
    void detachLogCapture() {
        tempStoreLogger.detachAppender(tempStoreLogs);
        ingestionLogger.detachAppender(ingestionLogs);
    }

    // ---------------------------------------------------------------- 認可・admission（body未読で拒否）

    @Test
    void defaultDenyAdmissionRejectsBeforeOpeningRequestStream() {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        ImportCsvIngestionService service = service(new DefaultDenyImportAdmissionPort());
        AtomicBoolean opened = new AtomicBoolean();

        BusinessException failure = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> {
                    opened.set(true);
                    return new ByteArrayInputStream(CSV);
                }));

        assertEquals(403, failure.getCode());
        assertFalse(opened.get());
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        assertNoRawTempFiles();
    }

    @Test
    void authorizationDeniedRejectsBeforeResolvingSourceOrOpeningBody() {
        when(authorization.requireCurrentAuthorization()).thenThrow(BusinessException.of(403, "error.forbidden"));
        CountingAdmission admission = new CountingAdmission(fixedFixtureAdmission());
        ImportCsvIngestionService service = service(admission);
        AtomicBoolean opened = new AtomicBoolean();

        BusinessException failure = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> {
                    opened.set(true);
                    return new ByteArrayInputStream(CSV);
                }));

        assertEquals(403, failure.getCode());
        assertEquals("error.forbidden", failure.getMessageKey());
        assertFalse(opened.get());
        assertEquals(0, admission.beforeBodyCalls.get());
        verify(metadata, never()).resolveActiveUploadSource(any());
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        verify(commit, never()).commit(any(), any(), any(), anyInt());
        assertNoRawTempFiles();
    }

    @Test
    void uploadInsideOuterTransactionIsRejectedBeforeAuthorizationOrBody() {
        ImportCsvIngestionService service = service(fixedFixtureAdmission());
        AtomicBoolean opened = new AtomicBoolean();

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service.upload(
                    new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> {
                        opened.set(true);
                        return new ByteArrayInputStream(CSV);
                    }));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertFalse(opened.get());
        verify(authorization, never()).requireCurrentAuthorization();
        verify(commit, never()).commit(any(), any(), any(), anyInt());
        assertNoRawTempFiles();
    }

    @Test
    void admissionContextMismatchRejectsBeforeOpeningRequestStream() {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        ImportAdmissionPort mismatched = new ImportAdmissionPort() {
            @Override public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context,
                    ImportUploadSource source, ImportEntity entity, String schema, ImportEncoding encoding) {
                return new ImportAdmissionToken(context.tenantId(), context.actorUserId(), context.legalEntityId() + 1,
                        source.id(), source.version(), entity, schema, encoding, "fixture-key");
            }
            @Override public ImportAdmissionManifest admitContent(ImportAdmissionToken token, String hash) {
                throw new AssertionError("body must not be read");
            }
        };
        ImportCsvIngestionService service = service(mismatched);
        AtomicBoolean opened = new AtomicBoolean();

        assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> {
                    opened.set(true);
                    return new ByteArrayInputStream(CSV);
                }));

        assertFalse(opened.get());
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        assertNoRawTempFiles();
    }

    @Test
    void rawBodyHardLimitRejectsByteSixtyFourMiBPlusOneAsClientError() {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException tooLarge = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8),
                () -> new RepeatingInputStream(64L * 1024 * 1024 + 1)));

        // SafeErrorPolicyの許可codeに413がないため、500（SYSTEM）へ化けない400で返す。
        assertEquals(400, tooLarge.getCode());
        assertEquals("error.import.uploadTooLarge", tooLarge.getMessageKey());
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        assertNoRawTempFiles();
    }

    // ---------------------------------------------------------------- 正向

    @Test
    void approvedSyntheticCustomerUploadStagesExactBytesAndReturnsRedactedUploadedDetail() throws Exception {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        when(metadata.findOrReuseExistingUpload(eq(SOURCE), eq(ImportEncoding.UTF8), any())).thenReturn(null);
        ImportAdmissionToken token = new ImportAdmissionToken(CONTEXT.tenantId(), CONTEXT.actorUserId(),
                CONTEXT.legalEntityId(), SOURCE.id(), SOURCE.version(), ImportEntity.CUSTOMER,
                SesImportV1Schema.VERSION, ImportEncoding.UTF8, "fixture-key");
        ImportAdmissionPort fixture = new ImportAdmissionPort() {
            @Override public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context,
                    ImportUploadSource source, ImportEntity entity, String schema, ImportEncoding encoding) {
                assertEquals(CONTEXT, context);
                assertEquals(ImportEntity.CUSTOMER, entity);
                return token;
            }
            @Override public ImportAdmissionManifest admitContent(ImportAdmissionToken value, String sourceHash) {
                assertEquals(token, value);
                if (!CSV_HASH.equals(sourceHash)) throw BusinessException.of(403, "error.import.fixtureContentMismatch");
                return contentManifest("fixture-key");
            }
        };
        StagedImportDocument staged = stagedFixture();
        AtomicBoolean checkpointPassedInsideBudget = new AtomicBoolean();
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(CSV_HASH), any()))
                .thenAnswer(invocation -> {
                    Path file = invocation.getArgument(1);
                    assertEquals(CSV_HASH, sha256(Files.readAllBytes(file)));
                    invocation.getArgument(4, Runnable.class).run();
                    checkpointPassedInsideBudget.set(true);
                    return staged;
                });
        when(commit.commit(eq(token), eq(contentManifest("fixture-key")), eq(staged), eq(120)))
                .thenAnswer(insertAndReturn(staged, UPLOADED));
        ImportCsvIngestionService service = service(fixture);

        ImportUploadJobDetail result = service.upload(new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8),
                () -> new ByteArrayInputStream(CSV));

        assertEquals(UPLOADED, result);
        assertEquals("UPLOADED", result.state());
        assertEquals("CUSTOMER", result.entity());
        assertEquals(CSV_HASH.substring(0, 12), result.sourceHashPrefix());
        assertTrue(checkpointPassedInsideBudget.get());
        ArgumentCaptor<Path> stagedFile = ArgumentCaptor.forClass(Path.class);
        verify(documents).stageReceivedForImport(any(), stagedFile.capture(), eq((long) CSV.length), eq(CSV_HASH),
                any());
        assertEquals(tempDirectory(), stagedFile.getValue().getParent());
        assertTrue(stagedFile.getValue().getFileName().toString().startsWith(ImportRawTempFileStore.PREFIX));
        assertFalse(Files.exists(stagedFile.getValue()));
        verify(documents, never()).discardImportStage(staged);
        assertNoRawTempFiles();
        assertEquals(0, cleanupFailureCount());
    }

    // ---------------------------------------------------------------- 認可変更（body読取後）

    @Test
    void legalEntityContextChangeAfterStagePreventsCommitAndDiscardsOnlyThatStage() {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT, CONTEXT, CONTEXT, CONTEXT, OTHER_CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        when(metadata.findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH)).thenReturn(null);
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        assertThrows(BusinessException.class, () -> upload(service));

        verify(documents).discardImportStage(staged);
        verify(commit, never()).commit(any(), any(), any(), anyInt());
        assertNoRawTempFiles();
    }

    @Test
    void authorizationRevokedBeforeCommitDiscardsStageAndNeverCommits() {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT, CONTEXT, CONTEXT, CONTEXT)
                .thenThrow(BusinessException.of(403, "error.forbidden"));
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        when(metadata.findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH)).thenReturn(null);
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException denied = assertThrows(BusinessException.class, () -> upload(service));

        assertEquals(403, denied.getCode());
        verify(documents).discardImportStage(staged);
        verify(commit, never()).commit(any(), any(), any(), anyInt());
        assertNoRawTempFiles();
    }

    // ---------------------------------------------------------------- scanner error

    @Test
    void scannerRejectionDuringStageLeavesNoStageCommitOrTempFile() {
        stubAuthorizedSource();
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(CSV_HASH), any()))
                .thenThrow(BusinessException.of(400, "error.file.scanRejected"));
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException rejected = assertThrows(BusinessException.class, () -> upload(service));

        assertEquals(400, rejected.getCode());
        assertEquals("error.file.scanRejected", rejected.getMessageKey());
        verify(documents, never()).discardImportStage(any());
        verify(commit, never()).commit(any(), any(), any(), anyInt());
        assertNoRawTempFiles();
    }

    // ---------------------------------------------------------------- 一時I/Oとclient入力の区別

    @Test
    void tempFileCreationFailureIsServerErrorAndBodyIsNeverRead() throws Exception {
        stubAuthorizedSource();
        Path notADirectory = Files.writeString(uploadRoot.resolve("not-a-directory"), "x");
        ImportRawTempFileStore broken = new ImportRawTempFileStore(notADirectory.resolve("quarantine"),
                meterRegistry, Files::deleteIfExists);
        ImportCsvIngestionService service = service(fixedFixtureAdmission(), broken);
        AtomicBoolean opened = new AtomicBoolean();

        BusinessException failure = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> {
                    opened.set(true);
                    return new ByteArrayInputStream(CSV);
                }));

        assertEquals(500, failure.getCode());
        assertEquals("error.import.tempStorageFailed", failure.getMessageKey());
        assertFalse(opened.get());
        String logs = logText(ingestionLogs);
        assertTrue(logs.contains("raw CSV一時ファイルの入出力に失敗しました"));
        assertFalse(logs.contains(uploadRoot.toString()), "絶対pathをログへ出さない");
    }

    @Test
    void clientBodyReadAndOpenFailuresStayClientErrors() {
        stubAuthorizedSource();
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException readFailure = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> new InputStream() {
                    @Override public int read() throws IOException { throw new IOException("synthetic client abort"); }
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        throw new IOException("synthetic client abort");
                    }
                }));
        BusinessException openFailure = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> {
                    throw new IOException("synthetic request stream unavailable");
                }));

        assertEquals(400, readFailure.getCode());
        assertEquals("error.import.uploadReadFailed", readFailure.getMessageKey());
        assertEquals(400, openFailure.getCode());
        assertEquals("error.import.uploadReadFailed", openFailure.getMessageKey());
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        assertNoRawTempFiles();
    }

    @Test
    void malformedCsvIsClientErrorNotServerError() {
        stubAuthorizedSource();
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException failure = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8),
                () -> new ByteArrayInputStream(new byte[]{'P', 'K', 3, 4})));

        assertEquals(400, failure.getCode());
        assertEquals("error.import.uploadReadFailed", failure.getMessageKey());
        assertNoRawTempFiles();
    }

    // ---------------------------------------------------------------- stage補償（元の失敗を上書きしない）

    @Test
    void stageDiscardFailureIsSuppressedUnderTheOriginalFailure() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        BusinessException commitFailure = BusinessException.of(500, "error.test.commitFailure");
        BusinessException cleanupFailure = BusinessException.of(500, "error.test.cleanupFailure");
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenThrow(commitFailure);
        doThrow(cleanupFailure).when(documents).discardImportStage(staged);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException failure = assertThrows(BusinessException.class, () -> upload(service));

        assertSame(commitFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(cleanupFailure, failure.getSuppressed()[0]);
        verify(documents).discardImportStage(staged);
        assertNoRawTempFiles();
    }

    @Test
    void stageDiscardFailureOnForeignWinnerSuccessPathIsReportedNotHidden() {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        ImportUploadJobDetail winner = new ImportUploadJobDetail(202, "UPLOADED", "CUSTOMER",
                CSV_HASH.substring(0, 12), 1, "UTF8");
        when(metadata.findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH)).thenReturn(null, winner);
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        when(commit.commit(any(), any(), eq(staged), anyInt()))
                .thenThrow(BusinessException.of(409, "error.import.jobIdentityConflict"));
        when(metadata.isUploadJobForStage(202L, SOURCE, CSV_HASH, staged, CONTEXT)).thenReturn(false);
        doThrow(BusinessException.of(500, "error.test.cleanupFailure")).when(documents).discardImportStage(staged);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException failure = assertThrows(BusinessException.class, () -> upload(service));

        assertEquals("error.test.cleanupFailure", failure.getMessageKey());
        assertNoRawTempFiles();
    }

    // ---------------------------------------------------------------- commit失敗の分類（未commit確定）

    @Test
    void commitBeginFailureIsDefinitelyNotCommittedAndDiscardsStageWithoutReadback() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        CannotCreateTransactionException poolExhausted =
                new CannotCreateTransactionException("synthetic connection pool exhaustion");
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenThrow(poolExhausted);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        CannotCreateTransactionException failure = assertThrows(CannotCreateTransactionException.class,
                () -> upload(service));

        assertSame(poolExhausted, failure);
        verify(documents).discardImportStage(staged);
        verify(metadata, never()).findCommittedUploadForStage(any(), any(), any(), any());
        assertFalse(logText(ingestionLogs).contains("保全"), "破棄したstageを保全とログに残さない");
        assertNoRawTempFiles();
    }

    @Test
    void lockWaitFailureInsideCommitIsDefinitelyNotCommittedAndDiscardsStage() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        CannotAcquireLockException lockWait = new CannotAcquireLockException("synthetic lock wait timeout");
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenAnswer(invocation -> {
            markInserted(staged);
            throw lockWait;
        });
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        CannotAcquireLockException failure = assertThrows(CannotAcquireLockException.class, () -> upload(service));

        assertSame(lockWait, failure);
        verify(documents).discardImportStage(staged);
        verify(metadata, never()).findCommittedUploadForStage(any(), any(), any(), any());
        assertNoRawTempFiles();
    }

    @Test
    void writeGateQueryTimeoutInsideCommitReportsDeadlineAndDiscardsStage() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        QueryTimeoutException writeGateTimeout = new QueryTimeoutException("synthetic write gate timeout");
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenThrow(writeGateTimeout);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException expired = assertThrows(BusinessException.class, () -> upload(service));

        assertDeadlineExceeded(expired);
        assertSame(writeGateTimeout, expired.getSuppressed()[0]);
        verify(documents).discardImportStage(staged);
        verify(metadata, never()).findCommittedUploadForStage(any(), any(), any(), any());
        String logs = logText(ingestionLogs);
        assertTrue(logs.contains("未commitで確定"));
        assertFalse(logs.contains("保全"), "破棄したstageを保全とログに残さない");
        assertNoRawTempFiles();
    }

    @Test
    void outcomeUnknownWithoutInsertedRowsIsTreatedAsNotCommitted() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        ImportCommitOutcomeUnknownException unknown =
                new ImportCommitOutcomeUnknownException(new TransactionSystemException("synthetic"));
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenThrow(unknown);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        ImportCommitOutcomeUnknownException failure = assertThrows(ImportCommitOutcomeUnknownException.class,
                () -> upload(service));

        assertSame(unknown, failure);
        verify(documents).discardImportStage(staged);
        verify(metadata, never()).findCommittedUploadForStage(any(), any(), any(), any());
        assertNoRawTempFiles();
    }

    // ---------------------------------------------------------------- COMMIT結果不明（ACK-loss）とreadback

    @Test
    void uncertainCommitReadbackRetainsThePossiblyCommittedStage() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenAnswer(
                insertAndThrow(staged, unknownCommit("synthetic commit acknowledgement loss")));
        when(metadata.findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged)).thenReturn(null);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        assertThrows(ImportCommitOutcomeUnknownException.class, () -> upload(service));

        verify(documents, never()).discardImportStage(staged);
        assertTrue(logText(ingestionLogs).contains("照合用に保全"));
        assertNoRawTempFiles();
    }

    @Test
    void commitAckLossWithOwnCommittedRowReturnsReadbackWithoutDiscard() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenAnswer(
                insertAndThrow(staged, unknownCommit("synthetic commit acknowledgement loss")));
        when(metadata.findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged)).thenReturn(UPLOADED);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        ImportUploadJobDetail result = upload(service);

        assertEquals(UPLOADED, result);
        verify(metadata).findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged);
        verify(documents, never()).discardImportStage(staged);
        assertNoRawTempFiles();
    }

    @Test
    void commitAckLossWithReadbackFailureRetainsStageAndRethrowsOriginalFailure() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        ImportCommitOutcomeUnknownException original = unknownCommit("synthetic commit acknowledgement loss");
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenAnswer(insertAndThrow(staged, original));
        when(metadata.findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged))
                .thenThrow(new IllegalArgumentException("synthetic readback outage"));
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        ImportCommitOutcomeUnknownException failure = assertThrows(ImportCommitOutcomeUnknownException.class,
                () -> upload(service));

        assertSame(original, failure);
        verify(metadata, times(1)).findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH);
        verify(documents, never()).discardImportStage(staged);
        assertNoRawTempFiles();
    }

    @Test
    void outcomeUnknownWithForeignWinnerReturnsWinnerAndDiscardsOwnStage() {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        ImportUploadJobDetail winner = new ImportUploadJobDetail(202, "UPLOADED", "CUSTOMER",
                CSV_HASH.substring(0, 12), 1, "UTF8");
        when(metadata.findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH)).thenReturn(null, winner);
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenAnswer(
                insertAndThrow(staged, unknownCommit("synthetic commit acknowledgement loss")));
        when(metadata.findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged)).thenReturn(null);
        when(metadata.isUploadJobForStage(202L, SOURCE, CSV_HASH, staged, CONTEXT)).thenReturn(false);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        ImportUploadJobDetail result = upload(service);

        assertEquals(winner, result);
        verify(documents).discardImportStage(staged);
        assertNoRawTempFiles();
    }

    // ---------------------------------------------------------------- 一意性競合（409、rollback済み）

    @Test
    void uniqueConflictWithForeignWinnerReturnsWinnerAndDiscardsOnlyOwnStage() {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        ImportUploadJobDetail winner = new ImportUploadJobDetail(202, "UPLOADED", "CUSTOMER",
                CSV_HASH.substring(0, 12), 1, "UTF8");
        when(metadata.findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH)).thenReturn(null, winner);
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        when(commit.commit(any(), any(), eq(staged), anyInt()))
                .thenThrow(BusinessException.of(409, "error.import.jobIdentityConflict"));
        when(metadata.findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged)).thenReturn(null);
        when(metadata.isUploadJobForStage(202L, SOURCE, CSV_HASH, staged, CONTEXT)).thenReturn(false);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        ImportUploadJobDetail result = upload(service);

        assertEquals(winner, result);
        verify(documents).discardImportStage(staged);
        assertNoRawTempFiles();
    }

    @Test
    void uniqueConflictWhoseWinnerIsOwnStageKeepsStage() {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        when(metadata.findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH)).thenReturn(null, UPLOADED);
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        when(commit.commit(any(), any(), eq(staged), anyInt()))
                .thenThrow(BusinessException.of(409, "error.import.jobIdentityConflict"));
        when(metadata.findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged)).thenReturn(null);
        when(metadata.isUploadJobForStage(101L, SOURCE, CSV_HASH, staged, CONTEXT)).thenReturn(true);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        ImportUploadJobDetail result = upload(service);

        assertEquals(UPLOADED, result);
        verify(documents, never()).discardImportStage(staged);
        assertNoRawTempFiles();
    }

    @Test
    void uniqueConflictReadbackFailureStillDiscardsTheRolledBackStage() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        BusinessException conflict = BusinessException.of(409, "error.import.jobIdentityConflict");
        IllegalArgumentException readbackOutage = new IllegalArgumentException("synthetic readback outage");
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenThrow(conflict);
        when(metadata.findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged))
                .thenThrow(readbackOutage);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException failure = assertThrows(BusinessException.class, () -> upload(service));

        assertSame(conflict, failure);
        assertSame(readbackOutage, failure.getSuppressed()[0]);
        verify(documents).discardImportStage(staged);
        assertFalse(logText(ingestionLogs).contains("保全"));
        assertNoRawTempFiles();
    }

    @Test
    void uniqueConflictWithoutVisibleWinnerRethrowsConflictAndDiscardsStage() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        BusinessException conflict = BusinessException.of(409, "error.import.jobIdentityConflict");
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenThrow(conflict);
        when(metadata.findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged)).thenReturn(null);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException failure = assertThrows(BusinessException.class, () -> upload(service));

        assertSame(conflict, failure);
        assertEquals(0, failure.getSuppressed().length);
        verify(documents).discardImportStage(staged);
        assertNoRawTempFiles();
    }

    // ---------------------------------------------------------------- 120秒総期限

    @Test
    void bodyReadPastDeadlineStopsBeforeContentAdmissionAndDeletesTemp() {
        stubAuthorizedSource();
        CountingAdmission admission = new CountingAdmission(fixedFixtureAdmission());
        ImportCsvIngestionService service = service(admission);

        BusinessException expired = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8),
                () -> new ClockAdvancingInputStream(CSV, () -> advance(BUDGET.plusSeconds(1)), null)));

        assertDeadlineExceeded(expired);
        assertEquals(1, admission.beforeBodyCalls.get());
        assertEquals(0, admission.contentCalls.get());
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        verify(commit, never()).commit(any(), any(), any(), anyInt());
        assertNoRawTempFiles();
    }

    @Test
    void deadlineReachedBeforeParseStopsBeforeContentAdmission() {
        stubAuthorizedSource();
        CountingAdmission admission = new CountingAdmission(fixedFixtureAdmission());
        ImportCsvIngestionService service = service(admission);

        BusinessException expired = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8),
                () -> new ClockAdvancingInputStream(CSV, null, () -> advance(BUDGET.plusSeconds(1)))));

        assertDeadlineExceeded(expired);
        assertEquals(0, admission.contentCalls.get());
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        assertNoRawTempFiles();
    }

    @Test
    void contentAdmissionReturningAfterDeadlineStopsBeforeReuseLookup() {
        stubAuthorizedSource();
        ImportAdmissionPort delegate = fixedFixtureAdmission();
        ImportAdmissionPort slowContent = new ImportAdmissionPort() {
            @Override public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context,
                    ImportUploadSource source, ImportEntity entity, String schema, ImportEncoding encoding) {
                return delegate.admitBeforeBody(context, source, entity, schema, encoding);
            }
            @Override public ImportAdmissionManifest admitContent(ImportAdmissionToken token, String sourceSha256) {
                ImportAdmissionManifest manifest = delegate.admitContent(token, sourceSha256);
                advance(BUDGET.plusSeconds(1));
                return manifest;
            }
        };
        ImportCsvIngestionService service = service(slowContent);

        BusinessException expired = assertThrows(BusinessException.class, () -> upload(service));

        assertDeadlineExceeded(expired);
        verify(metadata, never()).findOrReuseExistingUpload(any(), any(), any());
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        assertNoRawTempFiles();
    }

    @Test
    void reuseLookupReturningAfterDeadlineIsRejectedEvenWhenAJobExists() {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        when(metadata.findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH)).thenAnswer(invocation -> {
            advance(BUDGET.plusSeconds(1));
            return UPLOADED;
        });
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException expired = assertThrows(BusinessException.class, () -> upload(service));

        assertDeadlineExceeded(expired);
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        assertNoRawTempFiles();
    }

    @Test
    void stageReturningAfterDeadlineIsDiscardedAndNeverCommitted() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(CSV_HASH), any()))
                .thenAnswer(invocation -> {
                    advance(BUDGET.plusSeconds(1));
                    return staged;
                });
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException expired = assertThrows(BusinessException.class, () -> upload(service));

        assertDeadlineExceeded(expired);
        verify(documents).discardImportStage(staged);
        verify(commit, never()).commit(any(), any(), any(), anyInt());
        assertNoRawTempFiles();
    }

    @Test
    void stageCheckpointAbortsScannerOrStoragePhaseAtDeadline() {
        stubAuthorizedSource();
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(CSV_HASH), any()))
                .thenAnswer(invocation -> {
                    advance(BUDGET.plusSeconds(1));
                    invocation.getArgument(4, Runnable.class).run();
                    throw new AssertionError("checkpointは期限超過で例外を投げなければなりません");
                });
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException expired = assertThrows(BusinessException.class, () -> upload(service));

        assertDeadlineExceeded(expired);
        verify(documents, never()).discardImportStage(any());
        verify(commit, never()).commit(any(), any(), any(), anyInt());
        assertNoRawTempFiles();
    }

    @Test
    void lessThanOneSecondLeftRejectsBeforeStartingCommitTransaction() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(CSV_HASH), any()))
                .thenAnswer(invocation -> {
                    advance(BUDGET.minusMillis(500));
                    return staged;
                });
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException expired = assertThrows(BusinessException.class, () -> upload(service));

        assertDeadlineExceeded(expired);
        verify(commit, never()).commit(any(), any(), any(), anyInt());
        verify(documents).discardImportStage(staged);
        assertNoRawTempFiles();
    }

    @Test
    void commitReceivesOnlyTheRemainingBudgetAsTransactionTimeout() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(CSV_HASH), any()))
                .thenAnswer(invocation -> {
                    advance(Duration.ofMillis(20_500));
                    return staged;
                });
        when(commit.commit(any(), any(), eq(staged), eq(99))).thenAnswer(insertAndReturn(staged, UPLOADED));
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        ImportUploadJobDetail result = upload(service);

        assertEquals(UPLOADED, result);
        verify(commit).commit(any(), any(), eq(staged), eq(99));
        verify(documents, never()).discardImportStage(staged);
        assertNoRawTempFiles();
    }

    @Test
    void commitPhaseTimeoutWithoutVisibleRowReportsDeadlineAndRetainsStage() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenAnswer(insertAndThrow(staged,
                new ImportCommitOutcomeUnknownException(new QueryTimeoutException("synthetic commit timeout"))));
        when(metadata.findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged)).thenReturn(null);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        BusinessException expired = assertThrows(BusinessException.class, () -> upload(service));

        assertDeadlineExceeded(expired);
        verify(metadata).findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged);
        verify(documents, never()).discardImportStage(staged);
        assertNoRawTempFiles();
    }

    @Test
    void commitPhaseTimeoutWhoseReadbackShowsOwnRowStillReturnsTheCommittedJob() {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenAnswer(invocation -> {
            markInserted(staged);
            advance(BUDGET.plusSeconds(1));
            throw unknownCommit("synthetic timeout after durable commit");
        });
        when(metadata.findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged)).thenReturn(UPLOADED);
        ImportCsvIngestionService service = service(fixedFixtureAdmission());

        ImportUploadJobDetail result = upload(service);

        assertEquals(UPLOADED, result);
        verify(documents, never()).discardImportStage(staged);
        assertNoRawTempFiles();
    }

    // ---------------------------------------------------------------- raw temp削除失敗

    @Test
    void tempCleanupFailureAfterSuccessIsObservableRecoverableAndKeepsResult() throws Exception {
        stubAuthorizedSource();
        StagedImportDocument staged = stagedFixture();
        stubStage(staged);
        when(commit.commit(any(), any(), eq(staged), anyInt())).thenAnswer(insertAndReturn(staged, UPLOADED));
        ImportCsvIngestionService service = service(fixedFixtureAdmission(), failingDeleteStore());

        ImportUploadJobDetail result = upload(service);

        assertEquals(UPLOADED, result);
        assertEquals(1, cleanupFailureCount());
        List<Path> leftovers = rawTempFiles();
        assertEquals(1, leftovers.size());
        assertEquals(0, Files.size(leftovers.get(0)), "削除できないraw CSVは内容を切り詰めて残す");
        String logs = logText(tempStoreLogs);
        assertTrue(logs.contains("raw CSV一時ファイルの削除に失敗しました"));
        assertTrue(logs.contains(leftovers.get(0).getFileName().toString()));
        assertTrue(logs.contains("AccessDeniedException"));
        assertFalse(logs.contains(uploadRoot.toString()), "絶対pathをログへ出さない");
        assertFalse(logs.contains("架空会社"), "raw CSV内容をログへ出さない");
        verify(documents, never()).discardImportStage(staged);
    }

    @Test
    void tempCleanupFailureDoesNotMaskTheOriginalFailure() {
        stubAuthorizedSource();
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(CSV_HASH), any()))
                .thenThrow(BusinessException.of(400, "error.file.scanRejected"));
        ImportCsvIngestionService service = service(fixedFixtureAdmission(), failingDeleteStore());

        BusinessException failure = assertThrows(BusinessException.class, () -> upload(service));

        assertEquals("error.file.scanRejected", failure.getMessageKey());
        assertEquals(1, cleanupFailureCount());
        assertEquals(1, rawTempFiles().size());
    }

    // ---------------------------------------------------------------- helpers

    private ImportCsvIngestionService service(ImportAdmissionPort admission) {
        return service(admission, new ImportRawTempFileStore(tempDirectory(), meterRegistry, Files::deleteIfExists));
    }

    private ImportCsvIngestionService service(ImportAdmissionPort admission, ImportRawTempFileStore tempFiles) {
        return new ImportCsvIngestionService(authorization, metadata, admission, documents, commit, tempFiles,
                clock::get, BUDGET);
    }

    private ImportRawTempFileStore failingDeleteStore() {
        return new ImportRawTempFileStore(tempDirectory(), meterRegistry, file -> {
            throw new AccessDeniedException(file.toString());
        });
    }

    private static ImportUploadJobDetail upload(ImportCsvIngestionService service) {
        return service.upload(new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8),
                () -> new ByteArrayInputStream(CSV));
    }

    private void stubAuthorizedSource() {
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        when(metadata.findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH)).thenReturn(null);
    }

    private void stubStage(StagedImportDocument staged) {
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(CSV_HASH), any()))
                .thenReturn(staged);
    }

    /** 実commitImportDocumentと同じく、metadata insert時にdocument/versionのIDを採番する。 */
    private static void markInserted(StagedImportDocument staged) {
        staged.document().setId(81L);
        staged.version().setId(91L);
        staged.version().setDocumentId(81L);
    }

    private static Answer<ImportUploadJobDetail> insertAndReturn(StagedImportDocument staged,
                                                                 ImportUploadJobDetail result) {
        return invocation -> {
            markInserted(staged);
            return result;
        };
    }

    private static Answer<ImportUploadJobDetail> insertAndThrow(StagedImportDocument staged, RuntimeException failure) {
        return invocation -> {
            markInserted(staged);
            throw failure;
        };
    }

    private static ImportCommitOutcomeUnknownException unknownCommit(String message) {
        return new ImportCommitOutcomeUnknownException(new TransactionSystemException(message));
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }

    private Path tempDirectory() {
        return uploadRoot.resolve("quarantine").toAbsolutePath().normalize();
    }

    private List<Path> rawTempFiles() {
        if (!Files.isDirectory(tempDirectory())) return List.of();
        try (Stream<Path> files = Files.list(tempDirectory())) {
            return files.filter(file -> file.getFileName().toString().startsWith(ImportRawTempFileStore.PREFIX))
                    .toList();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void assertNoRawTempFiles() {
        assertEquals(List.of(), rawTempFiles(), "raw CSV一時ファイルを残してはいけません");
    }

    private double cleanupFailureCount() {
        var counter = meterRegistry.find(ImportRawTempFileStore.CLEANUP_FAILURE_METRIC).counter();
        return counter == null ? 0 : counter.count();
    }

    private static String logText(ListAppender<ILoggingEvent> appender) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) text.append(event.getFormattedMessage()).append('\n');
        return text.toString();
    }

    private static void assertDeadlineExceeded(BusinessException failure) {
        assertEquals(503, failure.getCode());
        assertEquals(ImportIngressDeadline.EXCEEDED_MESSAGE_KEY, failure.getMessageKey());
    }

    private static ImportAdmissionManifest contentManifest(String fixtureKey) {
        return new ImportAdmissionManifest(fixtureKey, CONTEXT.tenantId(), CONTEXT.legalEntityId(),
                SOURCE.id(), SOURCE.version(), ImportEntity.CUSTOMER, SesImportV1Schema.VERSION,
                ImportEncoding.UTF8, CSV_HASH, null, null, null, null, "A1A_CONTENT");
    }

    private static ImportAdmissionPort fixedFixtureAdmission() {
        ImportAdmissionToken token = new ImportAdmissionToken(CONTEXT.tenantId(), CONTEXT.actorUserId(),
                CONTEXT.legalEntityId(), SOURCE.id(), SOURCE.version(), ImportEntity.CUSTOMER,
                SesImportV1Schema.VERSION, ImportEncoding.UTF8, "unit-fixed-fixture");
        return new ImportAdmissionPort() {
            @Override public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context,
                    ImportUploadSource source, ImportEntity entity, String schema, ImportEncoding encoding) {
                return context.equals(CONTEXT) && source.equals(SOURCE) && entity == ImportEntity.CUSTOMER
                        && SesImportV1Schema.VERSION.equals(schema) && encoding == ImportEncoding.UTF8 ? token : null;
            }
            @Override public ImportAdmissionManifest admitContent(ImportAdmissionToken candidate, String hash) {
                if (!token.equals(candidate) || !CSV_HASH.equals(hash)) {
                    throw BusinessException.of(403, "error.import.fixtureContentMismatch");
                }
                return contentManifest(token.fixtureKey());
            }
        };
    }

    /** 実stageと同じく、commit前のdocument/versionはID未採番（commitImportDocumentがnullを要求する）。 */
    private static StagedImportDocument stagedFixture() {
        Document document = new Document();
        document.setTenantId(CONTEXT.tenantId());
        document.setLegalEntityId(Long.toString(CONTEXT.legalEntityId()));
        document.setDocumentType("IMPORT");
        DocumentVersion version = new DocumentVersion();
        version.setTenantId(CONTEXT.tenantId());
        version.setVersionNo(1);
        version.setStorageKey("unit-fixed-fixture/object");
        version.setSizeBytes((long) CSV.length);
        version.setSha256(CSV_HASH);
        version.setScanStatus("CLEAN");
        return new StagedImportDocument(document, version, version.getStorageKey(), "unit-fixed-operation");
    }

    private static final class CountingAdmission implements ImportAdmissionPort {
        private final ImportAdmissionPort delegate;
        private final AtomicInteger beforeBodyCalls = new AtomicInteger();
        private final AtomicInteger contentCalls = new AtomicInteger();

        private CountingAdmission(ImportAdmissionPort delegate) { this.delegate = delegate; }

        @Override public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context,
                ImportUploadSource source, ImportEntity entity, String schema, ImportEncoding encoding) {
            beforeBodyCalls.incrementAndGet();
            return delegate.admitBeforeBody(context, source, entity, schema, encoding);
        }

        @Override public ImportAdmissionManifest admitContent(ImportAdmissionToken token, String sourceSha256) {
            contentCalls.incrementAndGet();
            return delegate.admitContent(token, sourceSha256);
        }
    }

    /** 最初のread、またはclose時にclockを進めて、slow bodyや読取直後の期限到達を再現する。 */
    private static final class ClockAdvancingInputStream extends InputStream {
        private final ByteArrayInputStream bytes;
        private Runnable onFirstRead;
        private Runnable onClose;

        private ClockAdvancingInputStream(byte[] content, Runnable onFirstRead, Runnable onClose) {
            this.bytes = new ByteArrayInputStream(content);
            this.onFirstRead = onFirstRead;
            this.onClose = onClose;
        }

        @Override public int read() {
            fireFirstRead();
            return bytes.read();
        }

        @Override public int read(byte[] buffer, int offset, int length) {
            fireFirstRead();
            return bytes.read(buffer, offset, length);
        }

        @Override public void close() {
            if (onClose != null) {
                Runnable action = onClose;
                onClose = null;
                action.run();
            }
        }

        private void fireFirstRead() {
            if (onFirstRead != null) {
                Runnable action = onFirstRead;
                onFirstRead = null;
                action.run();
            }
        }
    }

    private static final class RepeatingInputStream extends InputStream {
        private long remaining;

        private RepeatingInputStream(long size) { remaining = size; }

        @Override public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return 'x';
        }

        @Override public int read(byte[] bytes, int offset, int length) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(remaining, length);
            java.util.Arrays.fill(bytes, offset, offset + count, (byte) 'x');
            remaining -= count;
            return count;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
