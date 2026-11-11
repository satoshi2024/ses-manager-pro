package com.ses.importcenter.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.ValidationCounterAccumulator;
import com.ses.service.DocumentService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.datasource.ConnectionHolder;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F1b-3B: Seal CAS および ACK loss 冪等回復の Fast feedback 単体・状態遷移テスト。
 */
class ImportValidationSealServiceTest {
    private static final String TENANT = "seal-h2";
    private final ImportAuthorizedContext context = new ImportAuthorizedContext(TENANT, 41, 77);

    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private ImportValidationLifecycleService service;
    private ImportValidationAppendService appendService;
    private ImportMetadataAuthorizationService authorization;
    private ImportMetadataService metadataService;
    private long jobSequence = 200L;

    private synchronized long nextJobId() {
        return ++jobSequence;
    }

    private record TestFixture(
            long jobId,
            long runId,
            ImportValidationLifecycleService.RunLease lease,
            ImportValidationAppendService.SealCapability capability
    ) {}

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:seal_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);

        authorization = mock(ImportMetadataAuthorizationService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(context);
        when(authorization.requireCurrentAuthorizationForWrite(context)).thenReturn(context);

        service = new ImportValidationLifecycleService(jdbc, authorization, mock(DocumentService.class),
                new DataSourceTransactionManager(dataSource));

        metadataService = mock(ImportMetadataService.class);
        appendService = new ImportValidationAppendService(jdbc, metadataService, service, new ObjectMapper());
    }

    private TestFixture setupFixture(String csvContent) {
        return setupFixtureWithService(service, appendService, csvContent);
    }

    private TestFixture setupFixture(String csvContent, String entity, Map<String, String> mapping) {
        return setupFixtureWithMapping(service, appendService, csvContent, entity, mapping);
    }

    private TestFixture setupFixtureWithService(
            ImportValidationLifecycleService targetService,
            ImportValidationAppendService targetAppendService,
            String csvContent) {
        return setupFixtureWithMapping(targetService, targetAppendService, csvContent, "CUSTOMER",
                Map.of("externalKey", "externalKey", "companyName", "companyName"));
    }

    private TestFixture setupFixtureWithMapping(
            ImportValidationLifecycleService targetService,
            ImportValidationAppendService targetAppendService,
            String csvContent, String entity, Map<String, String> mapping) {
        long jobId = nextJobId();
        byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
        byte[] sourceSha = digest().digest(csvBytes);

        // 正準な mapping JSON を ImportMetadataService.canonicalMapping から生成し、キー順序（TreeMap）とハッシュを確定
        byte[] mappingBytes = ImportMetadataService.canonicalMapping(mapping);
        byte[] mappingHash = hash(mappingBytes);

        long sourceId = jobId * 10 + 1;
        long documentId = jobId * 10 + 2;
        long versionId = jobId * 10 + 3;
        long mappingId = jobId * 10 + 4;

        jdbc.update("INSERT INTO t_import_source_system VALUES(?,?,?,?,?)", TENANT, sourceId, 3L, "source", "ACTIVE");
        jdbc.update("INSERT INTO t_document VALUES(?,?,?,?,?,?)", TENANT, documentId, "77", 0, null, 0);
        jdbc.update("INSERT INTO t_document_version VALUES(?,?,?,?,?,?,?)", TENANT, documentId, versionId, 1,
                HexFormat.of().formatHex(sourceSha), "CLEAN", 0);
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) VALUES(?,?,?,?,0)",
                TENANT, documentId, "IMPORT_JOB", jobId);
        jdbc.update("INSERT INTO t_import_mapping VALUES(?,?,?,?,?,?,?,?)", TENANT, mappingId, sourceId, entity,
                "ses-import-v1", 1, mappingHash, mappingBytes);
        jdbc.update("""
                INSERT INTO t_import_job(tenant_id,id,source_system_id,source_version,entity_type,schema_version,
                  source_encoding,source_sha256,legal_entity_id,document_id,document_version_id,document_version_no,
                  document_sha256,mapping_id,mapping_version,mapping_sha256,state,version_no,lease_generation)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'MAPPED',1,0)
                """, TENANT, jobId, sourceId, 3L, entity, "ses-import-v1", "UTF8", sourceSha, "77",
                documentId, versionId, 1, sourceSha, mappingId, 1, mappingHash);

        when(metadataService.openSource(jobId)).thenAnswer(inv ->
                new ImportSourceContent(new ByteArrayInputStream(csvBytes), "customer.csv",
                        ImportEncoding.UTF8, ImportEntity.valueOf(entity), HexFormat.of().formatHex(sourceSha)));

        var startOp = targetService.beginStart(jobId);
        var startLease = targetService.start(startOp);
        var completion = targetAppendService.appendRemainingWithSealCapability(startLease);
        return new TestFixture(jobId, startLease.runId(), completion.lease(), completion.capability());
    }

    @Test
    void posSeal02_successfulSealAndIdempotentRetryZeroRewrite() {
        String csv = "externalKey,companyName\ncust-1,株式会社A\ncust-2,株式会社B\n";
        var fixture = setupFixture(csv);

        // 1. 初回 seal: BUILDING -> SEALED
        var sealedLease = service.seal(fixture.capability);
        assertNotNull(sealedLease);
        assertEquals(fixture.capability.generation(), sealedLease.generation());
        assertEquals(fixture.capability.jobVersion(), sealedLease.jobVersion());

        String runState = jdbc.queryForObject(
                "SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, fixture.jobId, fixture.runId);
        assertEquals("SEALED", runState);
        Timestamp firstSealedAt = jdbc.queryForObject(
                "SELECT sealed_at FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Timestamp.class, TENANT, fixture.jobId, fixture.runId);
        assertNotNull(firstSealedAt);
        Long persistedSourceCount = jdbc.queryForObject(
                "SELECT source_row_count FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Long.class, TENANT, fixture.jobId, fixture.runId);
        assertEquals(2L, persistedSourceCount);

        // 2. POS-SEAL-02: 同一トークン再試行（ACK loss 冪等回復）
        var retryLease = service.seal(fixture.capability);
        assertNotNull(retryLease);
        assertEquals(sealedLease.jobVersion(), retryLease.jobVersion());
        assertEquals(sealedLease.expiresAt(), retryLease.expiresAt());

        Timestamp secondSealedAt = jdbc.queryForObject(
                "SELECT sealed_at FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Timestamp.class, TENANT, fixture.jobId, fixture.runId);
        assertEquals(firstSealedAt, secondSealedAt, "sealed_at は再取得・更新されず不変であること（Zero Rewrite）");
        long jobVersionAfter = jdbc.queryForObject(
                "SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, TENANT, fixture.jobId);
        assertEquals(fixture.capability.jobVersion(), jobVersionAfter, "job.version_no はインクリメントされないこと");
    }

    @Test
    void posW3c_recountsExactSealedSourceAndMintsRepeatablePrivateCapability() {
        var fixture = setupFixture("externalKey,companyName\ncust-1,株式会社A\ncust-2,株式会社B\n");
        var sealedLease = service.seal(fixture.capability);
        var first = appendService.recountSealed(sealedLease);
        var second = appendService.recountSealed(sealedLease);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.rowSetSha256(), second.rowSetSha256());
        assertEquals(first.summarySha256(), second.summarySha256());
        assertEquals("SEALED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, fixture.jobId, fixture.runId));
    }

    @Test
    void posW3d_atomicPublishAndAckRetryReturnPersistedCompletionWithoutRewrite() {
        var fixture = setupFixture("externalKey,companyName\ncust-w3d,株式会社W3D\n");
        var sealed = service.seal(fixture.capability);
        var capability = appendService.recountSealed(sealed);

        Timestamp completedAt = service.publish(capability);
        Map<String, Object> job = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?",
                TENANT, fixture.jobId);
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, fixture.jobId, fixture.runId);
        assertEquals("VALIDATED", job.get("state"));
        assertEquals(capability.lease().jobVersion() + 1, ((Number) job.get("version_no")).longValue());
        assertNull(job.get("active_validation_run_id"));
        assertEquals(fixture.runId, ((Number) job.get("current_validation_run_id")).longValue());
        assertNull(job.get("lease_owner"));
        assertNull(job.get("lease_expires_at"));
        assertEquals("COMPLETED", run.get("state"));
        assertNull(run.get("active_slot"));
        assertEquals(1, ((Number) run.get("published_slot")).intValue());
        assertEquals(completedAt, run.get("completed_at"));

        long publishedVersion = ((Number) job.get("version_no")).longValue();
        rejectAckMutationWithoutRewrite(fixture.jobId, fixture.runId,
                () -> jdbc.update("UPDATE t_import_job SET version_no=version_no+1 WHERE tenant_id=? AND id=?", TENANT, fixture.jobId),
                () -> jdbc.update("UPDATE t_import_job SET version_no=? WHERE tenant_id=? AND id=?", publishedVersion, TENANT, fixture.jobId),
                capability);
        long publishedGeneration = jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, TENANT, fixture.jobId);
        rejectAckMutationWithoutRewrite(fixture.jobId, fixture.runId,
                () -> jdbc.update("UPDATE t_import_job SET lease_generation=lease_generation+1 WHERE tenant_id=? AND id=?", TENANT, fixture.jobId),
                () -> jdbc.update("UPDATE t_import_job SET lease_generation=? WHERE tenant_id=? AND id=?", publishedGeneration, TENANT, fixture.jobId),
                capability);
        byte[] publishedSummarySha = (byte[]) run.get("summary_sha256");
        rejectAckMutationWithoutRewrite(fixture.jobId, fixture.runId,
                () -> jdbc.update("UPDATE t_import_validation_run SET summary_sha256=? WHERE tenant_id=? AND job_id=? AND id=?",
                        hash("wrong ack summary"), TENANT, fixture.jobId, fixture.runId),
                () -> jdbc.update("UPDATE t_import_validation_run SET summary_sha256=? WHERE tenant_id=? AND job_id=? AND id=?",
                        publishedSummarySha, TENANT, fixture.jobId, fixture.runId), capability);
        byte[] publishedSourceSha = (byte[]) run.get("source_sha256");
        rejectAckMutationWithoutRewrite(fixture.jobId, fixture.runId,
                () -> jdbc.update("UPDATE t_import_validation_run SET source_sha256=? WHERE tenant_id=? AND job_id=? AND id=?",
                        hash("wrong ack source"), TENANT, fixture.jobId, fixture.runId),
                () -> jdbc.update("UPDATE t_import_validation_run SET source_sha256=? WHERE tenant_id=? AND job_id=? AND id=?",
                        publishedSourceSha, TENANT, fixture.jobId, fixture.runId), capability);
        byte[] publishedRowSetSha = (byte[]) run.get("row_set_sha256");
        rejectAckMutationWithoutRewrite(fixture.jobId, fixture.runId,
                () -> jdbc.update("UPDATE t_import_validation_run SET row_set_sha256=? WHERE tenant_id=? AND job_id=? AND id=?",
                        hash("wrong ack rowset"), TENANT, fixture.jobId, fixture.runId),
                () -> jdbc.update("UPDATE t_import_validation_run SET row_set_sha256=? WHERE tenant_id=? AND job_id=? AND id=?",
                        publishedRowSetSha, TENANT, fixture.jobId, fixture.runId), capability);
        rejectAckMutationWithoutRewrite(fixture.jobId, fixture.runId,
                () -> jdbc.update("UPDATE t_import_validation_run SET completed_at=NULL WHERE tenant_id=? AND job_id=? AND id=?",
                        TENANT, fixture.jobId, fixture.runId),
                () -> jdbc.update("UPDATE t_import_validation_run SET completed_at=? WHERE tenant_id=? AND job_id=? AND id=?",
                        completedAt, TENANT, fixture.jobId, fixture.runId), capability);

        Timestamp expiredBeforeRetry = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
        expireCapabilityLeaseForAckRetry(capability, new Timestamp(expiredBeforeRetry.getTime() - 1_000));
        assertTrue(capability.lease().expiresAt().before(expiredBeforeRetry), "ACK retry時点でcapabilityの旧expiryが経過済みであること");
        Timestamp retriedAt = service.publish(capability);
        assertEquals(completedAt, retriedAt);
        assertDatabaseMapEquals(job, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?",
                TENANT, fixture.jobId));
        assertDatabaseMapEquals(run, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, fixture.jobId, fixture.runId));
    }

    @Test
    void negW3d_publishRechecksPersistedRowsAndRollsBackBothPointers() {
        var fixture = setupFixture("externalKey,companyName\ncust-w3d,株式会社W3D\n");
        var sealed = service.seal(fixture.capability);
        var capability = appendService.recountSealed(sealed);
        byte[] beforeRun = jdbc.queryForObject("SELECT summary_sha256 FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                byte[].class, TENANT, fixture.jobId, fixture.runId);

        Map<String, Object> storedRow = jdbc.queryForMap("SELECT source_row_number,canonical_fingerprint,disposition,finding_codes FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1",
                TENANT, fixture.jobId, fixture.runId);
        byte[] changedSourceHash = hash("post-recount row mutation");
        byte[] changedCommit = calculateRowCommit(((Number) storedRow.get("source_row_number")).longValue(),
                changedSourceHash, (byte[]) storedRow.get("canonical_fingerprint"), (String) storedRow.get("disposition"),
                (byte[]) storedRow.get("finding_codes"));
        jdbc.update("UPDATE t_import_validation_row SET source_row_sha256=?,row_commit_sha256=? WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1",
                changedSourceHash, changedCommit, TENANT, fixture.jobId, fixture.runId);
        assertThrows(BusinessException.class, () -> service.publish(capability));
        assertEquals("VALIDATING", jdbc.queryForObject("SELECT state FROM t_import_job WHERE tenant_id=? AND id=?",
                String.class, TENANT, fixture.jobId));
        assertEquals("SEALED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, fixture.jobId, fixture.runId));
        assertNull(jdbc.queryForObject("SELECT current_validation_run_id FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, TENANT, fixture.jobId));
        assertNull(jdbc.queryForObject("SELECT completed_at FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Timestamp.class, TENANT, fixture.jobId, fixture.runId));
        assertArrayEquals(beforeRun, jdbc.queryForObject("SELECT summary_sha256 FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                byte[].class, TENANT, fixture.jobId, fixture.runId));
    }

    @Test
    void negW3d_publishRejectsPostCapabilityFindingAndAmountCounterTamper() {
        var finding = setupFixture("externalKey,companyName\nfinding,会社\n");
        var findingLease = service.seal(finding.capability);
        var findingCapability = appendService.recountSealed(findingLease);
        jdbc.update("UPDATE t_import_validation_run SET finding_summary_bytes=? WHERE tenant_id=? AND job_id=? AND id=?",
                new byte[]{1, 2, 3}, TENANT, finding.jobId, finding.runId);
        Map<String, Object> findingJob = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, finding.jobId);
        Map<String, Object> findingRun = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, finding.jobId, finding.runId);
        assertThrows(BusinessException.class, () -> service.publish(findingCapability));
        assertDatabaseMapEquals(findingJob, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, finding.jobId));
        assertDatabaseMapEquals(findingRun, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, finding.jobId, finding.runId));

        var amount = setupFixture("externalKey,companyName\namount,会社\n");
        var amountLease = service.seal(amount.capability);
        var amountCapability = appendService.recountSealed(amountLease);
        jdbc.update("UPDATE t_import_validation_run SET cost_price_accepted_known_count=1,cost_price_accepted_sum_yen=0 WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, amount.jobId, amount.runId);
        Map<String, Object> amountJob = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, amount.jobId);
        Map<String, Object> amountRun = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, amount.jobId, amount.runId);
        assertThrows(BusinessException.class, () -> service.publish(amountCapability));
        assertDatabaseMapEquals(amountJob, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, amount.jobId));
        assertDatabaseMapEquals(amountRun, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, amount.jobId, amount.runId));
    }

    @Test
    void negW3d_publishRejectsTakeoverAfterCapabilityMint() {
        var fixture = setupFixture("externalKey,companyName\ntakeover,会社\n");
        var sealed = service.seal(fixture.capability);
        var capability = appendService.recountSealed(sealed);
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?",
                TENANT, fixture.jobId);
        var takeover = service.takeOver(service.beginTakeover(fixture.jobId, fixture.runId));
        assertTrue(takeover.generation() > capability.lease().generation());
        Map<String, Object> beforeJob = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, fixture.jobId);
        Map<String, Object> beforeRun = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, fixture.jobId, fixture.runId);

        BusinessException rejected = assertThrows(BusinessException.class, () -> service.publish(capability));
        assertEquals(409, rejected.getCode());
        assertDatabaseMapEquals(beforeJob, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, fixture.jobId));
        assertDatabaseMapEquals(beforeRun, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, fixture.jobId, fixture.runId));
    }

    @Test
    void negW3d_publishRequiresCurrentAclAndLockedResourceSnapshot() {
        var denied = setupFixture("externalKey,companyName\nacl-before-publish,会社\n");
        var deniedLease = service.seal(denied.capability);
        var deniedCapability = appendService.recountSealed(deniedLease);
        Map<String, Object> deniedJob = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, denied.jobId);
        Map<String, Object> deniedRun = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, denied.jobId, denied.runId);
        org.mockito.Mockito.doThrow(BusinessException.of(403, "error.forbidden"))
                .when(authorization).requireCurrentAuthorizationForWrite(context);
        BusinessException forbidden = assertThrows(BusinessException.class, () -> service.publish(deniedCapability));
        assertEquals(403, forbidden.getCode());
        assertDatabaseMapEquals(deniedJob, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, denied.jobId));
        assertDatabaseMapEquals(deniedRun, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, denied.jobId, denied.runId));

        org.mockito.Mockito.reset(authorization);
        when(authorization.requireCurrentAuthorization()).thenReturn(context);
        when(authorization.requireCurrentAuthorizationForWrite(context)).thenReturn(context);
        var drift = setupFixture("externalKey,companyName\nsource-drift,会社\n");
        var driftLease = service.seal(drift.capability);
        var driftCapability = appendService.recountSealed(driftLease);
        Long sourceId = jdbc.queryForObject("SELECT source_system_id FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, TENANT, drift.jobId);
        jdbc.update("UPDATE t_import_source_system SET status='DISABLED',version_no=version_no+1 WHERE tenant_id=? AND id=?",
                TENANT, sourceId);
        Map<String, Object> driftJob = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, drift.jobId);
        Map<String, Object> driftRun = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, drift.jobId, drift.runId);
        BusinessException conflict = assertThrows(BusinessException.class, () -> service.publish(driftCapability));
        assertEquals(409, conflict.getCode());
        assertDatabaseMapEquals(driftJob, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, drift.jobId));
        assertDatabaseMapEquals(driftRun, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, drift.jobId, drift.runId));

        var mappingDrift = setupFixture("externalKey,companyName\nmapping-drift,会社\n");
        var mappingLease = service.seal(mappingDrift.capability);
        var mappingCapability = appendService.recountSealed(mappingLease);
        Long mappingId = jdbc.queryForObject("SELECT mapping_id FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, TENANT, mappingDrift.jobId);
        jdbc.update("UPDATE t_import_mapping SET canonical_bytes=? WHERE tenant_id=? AND id=?",
                "{}".getBytes(StandardCharsets.UTF_8), TENANT, mappingId);
        Map<String, Object> mappingJob = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, mappingDrift.jobId);
        Map<String, Object> mappingRun = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, mappingDrift.jobId, mappingDrift.runId);
        BusinessException mappingConflict = assertThrows(BusinessException.class, () -> service.publish(mappingCapability));
        assertEquals(409, mappingConflict.getCode());
        assertDatabaseMapEquals(mappingJob, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, mappingDrift.jobId));
        assertDatabaseMapEquals(mappingRun, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, mappingDrift.jobId, mappingDrift.runId));
    }

    @Test
    void negW3d_publishRejectsRenewedLeaseAndAbortPublishBothOrders() {
        var renewed = setupFixture("externalKey,companyName\nrenewed,会社\n");
        var renewedLease = service.seal(renewed.capability);
        var staleCapability = appendService.recountSealed(renewedLease);
        service.renew(staleCapability.lease());
        assertThrows(BusinessException.class, () -> service.publish(staleCapability));

        var abortFirst = setupFixture("externalKey,companyName\nabort-first,会社\n");
        var abortFirstLease = service.seal(abortFirst.capability);
        var abortFirstCapability = appendService.recountSealed(abortFirstLease);
        service.abort(abortFirstCapability.lease());
        assertThrows(BusinessException.class, () -> service.publish(abortFirstCapability));
        assertEquals("ABORTED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, abortFirst.jobId, abortFirst.runId));
        assertNull(jdbc.queryForObject("SELECT active_validation_run_id FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, TENANT, abortFirst.jobId));

        var publishFirst = setupFixture("externalKey,companyName\npublish-first,会社\n");
        var publishFirstLease = service.seal(publishFirst.capability);
        var publishFirstCapability = appendService.recountSealed(publishFirstLease);
        service.publish(publishFirstCapability);
        assertThrows(BusinessException.class, () -> service.abort(publishFirstCapability.lease()));
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, publishFirst.jobId, publishFirst.runId));
        assertEquals("VALIDATED", jdbc.queryForObject("SELECT state FROM t_import_job WHERE tenant_id=? AND id=?",
                String.class, TENANT, publishFirst.jobId));
    }

    @Test
    void negW3d_ackRetryStillRequiresCurrentWriteAuthorization() {
        var fixture = setupFixture("externalKey,companyName\nacl,会社\n");
        var sealed = service.seal(fixture.capability);
        var capability = appendService.recountSealed(sealed);
        service.publish(capability);

        org.mockito.Mockito.doThrow(BusinessException.of(403, "error.forbidden"))
                .when(authorization).requireCurrentAuthorizationForWrite(context);
        BusinessException denied = assertThrows(BusinessException.class, () -> service.publish(capability));
        assertEquals(403, denied.getCode());
        assertEquals("VALIDATED", jdbc.queryForObject("SELECT state FROM t_import_job WHERE tenant_id=? AND id=?",
                String.class, TENANT, fixture.jobId));
    }

    @Test
    void negW3d_publishCommitFailureRollsBackRunAndJobCasTogether() {
        var injectingManager = new RollbackInjectingTransactionManager(dataSource);
        var faultService = new ImportValidationLifecycleService(
                jdbc, authorization, mock(DocumentService.class), injectingManager);
        var faultAppendService = new ImportValidationAppendService(
                jdbc, metadataService, faultService, new ObjectMapper());
        var fixture = setupFixtureWithService(faultService, faultAppendService,
                "externalKey,companyName\nrollback,会社\n");
        var sealed = faultService.seal(fixture.capability);
        var capability = faultAppendService.recountSealed(sealed);
        injectingManager.injectFailureOnCommit(fixture.runId, "COMPLETED");

        assertThrows(TransactionSystemException.class, () -> faultService.publish(capability));
        assertTrue(injectingManager.hasFailureTriggered());
        assertTrue(injectingManager.hasRollbackExecuted());
        assertEquals("SEALED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, fixture.jobId, fixture.runId));
        assertEquals(1, jdbc.queryForObject("SELECT active_slot FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Integer.class, TENANT, fixture.jobId, fixture.runId));
        assertNull(jdbc.queryForObject("SELECT published_slot FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Integer.class, TENANT, fixture.jobId, fixture.runId));
        assertNull(jdbc.queryForObject("SELECT completed_at FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Timestamp.class, TENANT, fixture.jobId, fixture.runId));
        assertEquals("VALIDATING", jdbc.queryForObject("SELECT state FROM t_import_job WHERE tenant_id=? AND id=?",
                String.class, TENANT, fixture.jobId));
        assertEquals(capability.lease().jobVersion(), jdbc.queryForObject("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, TENANT, fixture.jobId));
        assertNull(jdbc.queryForObject("SELECT current_validation_run_id FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, TENANT, fixture.jobId));
    }

    @Test
    void negW3d_jobCasZeroAfterRunCasRollsBackFullSnapshot() {
        JdbcTemplate failingJdbc = org.mockito.Mockito.spy(jdbc);
        AtomicBoolean jobCasSuppressed = new AtomicBoolean();
        AtomicBoolean runCasVisibleBeforeSuppression = new AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("UPDATE t_import_job SET state='VALIDATED'")) {
                Object[] args = (Object[]) invocation.getRawArguments()[1];
                String uncommittedRunState = jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                        String.class, args[1], args[2], args[0]);
                runCasVisibleBeforeSuppression.set("COMPLETED".equals(uncommittedRunState));
                jobCasSuppressed.set(true);
                return 0;
            }
            return invocation.callRealMethod();
        }).when(failingJdbc).update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
        var faultService = new ImportValidationLifecycleService(
                failingJdbc, authorization, mock(DocumentService.class), new DataSourceTransactionManager(dataSource));
        var faultAppendService = new ImportValidationAppendService(
                jdbc, metadataService, faultService, new ObjectMapper());
        var fixture = setupFixtureWithService(faultService, faultAppendService,
                "externalKey,companyName\ncas-zero,会社\n");
        var sealed = faultService.seal(fixture.capability);
        var capability = faultAppendService.recountSealed(sealed);
        Map<String, Object> beforeJob = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?",
                TENANT, fixture.jobId);
        Map<String, Object> beforeRun = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, fixture.jobId, fixture.runId);

        BusinessException rejected = assertThrows(BusinessException.class, () -> faultService.publish(capability));
        assertEquals(409, rejected.getCode());
        assertTrue(jobCasSuppressed.get(), "run CAS成功後にjob CASを制御して0件にしたこと");
        assertTrue(runCasVisibleBeforeSuppression.get(), "job CASを0件にする直前に同一transactionのrun CAS結果がCOMPLETEDであること");
        assertDatabaseMapEquals(beforeJob, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?",
                TENANT, fixture.jobId));
        assertDatabaseMapEquals(beforeRun, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, fixture.jobId, fixture.runId));
    }

    @Test
    void negW3c_rejectsMissingTailZeroRowsExtraRowsAndEachStoredRowFieldTamper() {
        String csv = "externalKey,companyName\ncust-1,株式会社A\ncust-2,株式会社B\n";
        var missingTail = setupFixture(csv);
        var missingTailLease = service.seal(missingTail.capability);
        jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=2",
                TENANT, missingTail.jobId, missingTail.runId);
        assertThrows(BusinessException.class, () -> appendService.recountSealed(missingTailLease));

        var noRows = setupFixture(csv);
        var noRowsLease = service.seal(noRows.capability);
        jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                TENANT, noRows.jobId, noRows.runId);
        assertThrows(BusinessException.class, () -> appendService.recountSealed(noRowsLease));

        var extra = setupFixture(csv);
        var extraLease = service.seal(extra.capability);
        insertRow(extra.jobId, extra.runId, 99);
        assertThrows(BusinessException.class, () -> appendService.recountSealed(extraLease));

        for (String column : List.of("source_row_sha256", "canonical_fingerprint", "disposition",
                "finding_codes", "row_commit_sha256")) {
            var changed = setupFixture(csv);
            var changedLease = service.seal(changed.capability);
            jdbc.update("UPDATE t_import_validation_row SET " + column + "=? WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1",
                    column.equals("disposition") ? "REJECTED" : hash("tampered"), TENANT, changed.jobId, changed.runId);
            assertThrows(BusinessException.class, () -> appendService.recountSealed(changedLease), column);
        }
        var rowNumber = setupFixture(csv);
        var rowNumberLease = service.seal(rowNumber.capability);
        jdbc.update("UPDATE t_import_validation_row SET source_row_number=10 WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=2",
                TENANT, rowNumber.jobId, rowNumber.runId);
        assertThrows(BusinessException.class, () -> appendService.recountSealed(rowNumberLease));
    }

    @Test
    void negW3c_rejectsSummaryTamperAndRevokedWriteAuthorization() {
        var summary = setupFixture("externalKey,companyName\ncust-1,株式会社A\n");
        var summaryLease = service.seal(summary.capability);
        jdbc.update("UPDATE t_import_validation_run SET accepted_row_count=0 WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, summary.jobId, summary.runId);
        assertThrows(BusinessException.class, () -> appendService.recountSealed(summaryLease));

        var denied = setupFixture("externalKey,companyName\ncust-1,株式会社A\n");
        var deniedLease = service.seal(denied.capability);
        org.mockito.Mockito.doThrow(BusinessException.of(403, "error.forbidden"))
                .when(authorization).requireCurrentAuthorizationForWrite(context);
        assertThrows(BusinessException.class, () -> appendService.recountSealed(deniedLease));
    }

    @Test
    void negW3c_rejectsNullVersusZeroAmountSummaryTamper() {
        var fixture = setupFixture("externalKey,companyName\ncust-1,株式会社A\n");
        var lease = service.seal(fixture.capability);
        jdbc.update("UPDATE t_import_validation_run SET cost_price_accepted_sum_yen=0 WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, fixture.jobId, fixture.runId);
        assertThrows(BusinessException.class, () -> appendService.recountSealed(lease));
    }

    @Test
    void negW3c_rejectsNullInEveryPersistedCounterAndAcceptsKnownZeroYen() {
        var fixture = setupFixture("externalKey,companyName\ncust-1,株式会社A\n");
        var lease = service.seal(fixture.capability);
        for (String column : List.of("source_row_count", "physical_row_count", "empty_row_count", "accepted_row_count",
                "rejected_row_count", "expected_unit_price_accepted_known_count", "expected_unit_price_rejected_known_count",
                "expected_unit_price_excluded_count", "unit_price_min_accepted_known_count", "unit_price_min_rejected_known_count",
                "unit_price_min_excluded_count", "unit_price_max_accepted_known_count", "unit_price_max_rejected_known_count",
                "unit_price_max_excluded_count", "selling_price_accepted_known_count", "selling_price_rejected_known_count",
                "selling_price_excluded_count", "cost_price_accepted_known_count", "cost_price_rejected_known_count",
                "cost_price_excluded_count")) {
            Long original = jdbc.queryForObject("SELECT " + column + " FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                    Long.class, TENANT, fixture.jobId, fixture.runId);
            jdbc.update("UPDATE t_import_validation_run SET " + column + "=NULL WHERE tenant_id=? AND job_id=? AND id=?",
                    TENANT, fixture.jobId, fixture.runId);
            assertThrows(BusinessException.class, () -> appendService.recountSealed(lease), column);
            jdbc.update("UPDATE t_import_validation_run SET " + column + "=? WHERE tenant_id=? AND job_id=? AND id=?",
                    original, TENANT, fixture.jobId, fixture.runId);
        }

        var zero = setupFixture("externalKey,fullName,expectedUnitPrice\neng-zero,社員零,0\n", "ENGINEER",
                Map.of("externalKey", "externalKey", "fullName", "fullName", "expectedUnitPrice", "expectedUnitPrice"));
        var zeroLease = service.seal(zero.capability);
        var zeroCapability = appendService.recountSealed(zeroLease);
        assertNotNull(zeroCapability);
        assertEquals(1L, jdbc.queryForObject("SELECT expected_unit_price_accepted_known_count FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Long.class, TENANT, zero.jobId, zero.runId));
        assertEquals(0, jdbc.queryForObject("SELECT expected_unit_price_accepted_sum_yen FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                BigDecimal.class, TENANT, zero.jobId, zero.runId).compareTo(BigDecimal.ZERO));
    }

    @Test
    void posW3c_exactHeartbeatRenewsAt250RowsWithoutAdoptingExternalRenew() {
        StringBuilder csv = new StringBuilder("externalKey,companyName\n");
        for (int i = 0; i < 251; i++) csv.append("cust-").append(i).append(",会社").append(i).append('\n');
        var fixture = setupFixture(csv.toString());
        var sealedLease = service.seal(fixture.capability);
        var capability = appendService.recountSealed(sealedLease);
        assertNotNull(capability);
        assertTrue(capability.lease().jobVersion() > sealedLease.jobVersion());
        assertEquals("SEALED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, fixture.jobId, fixture.runId));
    }

    @Test
    void negW3c_finalGateRejectsRenewOrTakeoverAfterRecountStarted() {
        var renewed = setupFixture("externalKey,companyName\ncust-1,株式会社A\n");
        var renewedLease = service.seal(renewed.capability);
        org.mockito.Mockito.doAnswer(invocation -> {
            jdbc.update("UPDATE t_import_job SET version_no=version_no+1 WHERE tenant_id=? AND id=?",
                    TENANT, renewed.jobId);
            return new ImportSourceContent(new ByteArrayInputStream("externalKey,companyName\ncust-1,株式会社A\n".getBytes(StandardCharsets.UTF_8)),
                    "customer.csv", ImportEncoding.UTF8, ImportEntity.CUSTOMER,
                    HexFormat.of().formatHex(digest().digest("externalKey,companyName\ncust-1,株式会社A\n".getBytes(StandardCharsets.UTF_8))));
        }).when(metadataService).openSource(renewed.jobId);
        assertThrows(BusinessException.class, () -> appendService.recountSealed(renewedLease));

        var takeover = setupFixture("externalKey,companyName\ncust-2,株式会社B\n");
        var takeoverLease = service.seal(takeover.capability);
        byte[] takeoverCsv = "externalKey,companyName\ncust-2,株式会社B\n".getBytes(StandardCharsets.UTF_8);
        org.mockito.Mockito.doAnswer(invocation -> {
            jdbc.update("UPDATE t_import_job SET lease_generation=lease_generation+1,version_no=version_no+1 WHERE tenant_id=? AND id=?",
                    TENANT, takeover.jobId);
            jdbc.update("UPDATE t_import_validation_run SET last_resumed_lease_generation=last_resumed_lease_generation+1 WHERE tenant_id=? AND job_id=? AND id=?",
                    TENANT, takeover.jobId, takeover.runId);
            return new ImportSourceContent(new ByteArrayInputStream(takeoverCsv), "customer.csv", ImportEncoding.UTF8,
                    ImportEntity.CUSTOMER, HexFormat.of().formatHex(digest().digest(takeoverCsv)));
        }).when(metadataService).openSource(takeover.jobId);
        assertThrows(BusinessException.class, () -> appendService.recountSealed(takeoverLease));
    }

    @Test
    void posSeal_leadingAndMiddleEmptyRows_successWithNonContiguousSourceRowNumbers() {
        // 先頭空行（2行目）および中間空行（4行目）を含むCSV。
        // ヘッダー読み取り後に physicalRows=0 からカウントするため、
        // 1行目の空行で physicalRows=1 (emptySkipped)、
        // 2行目の cust-1 で physicalRows=2 (source_row_number=2)、
        // 3行目の空行で physicalRows=3 (emptySkipped)、
        // 4行目の cust-2 で physicalRows=4 (source_row_number=4) となる。
        String csvWithEmptyLines = "externalKey,companyName\n\ncust-1,株式会社A\n\ncust-2,株式会社B\n";
        var fixture = setupFixture(csvWithEmptyLines);

        List<Long> rowNumbers = jdbc.query(
                "SELECT source_row_number FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? ORDER BY source_row_number",
                (rs, i) -> rs.getLong(1), TENANT, fixture.jobId, fixture.runId);
        assertEquals(List.of(2L, 4L), rowNumbers, "空行スキップにより source_row_number は 2, 4 となり 1 から連続せず跳躍するが厳格単調増加であること");

        var sealedLease = service.seal(fixture.capability);
        assertNotNull(sealedLease);

        String state = jdbc.queryForObject(
                "SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, fixture.jobId, fixture.runId);
        assertEquals("SEALED", state);
    }

    @Test
    void posSeal03_idempotentRetryAcceptsRenewedVersionAndExtendedExpiry() {
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        service.seal(fixture.capability);

        // 同一世代で renew 実行（version 進行 & expiry 延長）
        jdbc.update("""
                UPDATE t_import_job SET
                    version_no = version_no + 1,
                    lease_expires_at = TIMESTAMPADD(SECOND, 60, lease_expires_at)
                WHERE tenant_id=? AND id=?
                """, TENANT, fixture.jobId);

        var retryLease = service.seal(fixture.capability);
        assertNotNull(retryLease);
        assertTrue(retryLease.jobVersion() > fixture.capability.jobVersion());
        assertTrue(retryLease.expiresAt().after(fixture.capability.expiresAt()));
    }

    @Test
    void posSeal04_idempotentRetryAcceptsAdvancedVersionWithEqualExpiry() {
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        service.seal(fixture.capability);

        // version_no のみ増加し、lease_expires_at は同値を維持（GREATEST 特性）
        jdbc.update("UPDATE t_import_job SET version_no = version_no + 1 WHERE tenant_id=? AND id=?", TENANT, fixture.jobId);

        var retryLease = service.seal(fixture.capability);
        assertNotNull(retryLease);
        assertEquals(fixture.capability.jobVersion() + 1, retryLease.jobVersion());
        assertEquals(fixture.capability.expiresAt(), retryLease.expiresAt());
    }

    @Test
    void negSeal01_to_04_invalidTokensRejected() {
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        // NEG-SEAL-01: 他テナント
        var wrongTenantContext = new ImportAuthorizedContext("other-tenant", 41, 77);
        var wrongTenantCapability = reflectedCapabilityWithContext(fixture.capability, wrongTenantContext);
        assertThrows(BusinessException.class, () -> service.seal(wrongTenantCapability));

        // NEG-SEAL-02: 別 job / 別 run
        var wrongJobCapability = reflectedCapabilityWithJob(fixture.capability, 9999L);
        assertThrows(BusinessException.class, () -> service.seal(wrongJobCapability));

        // NEG-SEAL-03: 旧世代
        var staleGenCapability = reflectedCapabilityWithGeneration(fixture.capability, 0L);
        assertThrows(BusinessException.class, () -> service.seal(staleGenCapability));

        // NEG-SEAL-04: 期限切れ
        var expiredCapability = reflectedCapabilityWithExpiry(fixture.capability, Timestamp.from(Instant.now().minus(60, ChronoUnit.SECONDS)));
        assertThrows(BusinessException.class, () -> service.seal(expiredCapability));
    }

    @Test
    void negSeal05_to_08_rowSetDiscrepanciesRejected() {
        String csv = "externalKey,companyName\ncust-1,株式会社A\ncust-2,株式会社B\n";
        var fixture = setupFixture(csv);

        // NEG-SEAL-05: DB 側に余分な行（行数不一致）
        jdbc.update("""
                INSERT INTO t_import_validation_row(tenant_id,job_id,validation_run_id,source_row_number,
                  source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256)
                VALUES(?,?,?,99,?,?,?,?,?)
                """, TENANT, fixture.jobId, fixture.runId, hash("extra"), hash("extra"), "ACCEPTED", new byte[0], hash("extra"));
        assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=99",
                TENANT, fixture.jobId, fixture.runId);

        // NEG-SEAL-06: DB 側の行が欠落（行数不一致）
        jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=2",
                TENANT, fixture.jobId, fixture.runId);
        assertThrows(BusinessException.class, () -> service.seal(fixture.capability));

        // NEG-SEAL-07: commit sha 改ざん（新しい独立 fixture）
        var fixture07 = setupFixture(csv);
        jdbc.update("UPDATE t_import_validation_row SET row_commit_sha256=? WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1",
                hash("tampered"), TENANT, fixture07.jobId, fixture07.runId);
        assertThrows(BusinessException.class, () -> service.seal(fixture07.capability));

        // NEG-SEAL-08: 異なる行番号集合（5, 10）による行セット・ハッシュ不一致
        // （※ORDER BY source_row_number ASC により 5, 10 の順で整列走査されるため走査順序逆転ではなく、
        //   capability が保持する row-set ハッシュとの不一致として 409 で拒絶される）
        var fixture08 = setupFixture(csv);
        jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                TENANT, fixture08.jobId, fixture08.runId);
        insertRow(fixture08.jobId, fixture08.runId, 10);
        insertRow(fixture08.jobId, fixture08.runId, 5);
        assertThrows(BusinessException.class, () -> service.seal(fixture08.capability));
    }

    @Test
    void negSeal09_to_15_summaryContractViolationsRejectedAtConstructorBoundary() {
        // NEG-09〜15 は Snapshot / AmountCounter 構造の不変条件であり、
        // 構築境界（コンストラクタ）において IllegalArgumentException で堅牢に拒絶されることを検証する。
        long sourceRows = 2;
        Map<String, ValidationCounterAccumulator.AmountCounter> amounts = createValidAmounts();
        byte[] validFindingBytes = new byte[0];
        byte[] validFindingSha = hash(validFindingBytes);
        byte[] validReasonBytes = new byte[0];
        byte[] validReasonSha = hash(validReasonBytes);

        // NEG-SEAL-09: physical != source + empty
        assertThrows(IllegalArgumentException.class, () -> new ValidationCounterAccumulator.Snapshot(
                sourceRows + 10, sourceRows, 2, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, Map.of(), Map.of(), Map.of(), Map.of(),
                amounts, validFindingBytes, validFindingSha, validReasonBytes, validReasonSha));

        // NEG-SEAL-10: source != accepted + rejected
        assertThrows(IllegalArgumentException.class, () -> new ValidationCounterAccumulator.Snapshot(
                sourceRows, sourceRows, 1, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, Map.of(), Map.of(), Map.of(), Map.of(),
                amounts, validFindingBytes, validFindingSha, validReasonBytes, validReasonSha));

        // NEG-SEAL-11: known count = 0 なのに sum != null
        assertThrows(IllegalArgumentException.class, () ->
                new ValidationCounterAccumulator.AmountCounter(0, BigDecimal.TEN, 0, null, 0, Map.of()));

        // NEG-SEAL-12: known count > 0 なのに sum == null
        assertThrows(IllegalArgumentException.class, () ->
                new ValidationCounterAccumulator.AmountCounter(1, null, 0, null, 0, Map.of()));

        // NEG-SEAL-13: 金額精度溢れ（39桁以上）
        assertThrows(IllegalArgumentException.class, () ->
                new ValidationCounterAccumulator.AmountCounter(1, new BigDecimal("1" + "0".repeat(38)), 0, null, 0, Map.of()));

        // NEG-SEAL-14: finding summary bytes 超過 (8192バイト超)
        byte[] over8192FindingBytes = new byte[8193];
        byte[] over8192FindingSha = hash(over8192FindingBytes);
        assertThrows(IllegalArgumentException.class, () -> new ValidationCounterAccumulator.Snapshot(
                sourceRows, sourceRows, 2, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, Map.of(), Map.of(), Map.of(), Map.of(),
                amounts, over8192FindingBytes, over8192FindingSha, validReasonBytes, validReasonSha));

        // NEG-SEAL-15: amount reason bytes 超過 (8192バイト超)
        byte[] over8192ReasonBytes = new byte[8193];
        byte[] over8192ReasonSha = hash(over8192ReasonBytes);
        assertThrows(IllegalArgumentException.class, () -> new ValidationCounterAccumulator.Snapshot(
                sourceRows, sourceRows, 2, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, Map.of(), Map.of(), Map.of(), Map.of(),
                amounts, validFindingBytes, validFindingSha, over8192ReasonBytes, over8192ReasonSha));
    }

    @Test
    void negSeal26_to_29_ackLossNegativeBranches() {
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        service.seal(fixture.capability);

        // NEG-SEAL-26: アクター権限 revoke 時は 403 Forbidden（認可ゲート維持、撤権バイパス厳禁）
        when(authorization.requireCurrentAuthorizationForWrite(context))
                .thenThrow(BusinessException.of(403, "error.forbidden"));
        var ex26 = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(403, ex26.getCode());

        // 認可復旧
        doReturn(context).when(authorization).requireCurrentAuthorizationForWrite(context);

        // NEG-SEAL-27a: リース期限切れ（expiresAt <= dbNow）
        jdbc.update("UPDATE t_import_job SET lease_expires_at = TIMESTAMPADD(SECOND, -10, CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?",
                TENANT, fixture.jobId);
        var ex27a = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(409, ex27a.getCode());

        // NEG-SEAL-27b: expiry 回退（capability の期限より過去へ回退）
        jdbc.update("UPDATE t_import_job SET lease_expires_at = TIMESTAMPADD(SECOND, -60, CAST(? AS TIMESTAMP)) WHERE tenant_id=? AND id=?",
                fixture.capability.expiresAt(), TENANT, fixture.jobId);
        var ex27b = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(409, ex27b.getCode());

        // lease_expires_at を復元
        jdbc.update("UPDATE t_import_job SET lease_expires_at = ? WHERE tenant_id=? AND id=?",
                fixture.capability.expiresAt(), TENANT, fixture.jobId);

        // NEG-SEAL-27c: job.version 逆行（capability の version より過去へ逆行）
        jdbc.update("UPDATE t_import_job SET version_no = ? WHERE tenant_id=? AND id=?",
                fixture.capability.jobVersion() - 1, TENANT, fixture.jobId);
        var ex27c = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(409, ex27c.getCode());

        // version_no を復元
        jdbc.update("UPDATE t_import_job SET version_no = ? WHERE tenant_id=? AND id=?",
                fixture.capability.jobVersion(), TENANT, fixture.jobId);

        // NEG-SEAL-28: 別ハッシュの SealCapability
        var diffCapability = reflectedCapabilityWithRowSet(fixture.capability, HexFormat.of().formatHex(hash("different-row-set")));
        var ex28 = assertThrows(BusinessException.class, () -> service.seal(diffCapability));
        assertEquals(409, ex28.getCode());

        // NEG-SEAL-29: Takeover 後（新世代）の旧世代再試行
        jdbc.update("UPDATE t_import_job SET lease_generation = lease_generation + 1 WHERE tenant_id=? AND id=?",
                TENANT, fixture.jobId);
        var ex29 = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(409, ex29.getCode());
    }

    @Test
    void negSeal30_atomicRollbackOnCommitFailureAfterCasUpdateZeroPartialWrite() {
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";

        // 同一 instanceId を保持するため、start・append・seal の全フェーズを同じ注入版 lifecycle service で遂行する
        var injectingManager = new RollbackInjectingTransactionManager(dataSource);
        var faultInjectedService = new ImportValidationLifecycleService(
                jdbc, authorization, mock(DocumentService.class), injectingManager);
        var faultInjectedAppendService = new ImportValidationAppendService(
                jdbc, metadataService, faultInjectedService, new ObjectMapper());

        var fixture = setupFixtureWithService(faultInjectedService, faultInjectedAppendService, csv);

        // 目的の seal フェーズ直前にのみコミット障害を有効化
        injectingManager.injectFailureOnCommit(fixture.runId);

        assertThrows(TransactionSystemException.class, () -> faultInjectedService.seal(fixture.capability));
        assertTrue(injectingManager.hasFailureTriggered(), "同一接続で SEALED 到達確認後のコミット直前故障注入が確実にトリガーされたこと");
        assertTrue(injectingManager.hasRollbackExecuted(), "コミット例外発生時に doRollback が確実に実行されたこと");

        // 零部分書き込み確認: run は BUILDING のまま、summary 列は null、sealed_at も null
        String runState = jdbc.queryForObject(
                "SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, fixture.jobId, fixture.runId);
        assertEquals("BUILDING", runState);
        Timestamp sealedAt = jdbc.queryForObject(
                "SELECT sealed_at FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Timestamp.class, TENANT, fixture.jobId, fixture.runId);
        assertNull(sealedAt);
        Long sourceRowCount = jdbc.queryForObject(
                "SELECT source_row_count FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Long.class, TENANT, fixture.jobId, fixture.runId);
        assertNull(sourceRowCount);
        Long physicalRowCount = jdbc.queryForObject(
                "SELECT physical_row_count FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                Long.class, TENANT, fixture.jobId, fixture.runId);
        assertNull(physicalRowCount);
        byte[] summarySha = jdbc.queryForObject(
                "SELECT summary_sha256 FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                byte[].class, TENANT, fixture.jobId, fixture.runId);
        assertNull(summarySha);
        byte[] rowSetSha = jdbc.queryForObject(
                "SELECT row_set_sha256 FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                byte[].class, TENANT, fixture.jobId, fixture.runId);
        assertNull(rowSetSha);

        String jobState = jdbc.queryForObject(
                "SELECT state FROM t_import_job WHERE tenant_id=? AND id=?",
                String.class, TENANT, fixture.jobId);
        assertEquals("VALIDATING", jobState);
        long jobVersion = jdbc.queryForObject(
                "SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, TENANT, fixture.jobId);
        assertEquals(fixture.capability.jobVersion(), jobVersion);
    }

    private Map<String, ValidationCounterAccumulator.AmountCounter> createValidAmounts() {
        Map<String, ValidationCounterAccumulator.AmountCounter> map = new LinkedHashMap<>();
        for (String field : List.of("costPrice", "expectedUnitPrice", "sellingPrice", "unitPriceMax", "unitPriceMin")) {
            map.put(field, new ValidationCounterAccumulator.AmountCounter(0, null, 0, null, 0, Map.of()));
        }
        return map;
    }

    private void insertRow(long jobId, long runId, long rowNumber) {
        byte[] commitSha = hash("row-" + rowNumber);
        jdbc.update("""
                INSERT INTO t_import_validation_row(tenant_id,job_id,validation_run_id,source_row_number,
                  source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, TENANT, jobId, runId, rowNumber, hash("source-" + rowNumber), hash("canonical-" + rowNumber),
                "ACCEPTED", new byte[0], commitSha);
    }

    private static final class RollbackInjectingTransactionManager extends DataSourceTransactionManager {
        private final AtomicBoolean inject = new AtomicBoolean(false);
        private final AtomicBoolean failureTriggered = new AtomicBoolean(false);
        private final AtomicBoolean rollbackExecuted = new AtomicBoolean(false);
        private Long targetRunId;
        private String expectedState = "SEALED";

        private RollbackInjectingTransactionManager(javax.sql.DataSource ds) {
            super(ds);
            // コミット例外発生時に確実に doRollback を実行させる設定（※テスト用マネージャー内限定）
            setRollbackOnCommitFailure(true);
        }

        void injectFailureOnCommit(long runId) {
            injectFailureOnCommit(runId, "SEALED");
        }

        void injectFailureOnCommit(long runId, String state) {
            this.targetRunId = runId;
            this.expectedState = state;
            this.inject.set(true);
        }

        boolean hasFailureTriggered() {
            return failureTriggered.get();
        }

        boolean hasRollbackExecuted() {
            return rollbackExecuted.get();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (inject.compareAndSet(true, false)) {
                // holder、connection、targetRunId の存在を厳格検証（欠落時は明確に失敗）
                ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(obtainDataSource());
                if (holder == null || holder.getConnection() == null) {
                    throw new IllegalStateException("Transaction ConnectionHolder or Connection is missing in doCommit");
                }
                if (targetRunId == null) {
                    throw new IllegalStateException("targetRunId is not set in RollbackInjectingTransactionManager");
                }
                // トランザクション内で対象CAS後の状態へ到達したことを同一接続で事前確認
                try (PreparedStatement ps = holder.getConnection().prepareStatement(
                        "SELECT state FROM t_import_validation_run WHERE id = ?")) {
                    ps.setLong(1, targetRunId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("Target run row not found in uncommitted transaction: id=" + targetRunId);
                        }
                        String uncommittedState = rs.getString(1);
                        if (!expectedState.equals(uncommittedState)) {
                            throw new IllegalStateException("Expected uncommitted state to be " + expectedState + " before commit, but got: " + uncommittedState);
                        }
                    }
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to verify uncommitted target state in doCommit", e);
                }
                // 同一接続で未コミット SEALED 到達が確認できた場合のみ注入成功を記録
                failureTriggered.set(true);
                throw new TransactionSystemException("simulated commit failure after CAS update to " + expectedState);
            }
            super.doCommit(status);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbackExecuted.set(true);
            super.doRollback(status);
        }
    }

    private ImportValidationAppendService.SealCapability reflectedCapabilityWithContext(
            ImportValidationAppendService.SealCapability base, ImportAuthorizedContext newCtx) {
        var newLease = createLeaseWithContext(base.lease(), newCtx);
        return reflectedCapability(newLease, base.counters(), base.sourceSha256(), base.rowSetSha256());
    }

    private ImportValidationAppendService.SealCapability reflectedCapabilityWithJob(
            ImportValidationAppendService.SealCapability base, long newJobId) {
        var newLease = createLeaseWithJob(base.lease(), newJobId);
        return reflectedCapability(newLease, base.counters(), base.sourceSha256(), base.rowSetSha256());
    }

    private ImportValidationAppendService.SealCapability reflectedCapabilityWithGeneration(
            ImportValidationAppendService.SealCapability base, long newGen) {
        var newLease = createLeaseWithGeneration(base.lease(), newGen);
        return reflectedCapability(newLease, base.counters(), base.sourceSha256(), base.rowSetSha256());
    }

    private ImportValidationAppendService.SealCapability reflectedCapabilityWithExpiry(
            ImportValidationAppendService.SealCapability base, Timestamp newExpiry) {
        var newLease = createLeaseWithExpiry(base.lease(), newExpiry);
        return reflectedCapability(newLease, base.counters(), base.sourceSha256(), base.rowSetSha256());
    }

    private ImportValidationAppendService.SealCapability reflectedCapabilityWithRowSet(
            ImportValidationAppendService.SealCapability base, String newRowSetSha) {
        return reflectedCapability(base.lease(), base.counters(), base.sourceSha256(), newRowSetSha);
    }

    private ImportValidationAppendService.SealCapability reflectedCapability(
            ImportValidationLifecycleService.RunLease targetLease,
            ValidationCounterAccumulator.Snapshot counters,
            String sourceSha256, String rowSetSha256) {
        try {
            var ctor = ImportValidationAppendService.SealCapability.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return (ImportValidationAppendService.SealCapability) ctor.newInstance(
                    targetLease, service.appendSnapshot(targetLease), sourceSha256, rowSetSha256, counters);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private ImportValidationLifecycleService.RunLease createLeaseWithContext(
            ImportValidationLifecycleService.RunLease base, ImportAuthorizedContext newCtx) {
        try {
            var inputField = base.getClass().getDeclaredField("input");
            inputField.setAccessible(true);
            Object inputObj = inputField.get(base);
            var inClass = inputObj.getClass();
            var ctor = inClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object[] args = new Object[ctor.getParameterCount()];
            args[0] = newCtx;
            var fields = inClass.getDeclaredFields();
            for (int i = 1; i < fields.length; i++) {
                fields[i].setAccessible(true);
                args[i] = fields[i].get(inputObj);
            }
            Object newInput = ctor.newInstance(args);

            var leaseCtor = base.getClass().getDeclaredConstructors()[0];
            leaseCtor.setAccessible(true);
            return (ImportValidationLifecycleService.RunLease) leaseCtor.newInstance(
                    getField(base, "instanceId"), newInput, base.runId(), base.workerId(),
                    base.generation(), base.jobVersion(), base.expiresAt());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private ImportValidationLifecycleService.RunLease createLeaseWithJob(
            ImportValidationLifecycleService.RunLease base, long newJobId) {
        try {
            var inputField = base.getClass().getDeclaredField("input");
            inputField.setAccessible(true);
            Object inputObj = inputField.get(base);
            var inClass = inputObj.getClass();
            var ctor = inClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object[] args = new Object[ctor.getParameterCount()];
            var fields = inClass.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                fields[i].setAccessible(true);
                args[i] = fields[i].get(inputObj);
            }
            args[1] = newJobId;
            Object newInput = ctor.newInstance(args);

            var leaseCtor = base.getClass().getDeclaredConstructors()[0];
            leaseCtor.setAccessible(true);
            return (ImportValidationLifecycleService.RunLease) leaseCtor.newInstance(
                    getField(base, "instanceId"), newInput, base.runId(), base.workerId(),
                    base.generation(), base.jobVersion(), base.expiresAt());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private ImportValidationLifecycleService.RunLease createLeaseWithGeneration(
            ImportValidationLifecycleService.RunLease base, long newGen) {
        try {
            var leaseCtor = base.getClass().getDeclaredConstructors()[0];
            leaseCtor.setAccessible(true);
            return (ImportValidationLifecycleService.RunLease) leaseCtor.newInstance(
                    getField(base, "instanceId"), getField(base, "input"), base.runId(), base.workerId(),
                    newGen, base.jobVersion(), base.expiresAt());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private ImportValidationLifecycleService.RunLease createLeaseWithExpiry(
            ImportValidationLifecycleService.RunLease base, Timestamp newExpiry) {
        try {
            var leaseCtor = base.getClass().getDeclaredConstructors()[0];
            leaseCtor.setAccessible(true);
            return (ImportValidationLifecycleService.RunLease) leaseCtor.newInstance(
                    getField(base, "instanceId"), getField(base, "input"), base.runId(), base.workerId(),
                    base.generation(), base.jobVersion(), newExpiry);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void expireCapabilityLeaseForAckRetry(ImportValidationAppendService.PublishCapability capability,
                                                   Timestamp expiredAt) {
        try {
            var expiry = capability.lease().getClass().getDeclaredField("expiresAt");
            expiry.setAccessible(true);
            expiry.set(capability.lease(), expiredAt);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException("ACK-loss expiry fixtureの設定に失敗しました", failure);
        }
    }

    private static byte[] calculateRowCommit(long rowNumber, byte[] sourceHash, byte[] canonicalFingerprint,
                                             String disposition, byte[] findingCodes) {
        try {
            var method = ImportValidationAppendService.class.getDeclaredMethod("rowCommit", long.class,
                    byte[].class, byte[].class, String.class, byte[].class);
            method.setAccessible(true);
            return (byte[]) method.invoke(null, rowNumber, sourceHash, canonicalFingerprint, disposition, findingCodes);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException("有効なrow commitmentのテストfixtureを生成できません", failure);
        }
    }

    private static void assertDatabaseMapEquals(Map<String, Object> expected, Map<String, Object> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String column : expected.keySet()) {
            Object left = expected.get(column);
            Object right = actual.get(column);
            if (left instanceof byte[] leftBytes && right instanceof byte[] rightBytes) {
                assertArrayEquals(leftBytes, rightBytes, column);
            } else {
                assertEquals(left, right, column);
            }
        }
    }

    private void rejectAckMutationWithoutRewrite(long jobId, long runId, Runnable mutate, Runnable restore,
                                                 ImportValidationAppendService.PublishCapability capability) {
        mutate.run();
        Map<String, Object> tamperedJob = jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, jobId);
        Map<String, Object> tamperedRun = jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, jobId, runId);
        BusinessException rejected = assertThrows(BusinessException.class, () -> service.publish(capability));
        assertEquals(409, rejected.getCode());
        assertDatabaseMapEquals(tamperedJob, jdbc.queryForMap("SELECT * FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, jobId));
        assertDatabaseMapEquals(tamperedRun, jdbc.queryForMap("SELECT * FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, jobId, runId));
        restore.run();
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        var f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void schema(JdbcTemplate j) {
        j.execute("CREATE TABLE t_import_source_system(tenant_id VARCHAR(100),id BIGINT,version_no BIGINT,source_code VARCHAR(64),status VARCHAR(16),PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_document(tenant_id VARCHAR(100),id BIGINT,legal_entity_id VARCHAR(100),legal_hold_flag INT,retention_until DATE,deleted_flag INT,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_document_version(tenant_id VARCHAR(100),document_id BIGINT,id BIGINT,version_no INT,sha256 VARCHAR(64),scan_status VARCHAR(16),deleted_flag INT,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_document_link(tenant_id VARCHAR(100),id BIGINT AUTO_INCREMENT PRIMARY KEY,document_id BIGINT,target_type VARCHAR(32),target_id BIGINT,deleted_flag INT)");
        j.execute("CREATE TABLE t_import_mapping(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),mapping_version INT,mapping_sha256 BINARY(32),canonical_bytes BLOB,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_import_job(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,source_version BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),source_encoding VARCHAR(16),source_sha256 BINARY(32),legal_entity_id VARCHAR(100),document_id BIGINT,document_version_id BIGINT,document_version_no INT,document_sha256 BINARY(32),mapping_id BIGINT,mapping_version INT,mapping_sha256 BINARY(32),state VARCHAR(16),version_no BIGINT,lease_owner VARCHAR(128),lease_generation BIGINT,lease_expires_at TIMESTAMP(6),active_validation_run_id BIGINT,current_validation_run_id BIGINT,updated_at TIMESTAMP(6),PRIMARY KEY(id),UNIQUE(tenant_id,id))");
        j.execute("CREATE TABLE t_import_validation_run(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(100),job_id BIGINT,run_no INT,state VARCHAR(16),created_lease_generation BIGINT,last_resumed_lease_generation BIGINT,active_slot INT,published_slot INT,actor_user_id BIGINT,source_system_id BIGINT,source_version BIGINT,source_code_snapshot VARCHAR(64),entity_type VARCHAR(24),schema_version VARCHAR(32),source_encoding VARCHAR(16),source_sha256 BINARY(32),document_id BIGINT,document_version_id BIGINT,document_version_no INT,document_sha256 BINARY(32),mapping_id BIGINT,mapping_version INT,mapping_sha256 BINARY(32),validation_fingerprint BINARY(32),source_row_count BIGINT,physical_row_count BIGINT,empty_row_count BIGINT,accepted_row_count BIGINT,rejected_row_count BIGINT,finding_summary_bytes VARBINARY(8192),finding_summary_sha256 BINARY(32),expected_unit_price_accepted_known_count BIGINT,expected_unit_price_accepted_sum_yen DECIMAL(38,0),expected_unit_price_rejected_known_count BIGINT,expected_unit_price_rejected_sum_yen DECIMAL(38,0),expected_unit_price_excluded_count BIGINT,unit_price_min_accepted_known_count BIGINT,unit_price_min_accepted_sum_yen DECIMAL(38,0),unit_price_min_rejected_known_count BIGINT,unit_price_min_rejected_sum_yen DECIMAL(38,0),unit_price_min_excluded_count BIGINT,unit_price_max_accepted_known_count BIGINT,unit_price_max_accepted_sum_yen DECIMAL(38,0),unit_price_max_rejected_known_count BIGINT,unit_price_max_rejected_sum_yen DECIMAL(38,0),unit_price_max_excluded_count BIGINT,selling_price_accepted_known_count BIGINT,selling_price_accepted_sum_yen DECIMAL(38,0),selling_price_rejected_known_count BIGINT,selling_price_rejected_sum_yen DECIMAL(38,0),selling_price_excluded_count BIGINT,cost_price_accepted_known_count BIGINT,cost_price_accepted_sum_yen DECIMAL(38,0),cost_price_rejected_known_count BIGINT,cost_price_rejected_sum_yen DECIMAL(38,0),cost_price_excluded_count BIGINT,amount_excluded_reason_counts VARBINARY(8192),amount_excluded_reason_sha256 BINARY(32),row_set_sha256 BINARY(32),summary_sha256 BINARY(32),started_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,sealed_at TIMESTAMP(6) NULL,completed_at TIMESTAMP(6) NULL,UNIQUE(tenant_id,id),UNIQUE(tenant_id,job_id,id),UNIQUE(tenant_id,job_id,run_no),UNIQUE(tenant_id,job_id,active_slot),UNIQUE(tenant_id,job_id,published_slot))");
        j.execute("CREATE TABLE t_import_validation_row(tenant_id VARCHAR(100),job_id BIGINT,validation_run_id BIGINT,source_row_number BIGINT,source_row_sha256 BINARY(32),canonical_fingerprint BINARY(32),disposition VARCHAR(16),finding_codes VARBINARY(4096),row_commit_sha256 BINARY(32),PRIMARY KEY(tenant_id,validation_run_id,source_row_number))");
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] hash(byte[] value) {
        return digest().digest(value);
    }
}
