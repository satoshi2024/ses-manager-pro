package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.service.DocumentService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** MAPPED jobだけを対象とする、内部validation lease境界。 */
@Service
public class ImportValidationLeaseService {
    private static final int LEASE_SECONDS = 30;
    private final JdbcTemplate jdbc;
    private final ImportMetadataAuthorizationService authorization;
    private final DocumentService documents;
    private final TransactionTemplate transaction;
    private final String serviceInstanceId = UUID.randomUUID().toString();

    public ImportValidationLeaseService(JdbcTemplate jdbc, ImportMetadataAuthorizationService authorization,
                                        DocumentService documents, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.documents = documents;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** response喪失時も同じserver発行operationを渡して再実行する。 */
    public AcquireOperation beginAcquire(long jobId) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        Snapshot snapshot = snapshot(context, jobId);
        if (snapshot.owner() != null || snapshot.expiresAt() != null) conflict();
        return new AcquireOperation(serviceInstanceId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), snapshot);
    }

    /** expired grantからだけtakeover試行を作れる。worker/operation identityはserver発行。 */
    public TakeoverOperation beginTakeover(LeaseGrant expiredGrant) {
        if (expiredGrant == null || !serviceInstanceId.equals(expiredGrant.serviceInstanceId)) conflict();
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        requireActor(expiredGrant.snapshot.context(), context);
        Snapshot current = snapshot(context, expiredGrant.snapshot.jobId());
        if (!sameInput(expiredGrant.snapshot, current) || current.generation() != expiredGrant.generation
                || !expiredGrant.workerId.equals(current.owner()) || current.expiresAt() == null) conflict();
        return new TakeoverOperation(serviceInstanceId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                expiredGrant.snapshot, expiredGrant.workerId, expiredGrant.generation);
    }

    public LeaseGrant acquire(AcquireOperation operation) {
        if (operation == null || !serviceInstanceId.equals(operation.serviceInstanceId)) conflict();
        return transaction.execute(status -> change(operation.snapshot, operation.workerId,
                operation.operationId, Mode.ACQUIRE, null, -1));
    }

    public LeaseGrant takeOver(TakeoverOperation operation) {
        if (operation == null || !serviceInstanceId.equals(operation.serviceInstanceId)) conflict();
        return transaction.execute(status -> change(operation.snapshot, operation.workerId,
                operation.operationId, Mode.TAKEOVER, operation.previousWorkerId, operation.expectedGeneration));
    }

    public LeaseGrant renew(LeaseGrant grant) {
        if (grant == null || !serviceInstanceId.equals(grant.serviceInstanceId)) conflict();
        return transaction.execute(status -> change(grant.snapshot, grant.workerId,
                grant.operationId, Mode.RENEW, grant.workerId, grant.generation));
    }

    private LeaseGrant change(Snapshot expected, String worker, String operationId, Mode mode,
                              String previousOwner, long expectedGeneration) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        requireActor(expected.context(), context);
        Snapshot current = snapshot(context, expected.jobId());
        lockSource(context, expected, current);
        lockDocument(context, expected);
        lockVersion(context, expected);
        lockTypedLink(context, expected);
        Job job = lockJob(context, expected.jobId());
        requireJob(expected, current, job);
        Mapping mapping = lockMapping(context, expected.mappingId());
        requireMapping(expected, mapping);

        ImportAuthorizedContext lockedContext = authorization.requireCurrentAuthorization();
        requireActor(expected.context(), lockedContext);
        java.sql.Timestamp dbNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.sql.Timestamp.class);
        if (dbNow == null) conflict();
        if (mode == Mode.ACQUIRE && worker.equals(job.owner()) && job.generation() == expected.generation() + 1
                && job.expiresAt() != null && job.expiresAt().after(dbNow)
                && sameInput(expected, current)) {
            return new LeaseGrant(serviceInstanceId, expected, worker, operationId, job.generation());
        }
        if (mode == Mode.TAKEOVER && worker.equals(job.owner()) && job.generation() == expectedGeneration + 1
                && job.expiresAt() != null && job.expiresAt().after(dbNow)
                && sameInput(expected, current)) {
            return new LeaseGrant(serviceInstanceId, expected, worker, operationId, job.generation());
        }

        // 認可とDB時刻をCAS直前に再取得する。ここからCASまでは外部I/Oを行わない。
        ImportAuthorizedContext finalContext = authorization.requireCurrentAuthorization();
        requireActor(expected.context(), finalContext);
        java.sql.Timestamp finalNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.sql.Timestamp.class);
        if (finalNow == null) conflict();
        int changed;
        if (mode == Mode.ACQUIRE) {
            if (job.owner() != null || job.expiresAt() != null || job.generation() != expected.generation()) conflict();
            changed = jdbc.update("""
                    UPDATE t_import_job SET lease_owner=?, lease_generation=lease_generation+1,
                      lease_expires_at=TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)), version_no=version_no+1,
                      updated_at=CURRENT_TIMESTAMP(6)
                    WHERE tenant_id=? AND id=? AND version_no=? AND lease_generation=? AND lease_owner IS NULL
                      AND lease_expires_at IS NULL AND state='MAPPED' AND active_validation_run_id IS NULL
                      AND current_validation_run_id IS NULL AND source_system_id=? AND source_version=?
                      AND mapping_id=? AND mapping_version=? AND mapping_sha256=? AND source_sha256=?
                      AND document_id=? AND document_version_id=? AND document_version_no=? AND document_sha256=?
                    """, worker, LEASE_SECONDS, context.tenantId(), expected.jobId(), job.version(),
                    expected.generation(), expected.sourceId(), expected.sourceVersion(), expected.mappingId(),
                    expected.mappingVersion(), expected.mappingSha(), expected.sourceSha(), expected.documentId(),
                    expected.documentVersionId(), expected.documentVersionNo(), expected.documentSha());
        } else if (mode == Mode.TAKEOVER) {
            if (!previousOwner.equals(job.owner()) || job.generation() != expectedGeneration
                    || job.expiresAt() == null || job.expiresAt().after(dbNow)) conflict();
            changed = jdbc.update("""
                    UPDATE t_import_job SET lease_owner=?, lease_generation=lease_generation+1,
                      lease_expires_at=TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)), version_no=version_no+1,
                      updated_at=CURRENT_TIMESTAMP(6)
                    WHERE tenant_id=? AND id=? AND version_no=? AND lease_generation=? AND lease_owner=?
                      AND lease_expires_at<=CURRENT_TIMESTAMP(6) AND state='MAPPED'
                      AND active_validation_run_id IS NULL AND current_validation_run_id IS NULL
                      AND source_system_id=? AND source_version=? AND mapping_id=? AND mapping_version=?
                      AND mapping_sha256=? AND source_sha256=? AND document_id=? AND document_version_id=?
                      AND document_version_no=? AND document_sha256=?
                    """, worker, LEASE_SECONDS, context.tenantId(), expected.jobId(), job.version(),
                    expectedGeneration, previousOwner, expected.sourceId(), expected.sourceVersion(),
                    expected.mappingId(), expected.mappingVersion(), expected.mappingSha(), expected.sourceSha(),
                    expected.documentId(), expected.documentVersionId(), expected.documentVersionNo(), expected.documentSha());
        } else {
            if (!worker.equals(job.owner()) || job.generation() != expectedGeneration
                    || job.expiresAt() == null || !job.expiresAt().after(dbNow)) conflict();
            changed = jdbc.update("""
                    UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)),
                      version_no=version_no+1, updated_at=CURRENT_TIMESTAMP(6)
                    WHERE tenant_id=? AND id=? AND version_no=? AND lease_generation=? AND lease_owner=?
                      AND lease_expires_at>CURRENT_TIMESTAMP(6) AND state='MAPPED'
                      AND active_validation_run_id IS NULL AND current_validation_run_id IS NULL
                      AND source_system_id=? AND source_version=? AND mapping_id=? AND mapping_version=?
                      AND mapping_sha256=? AND source_sha256=? AND document_id=? AND document_version_id=?
                      AND document_version_no=? AND document_sha256=?
                    """, LEASE_SECONDS, context.tenantId(), expected.jobId(), job.version(), expectedGeneration,
                    worker, expected.sourceId(), expected.sourceVersion(), expected.mappingId(), expected.mappingVersion(),
                    expected.mappingSha(), expected.sourceSha(), expected.documentId(), expected.documentVersionId(),
                    expected.documentVersionNo(), expected.documentSha());
        }
        if (changed != 1) conflict();
        if (mode != Mode.ACQUIRE) {
            Long generation = jdbc.query("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?",
                    rs -> rs.next() ? rs.getLong(1) : null, context.tenantId(), expected.jobId());
            if (generation == null || generation != (mode == Mode.TAKEOVER ? expectedGeneration + 1 : expectedGeneration)) conflict();
        }
        return new LeaseGrant(serviceInstanceId, expected, worker, operationId,
                mode == Mode.ACQUIRE ? expected.generation() + 1
                        : mode == Mode.TAKEOVER ? expectedGeneration + 1 : expectedGeneration);
    }

    private Snapshot snapshot(ImportAuthorizedContext context, long jobId) {
        return jdbc.query("""
                SELECT j.source_system_id,j.source_version,j.entity_type,j.schema_version,j.source_encoding,
                       j.source_sha256,j.legal_entity_id,j.document_id,j.document_version_id,j.document_version_no,
                       j.document_sha256,j.mapping_id,j.mapping_version,j.mapping_sha256,j.state,j.version_no,
                       j.lease_owner,j.lease_generation,j.lease_expires_at,j.active_validation_run_id,
                       j.current_validation_run_id,s.version_no,s.status
                FROM t_import_job j JOIN t_import_source_system s
                  ON s.tenant_id=j.tenant_id AND s.id=j.source_system_id
                WHERE j.tenant_id=? AND j.id=?
                """, rs -> {
            if (!rs.next()) throw BusinessException.of(404, "error.import.jobNotFound");
            Snapshot value = new Snapshot(context, jobId, rs.getLong(1), rs.getLong(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getBytes(6), rs.getString(7), rs.getLong(8),
                    rs.getLong(9), rs.getInt(10), rs.getBytes(11), rs.getObject(12, Long.class),
                    rs.getObject(13, Integer.class), rs.getBytes(14), rs.getString(15), rs.getLong(16),
                    rs.getString(17), rs.getLong(18), rs.getTimestamp(19), rs.getObject(20, Long.class),
                    rs.getObject(21, Long.class), rs.getLong(22), rs.getString(23));
            requireEligible(value);
            return value;
        }, context.tenantId(), jobId);
    }

    private void requireEligible(Snapshot s) {
        if (!"MAPPED".equals(s.state()) || s.activeRun() != null || s.currentRun() != null
                || s.mappingId() == null || s.mappingVersion() == null || s.sourceSha() == null
                || s.documentSha() == null || s.mappingSha() == null || s.sourceVersion() != s.currentSourceVersion()
                || !"ACTIVE".equals(s.sourceStatus()) || !Long.toString(s.context().legalEntityId()).equals(s.legalEntity())) conflict();
    }

    private void lockSource(ImportAuthorizedContext c, Snapshot e, Snapshot s) {
        var row = jdbc.query("SELECT version_no,status FROM t_import_source_system WHERE tenant_id=? AND id=? FOR UPDATE",
                rs -> rs.next() ? new Object[]{rs.getLong(1), rs.getString(2)} : null, c.tenantId(), e.sourceId());
        if (row == null || ((Long) row[0]) != e.sourceVersion() || !"ACTIVE".equals(row[1])
                || !sameInput(e, s)) conflict();
    }

    private void lockDocument(ImportAuthorizedContext c, Snapshot s) {
        var d = jdbc.query("SELECT legal_entity_id,legal_hold_flag,retention_until,deleted_flag FROM t_document WHERE tenant_id=? AND id=? FOR UPDATE",
                rs -> rs.next() ? new Object[]{rs.getString(1), rs.getInt(2), rs.getDate(3), rs.getInt(4)} : null,
                c.tenantId(), s.documentId());
        LocalDate today = jdbc.queryForObject("SELECT CURRENT_DATE", LocalDate.class);
        if (d == null || !Long.toString(c.legalEntityId()).equals(d[0]) || (Integer) d[1] != 0
                || (Integer) d[3] != 0 || (d[2] != null && ((java.sql.Date) d[2]).toLocalDate().isBefore(today))) conflict();
    }

    private void lockVersion(ImportAuthorizedContext c, Snapshot s) {
        var v = jdbc.query("SELECT version_no,sha256,scan_status,deleted_flag FROM t_document_version WHERE tenant_id=? AND document_id=? AND id=? FOR UPDATE",
                rs -> rs.next() ? new Object[]{rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)} : null,
                c.tenantId(), s.documentId(), s.documentVersionId());
        if (v == null || (Integer) v[0] != s.documentVersionNo() || !"CLEAN".equals(v[2]) || (Integer) v[3] != 0
                || !HexFormat.of().formatHex(s.documentSha()).equalsIgnoreCase((String) v[1])) conflict();
    }

    private void lockTypedLink(ImportAuthorizedContext c, Snapshot s) {
        List<Long> ids = jdbc.query("SELECT id FROM t_document_link WHERE tenant_id=? AND document_id=? AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0 ORDER BY id FOR UPDATE",
                (rs, n) -> rs.getLong(1), c.tenantId(), s.documentId(), s.jobId());
        if (ids.size() != 1) conflict();
        documents.authorizeImportJobDownload(s.jobId(), s.documentId(), s.documentVersionId(),
                s.documentVersionNo(), HexFormat.of().formatHex(s.documentSha()));
    }

    private Job lockJob(ImportAuthorizedContext c, long id) {
        return jdbc.query("SELECT state,version_no,lease_owner,lease_generation,lease_expires_at,active_validation_run_id,current_validation_run_id FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE",
                rs -> rs.next() ? new Job(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                        rs.getTimestamp(5), rs.getObject(6, Long.class), rs.getObject(7, Long.class)) : null, c.tenantId(), id);
    }

    private Mapping lockMapping(ImportAuthorizedContext c, Long id) {
        return jdbc.query("SELECT source_system_id,entity_type,schema_version,mapping_version,mapping_sha256 FROM t_import_mapping WHERE tenant_id=? AND id=? FOR UPDATE",
                rs -> rs.next() ? new Mapping(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getBytes(5)) : null,
                c.tenantId(), id);
    }

    private void requireJob(Snapshot s, Snapshot current, Job j) {
        if (j == null || !"MAPPED".equals(j.state()) || j.activeRun() != null || j.currentRun() != null
                || !sameInput(s, current)) conflict();
    }

    private void requireMapping(Snapshot s, Mapping m) {
        if (m == null || m.sourceId() != s.sourceId() || !s.entityType().equals(m.entity())
                || !s.schemaVersion().equals(m.schema()) || m.version() != s.mappingVersion()
                || !MessageDigest.isEqual(s.mappingSha(), m.sha())) conflict();
    }

    private boolean sameInput(Snapshot a, Snapshot b) {
        return a.jobId() == b.jobId() && a.sourceId() == b.sourceId() && a.sourceVersion() == b.sourceVersion()
                && a.documentId() == b.documentId() && a.documentVersionId() == b.documentVersionId()
                && a.documentVersionNo() == b.documentVersionNo() && java.util.Objects.equals(a.mappingId(), b.mappingId())
                && java.util.Objects.equals(a.mappingVersion(), b.mappingVersion())
                && MessageDigest.isEqual(a.sourceSha(), b.sourceSha()) && MessageDigest.isEqual(a.documentSha(), b.documentSha())
                && MessageDigest.isEqual(a.mappingSha(), b.mappingSha()) && a.entityType().equals(b.entityType())
                && a.schemaVersion().equals(b.schemaVersion()) && a.encoding().equals(b.encoding())
                && a.legalEntity().equals(b.legalEntity());
    }

    private void requireActor(ImportAuthorizedContext expected, ImportAuthorizedContext actual) {
        if (!expected.equals(actual)) throw BusinessException.of(403, "error.forbidden");
    }

    private void conflict() { throw BusinessException.of(409, "error.import.jobStateConflict"); }

    private enum Mode { ACQUIRE, TAKEOVER, RENEW }
    private record Job(String state,long version,String owner,long generation,java.sql.Timestamp expiresAt,Long activeRun,Long currentRun) { }
    private record Mapping(long sourceId,String entity,String schema,int version,byte[] sha) { }
    private record Snapshot(ImportAuthorizedContext context,long jobId,long sourceId,long sourceVersion,String entityType,
                           String schemaVersion,String encoding,byte[] sourceSha,String legalEntity,long documentId,
                           long documentVersionId,int documentVersionNo,byte[] documentSha,Long mappingId,
                           Integer mappingVersion,byte[] mappingSha,String state,long jobVersion,String owner,
                           long generation,java.sql.Timestamp expiresAt,Long activeRun,Long currentRun,
                           long currentSourceVersion,String sourceStatus) { }
    public static final class AcquireOperation {
        private final String serviceInstanceId,operationId,workerId; private final Snapshot snapshot;
        private AcquireOperation(String instance,String op,String worker,Snapshot s) { serviceInstanceId=instance; operationId=op; workerId=worker; snapshot=s; }
    }
    public static final class TakeoverOperation {
        private final String serviceInstanceId,operationId,workerId,previousWorkerId; private final Snapshot snapshot; private final long expectedGeneration;
        private TakeoverOperation(String instance,String op,String worker,Snapshot s,String old,long gen) { serviceInstanceId=instance; operationId=op; workerId=worker; snapshot=s; previousWorkerId=old; expectedGeneration=gen; }
    }
    public static final class LeaseGrant {
        private final String serviceInstanceId; private final Snapshot snapshot; private final String workerId,operationId; private final long generation;
        private LeaseGrant(String instance,Snapshot s,String worker,String op,long gen) { serviceInstanceId=instance; snapshot=s; workerId=worker; operationId=op; generation=gen; }
    }
}
