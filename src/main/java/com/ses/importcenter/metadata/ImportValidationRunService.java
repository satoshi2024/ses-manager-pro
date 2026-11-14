package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.LogRedaction;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * A1a: upload済みjobの検証を、既存のlifecycle・lease・append・seal・fenceの実装だけで一続きに実行する同期編成。
 *
 * <p>順序は「mapping束縛（UPLOADEDのみ）→ start（またはlease切れのtakeover）→ source先頭からの検証とrow append
 * → seal → SEALED snapshotの再計算 → atomic publish」。状態遷移は全てlifecycle側のCAS（version・lease世代・
 * expires_at・run slotの完全一致）で行い、本classは遷移を直接書かない。各段階はlifecycleの短い独立transaction
 * （REQUIRES_NEW）で、外側transactionの中からは呼び出させない。
 *
 * <p>総期限: upload（B-NF06-1）と同じ120秒のsoft上限を本requestの入口から適用し、段階境界とchunk境界
 * （250行ごとのlease更新時）で確認する。超過時と、lifecycle内部の短いgate期限（10秒）超過時は503
 * （{@code error.import.processingDeadlineExceeded}）で返す。中断時は自分が保持する最新leaseでrunをabortし、
 * jobをMAPPEDへ戻す（best-effort。abortできない場合はlease期限後の次requestがtakeoverして続きから進める）。
 * 単一のblocking操作（1回のJDBC呼出・source読取）は中断できないため、超過量はその操作のtimeout分大きくなり得る。
 *
 * <p>scope: 対象jobは現在のtenantかつ現在の法人に属するCUSTOMER／{@code ses-import-v1}だけ。別法人・別tenantの
 * jobは存在しないjobと同じ404（{@code error.import.jobNotFound}）で返し、lifecycleへ渡さない
 * （lifecycleの入力読取は法人不一致を409にするため、先に判定しないとjob IDの存在がoracleになる）。
 *
 * <p>VALIDATEDのjobには再検証せず現在の公開runの要約を返す（冪等）。apply系は本classに含めない（B-NF06-3）。
 */
