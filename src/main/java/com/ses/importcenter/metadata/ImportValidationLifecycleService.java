package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.service.DocumentService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** F1b-1: run開始、run-aware lease、abortのtransaction境界。row append以降は扱わない。 */
@Service
public class ImportValidationLifecycleService {
    private static final int LEASE_SECONDS = 30;
    private static final String PARSER_CONTRACT = "ses-csv-v1-stream-limits-64m-100k-64-65536";
    private static final String CANONICAL_CONTRACT = "ses-import-canonical-row-v1";
    private static final String VALIDATOR_CONTRACT = "ses-import-v1-readonly-validator-v1";
    private static final String RESOLVER_MODE = "synthetic-fail-closed-resolver-v1";
    private static final String FINDING_CONTRACT = "ses-import-finding-code-v1";
    private final JdbcTemplate jdbc;
    private final ImportMetadataAuthorizationService authorization;
    private final DocumentService documents;
    private final TransactionTemplate transaction;
    private final String instanceId = UUID.randomUUID().toString();

    public ImportValidationLifecycleService(JdbcTemplate jdbc, ImportMetadataAuthorizationService authorization,
                                            DocumentService documents, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.documents = documents;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** operation/worker identityはserver発行の不透明値。呼出元はgenerationやactorを指定できない。 */
    public StartOperation beginStart(long jobId) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        Input input = readInput(context, jobId);
        if (!eligibleStart(input)) conflict();
        String operation = UUID.randomUUID().toString();
        return new StartOperation(instanceId, operation, operation + ":" + UUID.randomUUID(), input);
    }

    public RunLease start(StartOperation operation) {
        requireInstance(operation == null ? null : operation.instanceId);
        return transaction.execute(status -> startInTransaction(operation));
    }

