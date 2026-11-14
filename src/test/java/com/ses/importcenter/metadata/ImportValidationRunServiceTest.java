package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A1a検証編成（mapping束縛→start/takeover→append→seal→recount→publish）のH2上の分岐。 */
class ImportValidationRunServiceTest {
    private static final String CSV = """
            externalKey,companyName,contactPerson,contactEmail,contactPhone,address
            C-1,株式会社アルファ,山田太郎,taro@example.co.jp,03-1234-5678,東京都千代田区1-1
            C-1,株式会社ベータ,佐藤花子,hanako@example.co.jp,03-9999-0000,大阪府大阪市2-2
            C-3,,鈴木一郎,not-an-email,090-1111-2222,福岡県福岡市3-3
            """;

    private ImportA1aH2Fixture fixture;
    private long sourceId;
    private long mappingId;

    @BeforeEach
    void prepare() {
        fixture = new ImportA1aH2Fixture();
        sourceId = fixture.createSource("legacy-crm");
        mappingId = fixture.createMapping(sourceId, ImportA1aH2Fixture.CUSTOMER_MAPPING);
    }

    @Test
    void uploadedJobBindsTheMappingAndPublishesAValidatedRun() {
        long jobId = fixture.uploadedJob(sourceId, CSV, ImportA1aH2Fixture.LEGAL_ENTITY);

        ImportValidationRunSummary summary = fixture.runService(System::nanoTime).validate(jobId, mappingId);

        assertEquals("VALIDATED", summary.state());
        assertEquals(3, summary.sourceRows());
        assertEquals(1, summary.acceptedRows());
        assertEquals(2, summary.rejectedRows());
        assertEquals(0, summary.emptyRows());
        assertEquals(1, summary.runNo());
        assertEquals("VALIDATED", fixture.jobState(jobId));
        assertEquals("COMPLETED", fixture.jdbc.queryForObject("SELECT state FROM t_import_validation_run "
                + "WHERE tenant_id=? AND id=?", String.class, ImportA1aH2Fixture.TENANT, summary.runId()));
        assertEquals(1, fixture.jdbc.queryForObject("SELECT published_slot FROM t_import_validation_run "
                + "WHERE tenant_id=? AND id=?", Integer.class, ImportA1aH2Fixture.TENANT, summary.runId()));
        assertEquals(3, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row "
                + "WHERE tenant_id=? AND validation_run_id=?", Integer.class, ImportA1aH2Fixture.TENANT,
                summary.runId()));
        assertNull(fixture.jdbc.queryForObject("SELECT lease_owner FROM t_import_job WHERE tenant_id=? AND id=?",
                String.class, ImportA1aH2Fixture.TENANT, jobId), "publish後はleaseを残さない");
        assertEquals(mappingId, fixture.jdbc.queryForObject("SELECT mapping_id FROM t_import_job "
                + "WHERE tenant_id=? AND id=?", Long.class, ImportA1aH2Fixture.TENANT, jobId));
    }

    @Test
    void uploadedJobWithoutMappingIsRejectedWithoutStateChange() {
        long jobId = fixture.uploadedJob(sourceId, CSV, ImportA1aH2Fixture.LEGAL_ENTITY);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> fixture.runService(System::nanoTime).validate(jobId, null));

        assertEquals(400, failure.getCode());
        assertEquals("error.import.mappingRequired", failure.getMessageKey());
        assertEquals("UPLOADED", fixture.jobState(jobId));
        assertEquals(0, fixture.runCount(jobId));
    }

    @Test
    void validatedJobReturnsTheCurrentRunWithoutRevalidating() {
        long jobId = fixture.uploadedJob(sourceId, CSV, ImportA1aH2Fixture.LEGAL_ENTITY);
        ImportValidationRunService service = fixture.runService(System::nanoTime);
        ImportValidationRunSummary first = service.validate(jobId, mappingId);
        long opensAfterFirst = fixture.sourceOpens.get();

        ImportValidationRunSummary second = service.validate(jobId, null);

        assertEquals(first, second);
        assertEquals(1, fixture.runCount(jobId));
        assertEquals(opensAfterFirst, fixture.sourceOpens.get(), "再検証でsourceを読まない");
    }

    @Test
    void jobOfAnotherLegalEntityIsReportedAsMissingAndLeftUntouched() {
        long jobId = fixture.uploadedJob(sourceId, CSV, 99);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> fixture.runService(System::nanoTime).validate(jobId, mappingId));

        assertEquals(404, failure.getCode());
        assertEquals("error.import.jobNotFound", failure.getMessageKey());
        assertEquals("UPLOADED", fixture.jobState(jobId));
        assertNull(fixture.jdbc.queryForObject("SELECT mapping_id FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, ImportA1aH2Fixture.TENANT, jobId));
        BusinessException missing = assertThrows(BusinessException.class,
                () -> fixture.runService(System::nanoTime).validate(987_654L, mappingId));
        assertEquals(failure.getCode(), missing.getCode());
        assertEquals(failure.getMessageKey(), missing.getMessageKey());
    }

    @Test
    void differentMappingForAMappedJobIsAConflict() {
        long jobId = fixture.uploadedJob(sourceId, CSV, ImportA1aH2Fixture.LEGAL_ENTITY);
        fixture.metadata.bindMapping(jobId, 1, mappingId);
        long other = fixture.createMapping(sourceId, ImportA1aH2Fixture.mapping("externalKey", "companyName"));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> fixture.runService(System::nanoTime).validate(jobId, other));

        assertEquals(409, failure.getCode());
        assertEquals("error.import.mappingSnapshotMismatch", failure.getMessageKey());
        assertEquals("MAPPED", fixture.jobState(jobId));
    }

    @Test
    void activeLeaseOfAnotherWorkerIsReportedAsInProgressAndNotAborted() {
        long jobId = fixture.uploadedJob(sourceId, CSV, ImportA1aH2Fixture.LEGAL_ENTITY);
        fixture.metadata.bindMapping(jobId, 1, mappingId);
        ImportValidationLifecycleService.RunLease other = fixture.lifecycle.start(fixture.lifecycle.beginStart(jobId));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> fixture.runService(System::nanoTime).validate(jobId, null));

        assertEquals(409, failure.getCode());
        assertEquals("error.import.validationInProgress", failure.getMessageKey());
        assertEquals("VALIDATING", fixture.jobState(jobId));
        assertEquals("BUILDING", fixture.jdbc.queryForObject("SELECT state FROM t_import_validation_run "
                + "WHERE tenant_id=? AND id=?", String.class, ImportA1aH2Fixture.TENANT, other.runId()));
    }

    @Test
    void expiredLeaseIsTakenOverWithANewGenerationAndCompleted() {
        long jobId = fixture.uploadedJob(sourceId, CSV, ImportA1aH2Fixture.LEGAL_ENTITY);
        fixture.metadata.bindMapping(jobId, 1, mappingId);
        ImportValidationLifecycleService.RunLease abandoned =
                fixture.lifecycle.start(fixture.lifecycle.beginStart(jobId));
        fixture.jdbc.update("UPDATE t_import_job SET lease_expires_at=? WHERE tenant_id=? AND id=?",
                Timestamp.from(Instant.now().minusSeconds(60)), ImportA1aH2Fixture.TENANT, jobId);

        ImportValidationRunSummary summary = fixture.runService(System::nanoTime).validate(jobId, null);

        assertEquals("VALIDATED", summary.state());
        assertEquals(abandoned.runId(), summary.runId(), "放棄されたrunを引き継いで完了する");
        assertEquals(abandoned.generation() + 1, fixture.jdbc.queryForObject(
                "SELECT last_resumed_lease_generation FROM t_import_validation_run WHERE tenant_id=? AND id=?",
                Long.class, ImportA1aH2Fixture.TENANT, summary.runId()));
        assertThrows(BusinessException.class, () -> fixture.lifecycle.renew(abandoned), "旧世代のleaseはfenceされる");
    }

    @Test
    void deadlineExceededDuringTheScanAbortsTheOwnRunAndReturns503() {
        long jobId = fixture.uploadedJob(sourceId, CSV, ImportA1aH2Fixture.LEGAL_ENTITY);
        fixture.metadata.bindMapping(jobId, 1, mappingId);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> fixture.runService(ImportA1aH2Fixture.clockExpiringAfter(2)).validate(jobId, null));

        assertEquals(503, failure.getCode());
        assertEquals("error.import.processingDeadlineExceeded", failure.getMessageKey());
        assertEquals("MAPPED", fixture.jobState(jobId), "自分のleaseでabortしてMAPPEDへ戻す");
        assertEquals("ABORTED", fixture.jdbc.queryForObject("SELECT state FROM t_import_validation_run "
                + "WHERE tenant_id=? AND job_id=?", String.class, ImportA1aH2Fixture.TENANT, jobId));
        assertNull(fixture.jdbc.queryForObject("SELECT lease_owner FROM t_import_job WHERE tenant_id=? AND id=?",
                String.class, ImportA1aH2Fixture.TENANT, jobId));

        ImportValidationRunSummary retried = fixture.runService(System::nanoTime).validate(jobId, null);
        assertEquals("VALIDATED", retried.state());
        assertEquals(2, retried.runNo(), "中断後の再実行は新しいrunで完了する");
    }

    @Test
    void deadlineAlreadyExceededBeforeStartLeavesNoRun() {
        long jobId = fixture.uploadedJob(sourceId, CSV, ImportA1aH2Fixture.LEGAL_ENTITY);
        fixture.metadata.bindMapping(jobId, 1, mappingId);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> fixture.runService(ImportA1aH2Fixture.clockExpiringAfter(1)).validate(jobId, null));

        assertEquals(503, failure.getCode());
        assertEquals(0, fixture.runCount(jobId));
        assertEquals("MAPPED", fixture.jobState(jobId));
    }

    @Test
    void invalidMappingIdIsRejectedBeforeAuthorizationWork() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> fixture.runService(System::nanoTime).validate(1L, 0L));
        assertEquals(400, failure.getCode());
        assertNotNull(failure.getMessageKey());
    }
}