@Service
public class ImportValidationRunService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportValidationRunService.class);
    static final String DEADLINE_MESSAGE_KEY = "error.import.processingDeadlineExceeded";

    private final ImportMetadataAuthorizationService authorization;
    private final ImportMetadataService metadata;
    private final ImportValidationLifecycleService lifecycle;
    private final ImportValidationAppendService append;
    private final JdbcTemplate jdbc;
    private final LongSupplier nanoClock;
    private final Duration totalBudget;

    @Autowired
    public ImportValidationRunService(ImportMetadataAuthorizationService authorization, ImportMetadataService metadata,
                                      ImportValidationLifecycleService lifecycle, ImportValidationAppendService append,
                                      JdbcTemplate jdbc) {
        this(authorization, metadata, lifecycle, append, jdbc, System::nanoTime, ImportIngressDeadline.TOTAL_BUDGET);
    }

    /** test用。clockと総期限を差し替え、実時間のsleepなしで期限分岐を再現する。 */
    ImportValidationRunService(ImportMetadataAuthorizationService authorization, ImportMetadataService metadata,
                               ImportValidationLifecycleService lifecycle, ImportValidationAppendService append,
                               JdbcTemplate jdbc, LongSupplier nanoClock, Duration totalBudget) {
        this.authorization = Objects.requireNonNull(authorization);
        this.metadata = Objects.requireNonNull(metadata);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.append = Objects.requireNonNull(append);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.totalBudget = Objects.requireNonNull(totalBudget);
    }

    /**
     * @param mappingId UPLOADEDのjobに束縛するmapping（同じsource・entity・schemaのもの）。MAPPED以降のjobでは
     *                  省略するか、既に束縛済みのmappingと同じ値でなければならない。
     */
    public ImportValidationRunSummary validate(long jobId, Long mappingId) {
        ImportIngressDeadline deadline = ImportIngressDeadline.start(nanoClock, totalBudget);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("取込検証の編成は外側transactionなしで呼び出す必要があります");
        }
        if (mappingId != null && mappingId < 1) {
            throw BusinessException.of(400, "error.import.invalidMapping");
        }
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        JobView job = loadVisibleJob(context, jobId);
        if ("UPLOADED".equals(job.state())) {
            if (mappingId == null) throw BusinessException.of(400, "error.import.mappingRequired");
            metadata.bindMapping(jobId, job.version(), mappingId);
            requireSameContext(context);
            job = loadVisibleJob(context, jobId);
        } else if (mappingId != null && !mappingId.equals(job.mappingId())) {
            throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
        }
        if ("VALIDATED".equals(job.state())) {
            return requireSummary(context, jobId);
        }
        checkDeadline(deadline, "start");

        LeaseHolder holder = new LeaseHolder();
        try {
            ImportValidationLifecycleService.RunLease lease = acquire(job, jobId);
            holder.lease = lease;
            Consumer<ImportValidationLifecycleService.RunLease> progress = renewed -> {
                holder.lease = renewed;
                checkDeadline(deadline, "scan");
            };
            ImportValidationLifecycleService.RunLease sealed;
            if ("BUILDING".equals(runState(context, jobId, lease.runId()))) {
                ImportValidationAppendService.AppendCompletion completion =
                        append.appendRemainingWithSealCapability(lease, progress);
                holder.lease = completion.lease();
                checkDeadline(deadline, "seal");
                sealed = lifecycle.seal(completion.capability());
                holder.lease = sealed;
            } else {
                sealed = lease;
            }
            checkDeadline(deadline, "recount");
            ImportValidationAppendService.PublishCapability capability = append.recountSealed(sealed, progress);
            holder.lease = capability.lease();
            checkDeadline(deadline, "publish");
            lifecycle.publish(capability);
            holder.lease = null;
        } catch (RuntimeException | Error failure) {
            abortQuietly(holder.lease, failure);
            if (failure instanceof BusinessException business && business.getCode() == 503) throw business;
            if (deadline.expired() || ImportIngressDeadline.isTimeoutFailure(failure)) {
                BusinessException exceeded = BusinessException.of(503, DEADLINE_MESSAGE_KEY);
                exceeded.addSuppressed(failure);
                throw exceeded;
            }
            throw failure;
        }
        return requireSummary(context, jobId);
    }

    /** MAPPEDはstart、lease切れのVALIDATINGはtakeover。lease有効中は他の検証が進行中として409。 */
    private ImportValidationLifecycleService.RunLease acquire(JobView job, long jobId) {
        return switch (job.state()) {
            case "MAPPED" -> lifecycle.start(lifecycle.beginStart(jobId));
            case "VALIDATING" -> {
                if (job.leaseActive() || job.activeRunId() == null) {
                    throw BusinessException.of(409, "error.import.validationInProgress");
                }
                yield lifecycle.takeOver(lifecycle.beginTakeover(jobId, job.activeRunId()));
            }
            default -> throw BusinessException.of(409, "error.import.jobStateConflict");
        };
    }

    private void abortQuietly(ImportValidationLifecycleService.RunLease lease, Throwable cause) {
        if (lease == null) return;
        try {
            lifecycle.abort(lease);
            log.warn("[取込] 検証を中断したためrunをabortしました: runId={} failureClass={}", lease.runId(),
                    LogRedaction.exceptionType(cause));
        } catch (RuntimeException abortFailure) {
            // 他workerのtakeover・ACK喪失・権限変更等。lease期限後の次requestがtakeoverするため元の失敗を優先する。
            log.warn("[取込] 検証中断後のabortに失敗しました（lease期限後にtakeover可能）: runId={} failureClass={} "
                    + "abortFailureClass={}", lease.runId(), LogRedaction.exceptionType(cause),
                    LogRedaction.exceptionType(abortFailure));
        }
    }

    private void checkDeadline(ImportIngressDeadline deadline, String phase) {
        if (deadline.expired()) {
            log.warn("[取込] 検証処理の総期限を超過したため中断します: phase={} budgetSeconds={}", phase,
                    totalBudget.toSeconds());
            throw BusinessException.of(503, DEADLINE_MESSAGE_KEY);
        }
    }

    private void requireSameContext(ImportAuthorizedContext expected) {
        if (!expected.equals(authorization.requireCurrentAuthorization())) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }

    /** 現在のtenant・法人に属するA1a対象jobだけを返す。それ以外は存在しないjobと同じ404。 */
    private JobView loadVisibleJob(ImportAuthorizedContext context, long jobId) {
        JobView job = jdbc.query("""
                SELECT state, version_no, legal_entity_id, entity_type, schema_version, mapping_id,
                       active_validation_run_id,
                       CASE WHEN lease_expires_at IS NOT NULL AND lease_expires_at > CURRENT_TIMESTAMP(6)
                            THEN 1 ELSE 0 END AS lease_active
                FROM t_import_job WHERE tenant_id=? AND id=?
                """, rs -> rs.next() ? new JobView(rs.getString("state"), rs.getLong("version_no"),
                rs.getString("legal_entity_id"), rs.getString("entity_type"), rs.getString("schema_version"),
                rs.getObject("mapping_id", Long.class), rs.getObject("active_validation_run_id", Long.class),
                rs.getInt("lease_active") == 1) : null, context.tenantId(), jobId);
        if (job == null || !Long.toString(context.legalEntityId()).equals(job.legalEntityId())
                || !ImportEntity.CUSTOMER.name().equals(job.entity())
                || !SesImportV1Schema.VERSION.equals(job.schema())) {
            throw BusinessException.of(404, "error.import.jobNotFound");
        }
        return job;
    }

    private String runState(ImportAuthorizedContext context, long jobId, long runId) {
        String state = jdbc.query("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                rs -> rs.next() ? rs.getString(1) : null, context.tenantId(), jobId, runId);
        if (state == null) throw BusinessException.of(409, "error.import.validationRunMismatch");
        return state;
    }

    private ImportValidationRunSummary requireSummary(ImportAuthorizedContext context, long jobId) {
        requireSameContext(context);
        ImportValidationRunSummary summary = jdbc.query("""
                SELECT j.id, j.state, r.id, r.run_no, r.source_row_count, r.accepted_row_count,
                       r.rejected_row_count, r.empty_row_count
                FROM t_import_job j JOIN t_import_validation_run r
                  ON r.tenant_id=j.tenant_id AND r.job_id=j.id AND r.id=j.current_validation_run_id
                WHERE j.tenant_id=? AND j.id=? AND j.legal_entity_id=? AND j.state='VALIDATED'
                  AND r.state='COMPLETED' AND r.published_slot=1
                """, rs -> rs.next() ? new ImportValidationRunSummary(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getInt(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)) : null,
                context.tenantId(), jobId, Long.toString(context.legalEntityId()));
        if (summary == null) throw BusinessException.of(409, "error.import.jobStateConflict");
        return summary;
    }

    private record JobView(String state, long version, String legalEntityId, String entity, String schema,
                           Long mappingId, Long activeRunId, boolean leaseActive) { }

    /** 中断時のabortに使う、自分が最後に受け取ったlease。publish成功後はnull。 */
    private static final class LeaseHolder {
        private ImportValidationLifecycleService.RunLease lease;
    }
}