    public TakeoverOperation beginTakeover(long jobId, long runId) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        Input input = readInput(context, jobId);
        if (!"VALIDATING".equals(input.state) || !Objects.equals(input.activeRunId, runId)
                || input.owner == null || input.expiresAt == null) conflict();
        String operation = UUID.randomUUID().toString();
        return new TakeoverOperation(instanceId, operation, operation + ":" + UUID.randomUUID(), input, runId);
    }

    public RunLease takeOver(TakeoverOperation operation) {
        requireInstance(operation == null ? null : operation.instanceId);
        return transaction.execute(status -> takeOverInTransaction(operation));
    }

    public RunLease renew(RunLease lease) {
        requireInstance(lease == null ? null : lease.instanceId);
        return transaction.execute(status -> renewInTransaction(lease));
    }

    public void abort(RunLease lease) {
        requireInstance(lease == null ? null : lease.instanceId);
        transaction.executeWithoutResult(status -> abortInTransaction(lease));
    }

    AppendSnapshot appendSnapshot(RunLease lease) {
        requireInstance(lease == null ? null : lease.instanceId);
        Input in = lease.input;
        return new AppendSnapshot(in.context, in.jobId, in.sourceId, in.sourceVersion, in.sourceCode, in.entity,
                in.schema, in.encoding, in.sourceSha, in.documentId, in.documentVersionId, in.documentVersionNo,
                in.documentSha, in.mappingId, in.mappingVersion, in.mappingSha, lease.runId, lease.workerId,
                lease.generation, lease.jobVersion, lease.expiresAt, fingerprint(in));
    }

    void appendChunk(RunLease lease, ImportValidationAppendService.ValidatedChunk chunk) {
        requireInstance(lease == null ? null : lease.instanceId);
        transaction.executeWithoutResult(status -> appendChunkInTransaction(lease, chunk));
    }

    private void appendChunkInTransaction(RunLease grant, ImportValidationAppendService.ValidatedChunk chunk) {
        AppendSnapshot expected = appendSnapshot(grant);
        if (chunk == null || chunk.lease() != grant || !chunk.matches(expected)
                || chunk.rows().isEmpty() || chunk.rows().size() > 250) conflict();
        Input input = grant.input;
        current(input.context);
        Locked locked = lockResources(input, grant.runId);
        Job job = locked.job;
        Run run = locked.run;
        long previous = 0;
        for (ImportValidationAppendService.RowMetadata row : chunk.rows()) {
            if (row.sourceRowNumber() <= previous) conflict();
            previous = row.sourceRowNumber();
        }
        int found = 0;
        for (ImportValidationAppendService.RowMetadata row : chunk.rows()) {
            ImportValidationAppendService.RowMetadata existing = jdbc.query("""
                    SELECT source_row_number,source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256
                    FROM t_import_validation_row WHERE tenant_id=? AND validation_run_id=? AND source_row_number=?
                    FOR UPDATE
                    """, rs -> rs.next() ? new ImportValidationAppendService.RowMetadata(rs.getLong(1), rs.getBytes(2),
                    rs.getBytes(3), rs.getString(4), rs.getBytes(5), rs.getBytes(6)) : null,
                    input.context.tenantId(), grant.runId, row.sourceRowNumber());
            if (existing != null) {
                if (!row.sameMetadata(existing)) conflict();
                found++;
            }
        }
        if (found != chunk.rows().size() && found != 0) conflict();
        Long maxExisting = jdbc.queryForObject("SELECT MAX(source_row_number) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Long.class, input.context.tenantId(), job.id, grant.runId);
        if (found == 0 && maxExisting != null && chunk.rows().get(0).sourceRowNumber() <= maxExisting) conflict();

        // row lock待機の後で権限とDB時刻を再確認し、待機中の撤権・期限切れを拒否する。
        ImportAuthorizedContext gate = authorization.requireCurrentAuthorizationForWrite(input.context);
        requireSameActor(input.context, gate);
        Timestamp now = dbNow();
        if (!activeLease(job, run, grant) || job.version != grant.jobVersion
                || job.expiresAt == null || !job.expiresAt.equals(grant.expiresAt)
                || !job.expiresAt.after(now) || !"BUILDING".equals(run.state)
                || !MessageDigest.isEqual(run.fingerprint, expected.validatorFingerprint())) conflict();
        if (found == chunk.rows().size()) return;

        final int[][] inserted;
        try {
            inserted = jdbc.batchUpdate("""
                    INSERT INTO t_import_validation_row(tenant_id,job_id,validation_run_id,source_row_number,
                      source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """, chunk.rows(), chunk.rows().size(), (ps, row) -> {
                int i = 1;
                ps.setString(i++, input.context.tenantId()); ps.setLong(i++, job.id); ps.setLong(i++, grant.runId);
                ps.setLong(i++, row.sourceRowNumber()); ps.setBytes(i++, row.sourceRowSha256());
                ps.setBytes(i++, row.canonicalFingerprint()); ps.setString(i++, row.disposition());
                ps.setBytes(i++, row.findingCodes()); ps.setBytes(i, row.rowCommitSha256());
            });
        } catch (DuplicateKeyException uniquenessConflict) {
            conflict();
            return;
        }
        int insertedCount = java.util.Arrays.stream(inserted).mapToInt(batch -> batch.length).sum();
        if (insertedCount != chunk.rows().size()
                || java.util.Arrays.stream(inserted).flatMapToInt(java.util.Arrays::stream)
                .anyMatch(count -> count != 1 && count != Statement.SUCCESS_NO_INFO)) conflict();
    }

    private RunLease startInTransaction(StartOperation op) {
        ImportAuthorizedContext context = current(op.input.context);
        Locked locked = lockResources(op.input, null);
        ImportAuthorizedContext gate = authorization.requireCurrentAuthorizationForWrite(op.input.context);
        requireSameActor(op.input.context, gate);
        Timestamp now = dbNow();

        Job job = locked.job;
        Run run = locked.run;
        long expectedGeneration = op.input.generation + 1;
        if (isStartRetry(op, job, run, expectedGeneration)) {
            if (!job.expiresAt.after(now)) conflict();
            return lease(op.input, job.activeRunId, op.workerId, expectedGeneration, job.version, job.expiresAt);
        }
        requireExpectedInput(op.input, job);
        if (!eligibleStart(job) || job.version != op.input.version || job.generation != op.input.generation
                || !job.state.equals(op.input.state) || !Objects.equals(job.owner, op.input.owner)
                || !Objects.equals(job.expiresAt, op.input.expiresAt)
                || !Objects.equals(job.currentRunId, op.input.currentRunId)
                || (job.owner != null && (job.expiresAt == null || job.expiresAt.after(now)))) conflict();
        if (job.owner == null && job.expiresAt != null) conflict();
        if (job.currentRunId != null) {
            if (run == null || run.id != job.currentRunId || !"COMPLETED".equals(run.state)
                    || !Integer.valueOf(1).equals(run.publishedSlot) || !sameRunSnapshot(op.input, run)) conflict();
            if (jdbc.update("UPDATE t_import_validation_run SET published_slot=NULL WHERE tenant_id=? AND job_id=? AND id=? AND state='COMPLETED' AND published_slot=1",
                    context.tenantId(), job.id, run.id) != 1) conflict();
        } else if (run != null) {
            conflict();
        }

        int runNo = jdbc.queryForObject("SELECT COALESCE(MAX(run_no),0)+1 FROM t_import_validation_run WHERE tenant_id=? AND job_id=?",
                Integer.class, context.tenantId(), job.id);
        byte[] validatorFingerprint = fingerprint(op.input);
        long runId = insertRun(context, op.input, runNo, expectedGeneration, validatorFingerprint);
        String currentRunPredicate = job.currentRunId == null
                ? " AND current_validation_run_id IS NULL"
                : " AND current_validation_run_id=?";
        String updateSql = """
                UPDATE t_import_job SET state='VALIDATING',lease_owner=?,lease_generation=?,
                  lease_expires_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),active_validation_run_id=?,
                  current_validation_run_id=NULL,version_no=version_no+1,updated_at=CURRENT_TIMESTAMP(6)
                WHERE tenant_id=? AND id=? AND version_no=? AND lease_generation=? AND state=?
                  AND active_validation_run_id IS NULL
                """ + currentRunPredicate + """
                  AND ((lease_owner IS NULL AND lease_expires_at IS NULL)
                    OR (lease_owner=? AND lease_expires_at=? AND lease_expires_at<=CURRENT_TIMESTAMP(6)))
                """;
        int updated = job.currentRunId == null
                ? jdbc.update(updateSql, op.workerId, expectedGeneration, LEASE_SECONDS, runId, context.tenantId(), job.id,
                    job.version, job.generation, job.state, job.owner, job.expiresAt)
                : jdbc.update(updateSql, op.workerId, expectedGeneration, LEASE_SECONDS, runId, context.tenantId(), job.id,
                    job.version, job.generation, job.state, job.currentRunId, job.owner, job.expiresAt);
        if (updated != 1) conflict();
        Timestamp expiresAt = jdbc.queryForObject("SELECT lease_expires_at FROM t_import_job WHERE tenant_id=? AND id=?",
                Timestamp.class, context.tenantId(), job.id);
        return lease(op.input, runId, op.workerId, expectedGeneration, job.version + 1,
                expiresAt);
    }

    private RunLease takeOverInTransaction(TakeoverOperation op) {
        Input expected = op.input;
        current(expected.context);
        Locked locked = lockResources(expected, op.runId);
        ImportAuthorizedContext gate = authorization.requireCurrentAuthorizationForWrite(expected.context);
        requireSameActor(expected.context, gate);
        Timestamp now = dbNow();
        Job job = locked.job;
        Run run = locked.run;
        long generation = expected.generation + 1;
        if (isTakeoverRetry(op, job, run, generation)) {
            if (!job.expiresAt.after(now)) conflict();
            return lease(expected, run.id, op.workerId, generation, job.version, job.expiresAt);
        }
        requireExpectedInput(expected, job);
        if (!"VALIDATING".equals(job.state) || !Objects.equals(job.activeRunId, op.runId)
                || job.currentRunId != null || job.owner == null || job.expiresAt == null
                || !job.owner.equals(expected.owner) || job.generation != expected.generation
                || job.version != expected.version || job.expiresAt.after(now)
                || run == null || run.id != op.runId || !activeRun(run)
                || run.lastResumedGeneration != expected.generation || !sameRunInput(expected, run)) conflict();
        int changedJob = jdbc.update("""
                UPDATE t_import_job SET lease_owner=?,lease_generation=?,
                  lease_expires_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),version_no=version_no+1,
                  updated_at=CURRENT_TIMESTAMP(6)
                WHERE tenant_id=? AND id=? AND version_no=? AND state='VALIDATING'
                  AND active_validation_run_id=? AND current_validation_run_id IS NULL
                  AND lease_owner=? AND lease_generation=? AND lease_expires_at=?
                  AND lease_expires_at<=CURRENT_TIMESTAMP(6)
                """, op.workerId, generation, LEASE_SECONDS, expected.context.tenantId(), job.id,
                job.version, op.runId, expected.owner, expected.generation, expected.expiresAt);
        int changedRun = jdbc.update("""
                UPDATE t_import_validation_run SET last_resumed_lease_generation=?
                WHERE tenant_id=? AND job_id=? AND id=? AND state IN ('BUILDING','SEALED')
                  AND active_slot=1 AND last_resumed_lease_generation=?
                """, generation, expected.context.tenantId(), job.id, op.runId, run.lastResumedGeneration);
        if (changedJob != 1 || changedRun != 1) conflict();
        Timestamp expiresAt = jdbc.queryForObject("SELECT lease_expires_at FROM t_import_job WHERE tenant_id=? AND id=?",
                Timestamp.class, expected.context.tenantId(), job.id);
        return lease(expected, op.runId, op.workerId, generation, job.version + 1,
                expiresAt);
    }

    private RunLease renewInTransaction(RunLease grant) {
        Input expected = grant.input;
        current(expected.context);
        Locked locked = lockResources(expected, grant.runId);
        ImportAuthorizedContext gate = authorization.requireCurrentAuthorizationForWrite(expected.context);
        requireSameActor(expected.context, gate);
        Timestamp now = dbNow();
        Job job = locked.job;
        Run run = locked.run;
        if (!activeLease(job, run, grant) || job.expiresAt == null || !job.expiresAt.after(now)) conflict();
        if (job.version > grant.jobVersion && !job.expiresAt.before(grant.expiresAt)) {
            return lease(expected, grant.runId, grant.workerId, grant.generation, job.version, job.expiresAt);
        }
        if (job.version != grant.jobVersion || !job.expiresAt.equals(grant.expiresAt)) conflict();
        int updated = jdbc.update("""
                UPDATE t_import_job SET lease_expires_at=GREATEST(
                  lease_expires_at,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))),
                  version_no=version_no+1,updated_at=CURRENT_TIMESTAMP(6)
                WHERE tenant_id=? AND id=? AND version_no=? AND state='VALIDATING'
                  AND active_validation_run_id=? AND lease_owner=? AND lease_generation=?
                  AND lease_expires_at=? AND lease_expires_at>CURRENT_TIMESTAMP(6)
                """, LEASE_SECONDS, expected.context.tenantId(), job.id, job.version, grant.runId,
                grant.workerId, grant.generation, grant.expiresAt);
        if (updated != 1) conflict();
        Timestamp newExpiry = jdbc.queryForObject("SELECT lease_expires_at FROM t_import_job WHERE tenant_id=? AND id=?",
                Timestamp.class, expected.context.tenantId(), job.id);
        return lease(expected, grant.runId, grant.workerId, grant.generation, job.version + 1, newExpiry);
    }

    private void abortInTransaction(RunLease grant) {
        Input expected = grant.input;
        current(expected.context);
        Locked locked = lockResources(expected, grant.runId);
        ImportAuthorizedContext gate = authorization.requireCurrentAuthorizationForWrite(expected.context);
        requireSameActor(expected.context, gate);
        Timestamp now = dbNow();
        Job job = locked.job;
        Run run = locked.run;
        if (isAbortRetry(job, run, grant)) return;
        if (!activeLease(job, run, grant) || job.version != grant.jobVersion
                || job.expiresAt == null || !job.expiresAt.after(now)
                || !"BUILDING".equals(run.state) && !"SEALED".equals(run.state)) conflict();
        int changedRun = jdbc.update("""
                UPDATE t_import_validation_run SET state='ABORTED',active_slot=NULL
                WHERE tenant_id=? AND job_id=? AND id=? AND state IN ('BUILDING','SEALED') AND active_slot=1
                """, expected.context.tenantId(), job.id, grant.runId);
        int changedJob = jdbc.update("""
                UPDATE t_import_job SET state='MAPPED',active_validation_run_id=NULL,
                  lease_owner=NULL,lease_expires_at=NULL,version_no=version_no+1,updated_at=CURRENT_TIMESTAMP(6)
                WHERE tenant_id=? AND id=? AND version_no=? AND state='VALIDATING'
                  AND active_validation_run_id=? AND current_validation_run_id IS NULL
                  AND lease_owner=? AND lease_generation=? AND lease_expires_at=?
                  AND lease_expires_at>CURRENT_TIMESTAMP(6)
                """, expected.context.tenantId(), job.id, job.version, grant.runId,
                grant.workerId, grant.generation, grant.expiresAt);
        if (changedRun != 1 || changedJob != 1) conflict();
    }

    private Locked lockResources(Input expected, Long runId) {
        ImportAuthorizedContext context = expected.context;
        current(context);
        Source source = jdbc.query("SELECT version_no,status,source_code FROM t_import_source_system WHERE tenant_id=? AND id=? FOR UPDATE",
                rs -> rs.next() ? new Source(rs.getLong(1), rs.getString(2), rs.getString(3)) : null,
                context.tenantId(), expected.sourceId);
        if (source == null || source.version != expected.sourceVersion || !"ACTIVE".equals(source.status)
                || !source.code.equals(expected.sourceCode)) conflict();
        var document = jdbc.query("SELECT legal_entity_id,legal_hold_flag,retention_until,deleted_flag FROM t_document WHERE tenant_id=? AND id=? FOR UPDATE",
                rs -> rs.next() ? new Object[]{rs.getString(1), rs.getInt(2), rs.getDate(3), rs.getInt(4)} : null,
                context.tenantId(), expected.documentId);
        LocalDate today = jdbc.queryForObject("SELECT CURRENT_DATE", LocalDate.class);
        if (document == null || !Long.toString(context.legalEntityId()).equals(document[0])
                || (Integer) document[1] != 0 || (Integer) document[3] != 0
                || (document[2] != null && ((java.sql.Date) document[2]).toLocalDate().isBefore(today))) conflict();
        var version = jdbc.query("SELECT version_no,sha256,scan_status,deleted_flag FROM t_document_version WHERE tenant_id=? AND document_id=? AND id=? FOR UPDATE",
                rs -> rs.next() ? new Object[]{rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)} : null,
                context.tenantId(), expected.documentId, expected.documentVersionId);
        if (version == null || (Integer) version[0] != expected.documentVersionNo
                || !"CLEAN".equals(version[2]) || (Integer) version[3] != 0
                || !HexFormat.of().formatHex(expected.documentSha).equalsIgnoreCase((String) version[1])) conflict();
        var links = jdbc.query("SELECT id FROM t_document_link WHERE tenant_id=? AND document_id=? AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0 ORDER BY id FOR UPDATE",
                (rs, row) -> rs.getLong(1), context.tenantId(), expected.documentId, expected.jobId);
        if (links.size() != 1) conflict();
        documents.authorizeImportJobDownload(expected.jobId, expected.documentId, expected.documentVersionId,
                expected.documentVersionNo, HexFormat.of().formatHex(expected.documentSha));

        Job job = loadJob(context.tenantId(), expected.jobId, true);
        requireExpectedInput(expected, job);
        Mapping mapping = jdbc.query("SELECT source_system_id,entity_type,schema_version,mapping_version,mapping_sha256,canonical_bytes FROM t_import_mapping WHERE tenant_id=? AND id=? FOR UPDATE",
                rs -> rs.next() ? new Mapping(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getBytes(5), rs.getBytes(6)) : null, context.tenantId(), expected.mappingId);
        if (mapping == null || mapping.sourceId != expected.sourceId || !mapping.entity.equals(expected.entity)
                || !mapping.schema.equals(expected.schema) || mapping.version != expected.mappingVersion
                || !MessageDigest.isEqual(mapping.sha, expected.mappingSha)
                || !MessageDigest.isEqual(sha256(mapping.canonicalBytes), mapping.sha)) conflict();
        Long targetRun = runId != null ? runId : (job.activeRunId != null ? job.activeRunId : job.currentRunId);
        Run run = targetRun == null ? null : loadRun(context.tenantId(), expected.jobId, targetRun);
        return new Locked(job, run);
    }

    private Input readInput(ImportAuthorizedContext context, long jobId) {
        return jdbc.query("""
                SELECT j.source_system_id,j.source_version,s.source_code,s.version_no,s.status,j.entity_type,
                  j.schema_version,j.source_encoding,j.source_sha256,j.legal_entity_id,j.document_id,
                  j.document_version_id,j.document_version_no,j.document_sha256,j.mapping_id,j.mapping_version,
                  j.mapping_sha256,j.state,j.version_no,j.lease_owner,j.lease_generation,j.lease_expires_at,
                  j.active_validation_run_id,j.current_validation_run_id
                FROM t_import_job j JOIN t_import_source_system s
                  ON s.tenant_id=j.tenant_id AND s.id=j.source_system_id
                WHERE j.tenant_id=? AND j.id=?
                """, rs -> {
            if (!rs.next()) throw BusinessException.of(404, "error.import.jobNotFound");
            Input input = new Input(context, jobId, rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBytes(9),
                    rs.getString(10), rs.getLong(11), rs.getLong(12), rs.getInt(13), rs.getBytes(14),
                    rs.getObject(15, Long.class), rs.getObject(16, Integer.class), rs.getBytes(17),
                    rs.getString(18), rs.getLong(19), rs.getString(20), rs.getLong(21), rs.getTimestamp(22),
                    rs.getObject(23, Long.class), rs.getObject(24, Long.class));
            if (input.mappingId == null || input.mappingVersion == null || input.mappingSha == null
                    || input.sourceSha == null || input.documentSha == null
                    || input.sourceVersion != input.currentSourceVersion
                    || !"ACTIVE".equals(input.sourceStatus)
                    || !Long.toString(context.legalEntityId()).equals(input.legalEntity)) conflict();
            return input;
        }, context.tenantId(), jobId);
    }

    private Job loadJob(String tenant, long id, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("SELECT source_system_id,source_version,entity_type,schema_version,source_encoding,source_sha256,legal_entity_id,document_id,document_version_id,document_version_no,document_sha256,mapping_id,mapping_version,mapping_sha256,state,version_no,lease_owner,lease_generation,lease_expires_at,active_validation_run_id,current_validation_run_id FROM t_import_job WHERE tenant_id=? AND id=?" + suffix,
                rs -> rs.next() ? new Job(id, rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBytes(6), rs.getString(7), rs.getLong(8), rs.getLong(9), rs.getInt(10),
                        rs.getBytes(11), rs.getObject(12, Long.class), rs.getObject(13, Integer.class), rs.getBytes(14),
                        rs.getString(15), rs.getLong(16), rs.getString(17), rs.getLong(18), rs.getTimestamp(19),
                        rs.getObject(20, Long.class), rs.getObject(21, Long.class)) : null, tenant, id);
    }

    private Run loadRun(String tenant, long jobId, long runId) {
        return jdbc.query("SELECT id,run_no,state,created_lease_generation,last_resumed_lease_generation,active_slot,published_slot,actor_user_id,source_system_id,source_version,source_code_snapshot,entity_type,schema_version,source_encoding,source_sha256,document_id,document_version_id,document_version_no,document_sha256,mapping_id,mapping_version,mapping_sha256,validation_fingerprint FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=? FOR UPDATE",
                rs -> rs.next() ? new Run(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                        rs.getObject(6, Integer.class), rs.getObject(7, Integer.class), rs.getLong(8), rs.getLong(9),
                        rs.getLong(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
                        rs.getBytes(15), rs.getLong(16), rs.getLong(17), rs.getInt(18), rs.getBytes(19),
                        rs.getLong(20), rs.getInt(21), rs.getBytes(22), rs.getBytes(23)) : null,
                tenant, jobId, runId);
    }

    private long insertRun(ImportAuthorizedContext context, Input in, int runNo, long generation, byte[] fingerprint) {
        String sql = "INSERT INTO t_import_validation_run (tenant_id,job_id,run_no,state,created_lease_generation,last_resumed_lease_generation,active_slot,published_slot,actor_user_id,source_system_id,source_version,source_code_snapshot,entity_type,schema_version,source_encoding,source_sha256,document_id,document_version_id,document_version_no,document_sha256,mapping_id,mapping_version,mapping_sha256,validation_fingerprint) VALUES (?,?,?,'BUILDING',?,?,1,NULL,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            int i = 1;
            ps.setString(i++, context.tenantId()); ps.setLong(i++, in.jobId); ps.setInt(i++, runNo);
            ps.setLong(i++, generation); ps.setLong(i++, generation); ps.setLong(i++, context.actorUserId());
            ps.setLong(i++, in.sourceId); ps.setLong(i++, in.sourceVersion); ps.setString(i++, in.sourceCode);
            ps.setString(i++, in.entity); ps.setString(i++, in.schema); ps.setString(i++, in.encoding);
            ps.setBytes(i++, in.sourceSha); ps.setLong(i++, in.documentId); ps.setLong(i++, in.documentVersionId);
            ps.setInt(i++, in.documentVersionNo); ps.setBytes(i++, in.documentSha); ps.setLong(i++, in.mappingId);
            ps.setInt(i++, in.mappingVersion); ps.setBytes(i++, in.mappingSha); ps.setBytes(i, fingerprint);
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) conflict();
        return id.longValue();
    }

    private boolean isStartRetry(StartOperation op, Job job, Run run, long generation) {
        return "VALIDATING".equals(job.state) && job.activeRunId != null && job.currentRunId == null
                && op.workerId.equals(job.owner) && job.generation == generation && job.expiresAt != null
                && job.version > op.input.version && run != null
                && run.id == job.activeRunId && "BUILDING".equals(run.state)
                && run.createdGeneration == generation && run.lastResumedGeneration == generation
                && run.actorId == op.input.context.actorUserId() && sameRunInput(op.input, run)
                && MessageDigest.isEqual(run.fingerprint, fingerprint(op.input));
    }

    private boolean isTakeoverRetry(TakeoverOperation op, Job job, Run run, long generation) {
        return activeRun(run) && run.id == op.runId && job.activeRunId != null && job.activeRunId == op.runId
                && job.currentRunId == null && "VALIDATING".equals(job.state) && op.workerId.equals(job.owner)
                && job.generation == generation && run.lastResumedGeneration == generation
                && job.expiresAt != null && sameRunInput(op.input, run)
                && job.version > op.input.version;
    }

    private boolean isAbortRetry(Job job, Run run, RunLease grant) {
        return job != null && "MAPPED".equals(job.state) && job.activeRunId == null && job.currentRunId == null
                && job.owner == null && job.expiresAt == null && job.generation == grant.generation
                && job.version > grant.jobVersion && run != null && run.id == grant.runId
                && "ABORTED".equals(run.state) && run.activeSlot == null && run.publishedSlot == null
                && run.lastResumedGeneration == grant.generation && sameRunInput(grant.input, run);
    }

    private boolean activeLease(Job job, Run run, RunLease grant) {
        return job != null && run != null && "VALIDATING".equals(job.state)
                && Objects.equals(job.activeRunId, grant.runId) && job.currentRunId == null
                && grant.workerId.equals(job.owner) && job.generation == grant.generation
                && run.id == grant.runId && activeRun(run) && run.lastResumedGeneration == grant.generation
                && sameRunInput(grant.input, run);
    }

    private boolean activeRun(Run run) {
        return run != null && ("BUILDING".equals(run.state) || "SEALED".equals(run.state))
                && Integer.valueOf(1).equals(run.activeSlot) && run.publishedSlot == null;
    }

    private boolean sameRunInput(Input in, Run run) {
        return sameRunSnapshot(in, run) && MessageDigest.isEqual(run.fingerprint, fingerprint(in));
    }

    private boolean sameRunSnapshot(Input in, Run run) {
        return run.sourceId == in.sourceId && run.sourceVersion == in.sourceVersion
                && run.sourceCode.equals(in.sourceCode) && run.entity.equals(in.entity) && run.schema.equals(in.schema)
                && run.encoding.equals(in.encoding) && MessageDigest.isEqual(run.sourceSha, in.sourceSha)
                && run.documentId == in.documentId && run.documentVersionId == in.documentVersionId
                && run.documentVersionNo == in.documentVersionNo && MessageDigest.isEqual(run.documentSha, in.documentSha)
                && Objects.equals(run.mappingId, in.mappingId) && Objects.equals(run.mappingVersion, in.mappingVersion)
                && MessageDigest.isEqual(run.mappingSha, in.mappingSha);
    }

    private void requireExpectedInput(Input expected, Job actual) {
        if (actual == null || actual.id != expected.jobId || actual.sourceId != expected.sourceId
                || actual.sourceVersion != expected.sourceVersion || !actual.entity.equals(expected.entity)
                || !actual.schema.equals(expected.schema) || !actual.encoding.equals(expected.encoding)
                || !MessageDigest.isEqual(actual.sourceSha, expected.sourceSha)
                || !actual.legalEntity.equals(expected.legalEntity) || actual.documentId != expected.documentId
                || actual.documentVersionId != expected.documentVersionId
                || actual.documentVersionNo != expected.documentVersionNo
                || !MessageDigest.isEqual(actual.documentSha, expected.documentSha)
                || !Objects.equals(actual.mappingId, expected.mappingId)
                || !Objects.equals(actual.mappingVersion, expected.mappingVersion)
                || !MessageDigest.isEqual(actual.mappingSha, expected.mappingSha)) conflict();
    }

    private boolean eligibleStart(Input in) {
        return in != null && ("MAPPED".equals(in.state) || "VALIDATED".equals(in.state))
                && in.activeRunId == null
                && ("MAPPED".equals(in.state) ? in.currentRunId == null : in.currentRunId != null)
                && in.mappingId != null && in.mappingVersion != null
                && in.mappingSha != null && in.sourceSha != null && in.documentSha != null;
    }

    private boolean eligibleStart(Job job) {
        return ("MAPPED".equals(job.state) && job.activeRunId == null && job.currentRunId == null)
                || ("VALIDATED".equals(job.state) && job.activeRunId == null && job.currentRunId != null);
    }

    private Timestamp dbNow() {
        Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
        if (now == null) conflict();
        return now;
    }

    private byte[] fingerprint(Input in) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
            writeFingerprintField(out, "SES-IMPORT-VALIDATION-FINGERPRINT-V1");
            out.writeInt(1);
            writeFingerprintField(out, PARSER_CONTRACT);
            writeFingerprintField(out, CANONICAL_CONTRACT);
            writeFingerprintField(out, VALIDATOR_CONTRACT);
            writeFingerprintField(out, RESOLVER_MODE);
            writeFingerprintField(out, FINDING_CONTRACT);
            writeFingerprintField(out, in.entity);
            writeFingerprintField(out, in.schema);
            writeFingerprintField(out, in.encoding);
            writeFingerprintField(out, in.mappingSha);
            out.flush();
            return sha256(bytes.toByteArray());
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeFingerprintField(java.io.DataOutputStream out, String value) throws java.io.IOException {
        writeFingerprintField(out, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void writeFingerprintField(java.io.DataOutputStream out, byte[] value) throws java.io.IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private RunLease lease(Input input, long runId, String worker, long generation, long version, Timestamp expiry) {
        return new RunLease(instanceId, input, runId, worker, generation, version, expiry);
    }

    private ImportAuthorizedContext current(ImportAuthorizedContext expected) {
        ImportAuthorizedContext actual = authorization.requireCurrentAuthorization();
        requireSameActor(expected, actual);
        return actual;
    }

    private void requireSameActor(ImportAuthorizedContext expected, ImportAuthorizedContext actual) {
        if (!expected.equals(actual)) throw BusinessException.of(403, "error.forbidden");
    }

    private void requireInstance(String candidate) {
        if (candidate == null || !instanceId.equals(candidate)) conflict();
    }

    private void conflict() { throw BusinessException.of(409, "error.import.jobStateConflict"); }

    public static final class StartOperation {
        private final String instanceId, operationId, workerId;
        private final Input input;
        private StartOperation(String instanceId, String operationId, String workerId, Input input) {
            this.instanceId = instanceId; this.operationId = operationId; this.workerId = workerId; this.input = input;
        }
    }

    public static final class TakeoverOperation {
        private final String instanceId, operationId, workerId;
        private final Input input;
        private final long runId;
        private TakeoverOperation(String instanceId, String operationId, String workerId, Input input, long runId) {
            this.instanceId = instanceId; this.operationId = operationId; this.workerId = workerId;
            this.input = input; this.runId = runId;
        }
    }

    public static final class RunLease {
        private final String instanceId, workerId;
        private final Input input;
        private final long runId, generation, jobVersion;
        private final Timestamp expiresAt;
        private RunLease(String instanceId, Input input, long runId, String workerId, long generation,
                         long jobVersion, Timestamp expiresAt) {
            this.instanceId = instanceId; this.input = input; this.runId = runId; this.workerId = workerId;
            this.generation = generation; this.jobVersion = jobVersion;
            this.expiresAt = expiresAt == null ? null : (Timestamp) expiresAt.clone();
        }
        public long runId() { return runId; }
        public long generation() { return generation; }
        public Timestamp expiresAt() { return expiresAt == null ? null : (Timestamp) expiresAt.clone(); }
    }

    record AppendSnapshot(ImportAuthorizedContext context, long jobId, long sourceId, long sourceVersion,
                          String sourceCode, String entity, String schema, String encoding, byte[] sourceSha,
                          long documentId, long documentVersionId, int documentVersionNo, byte[] documentSha,
                          Long mappingId, int mappingVersion, byte[] mappingSha, long runId, String workerId,
                          long generation, long jobVersion, Timestamp expiresAt, byte[] validatorFingerprint) {
        AppendSnapshot {
            sourceSha = sourceSha.clone(); documentSha = documentSha.clone(); mappingSha = mappingSha.clone();
            validatorFingerprint = validatorFingerprint.clone();
            expiresAt = expiresAt == null ? null : (Timestamp) expiresAt.clone();
        }
        @Override public byte[] sourceSha() { return sourceSha.clone(); }
        @Override public byte[] documentSha() { return documentSha.clone(); }
        @Override public byte[] mappingSha() { return mappingSha.clone(); }
        @Override public byte[] validatorFingerprint() { return validatorFingerprint.clone(); }
        @Override public Timestamp expiresAt() { return expiresAt == null ? null : (Timestamp) expiresAt.clone(); }
    }

    private record Source(long version, String status, String code) { }
    private record Mapping(long sourceId, String entity, String schema, int version, byte[] sha, byte[] canonicalBytes) { }
    private record Locked(Job job, Run run) { }
    private record Run(long id, int runNo, String state, long createdGeneration, long lastResumedGeneration,
                       Integer activeSlot, Integer publishedSlot, long actorId, long sourceId, long sourceVersion,
                       String sourceCode, String entity, String schema, String encoding, byte[] sourceSha,
                       long documentId, long documentVersionId, int documentVersionNo, byte[] documentSha,
                       Long mappingId, int mappingVersion, byte[] mappingSha, byte[] fingerprint) { }
    private record Job(long id, long sourceId, long sourceVersion, String entity, String schema, String encoding,
                       byte[] sourceSha, String legalEntity, long documentId, long documentVersionId,
                       int documentVersionNo, byte[] documentSha, Long mappingId, Integer mappingVersion,
                       byte[] mappingSha, String state, long version, String owner, long generation,
                       Timestamp expiresAt, Long activeRunId, Long currentRunId) { }
    private record Input(ImportAuthorizedContext context, long jobId, long sourceId, long sourceVersion,
                         String sourceCode, long currentSourceVersion, String sourceStatus, String entity,
                         String schema, String encoding, byte[] sourceSha, String legalEntity, long documentId,
                         long documentVersionId, int documentVersionNo, byte[] documentSha, Long mappingId,
                         Integer mappingVersion, byte[] mappingSha, String state, long version, String owner,
                         long generation, Timestamp expiresAt, Long activeRunId, Long currentRunId) { }
}
