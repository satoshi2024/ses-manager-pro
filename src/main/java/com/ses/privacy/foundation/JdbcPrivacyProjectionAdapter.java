package com.ses.privacy.foundation;

import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 合成application DBだけにjournal prefixをCAS投影するtest-only adapter。 */
final class JdbcPrivacyProjectionAdapter implements PrivacyProjectionPort {
    private static final String ZERO_HASH = "0".repeat(64);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final Environment environment;

    JdbcPrivacyProjectionAdapter(DataSource dataSource, Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
        requireTestProfile();
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public ProjectionResult apply(JournalProjectionService.VerifiedJournalEvent verified) {
        requireTestProfile();
        if (verified == null || verified.partition() == null || verified.event() == null) {
            return ProjectionResult.quarantine(0, null, QuarantineCode.ACK_INVALID);
        }
        JournalEvent event = verified.event();
        try {
            ProjectionResult result = writeTransaction.execute(status -> applyInTransaction(verified.partition(), event));
            if (result == null) return ProjectionResult.quarantine(event.revision(), event.eventHash(), QuarantineCode.PROJECTION_UNAVAILABLE);
            return result;
        } catch (RuntimeException uncertain) {
            try {
                ProjectionSnapshot observed = snapshot(verified.partition());
                if (observed.revision() == event.revision() && observed.eventHash().equals(event.eventHash())) {
                    return ProjectionResult.noOp(observed.revision(), observed.eventHash());
                }
            } catch (RuntimeException ignored) {
                // commit ACK不明とreadback不能はquarantineへ倒す。
            }
            return ProjectionResult.quarantine(event.revision(), event.eventHash(), QuarantineCode.PROJECTION_UNAVAILABLE);
        }
    }

    @Override
    public ProjectionSnapshot snapshot(JournalPartition partition) {
        requireTestProfile();
        Objects.requireNonNull(partition, "partition");
        return readTransaction.execute(status -> readSnapshot(partition));
    }

    private ProjectionResult applyInTransaction(JournalPartition partition, JournalEvent event) {
        List<ProjectionSnapshot> rows = jdbc.query("SELECT last_applied_revision, event_hash, held, last_action_id, last_action_state FROM sf_w2b_projection WHERE tenant_id = ? AND subject_ref = ? FOR UPDATE",
                (rs, rowNum) -> new ProjectionSnapshot(partition, rs.getLong("last_applied_revision"), rs.getString("event_hash"),
                        rs.getBoolean("held"), rs.getString("last_action_id"), rs.getString("last_action_state")),
                partition.tenantId(), partition.subjectRef());
        ProjectionSnapshot current = rows.isEmpty() ? empty(partition) : rows.get(0);
        if (event.revision() == current.revision()) {
            return event.eventHash().equals(current.eventHash())
                    ? ProjectionResult.noOp(current.revision(), current.eventHash())
                    : ProjectionResult.quarantine(event.revision(), event.eventHash(), QuarantineCode.HASH_CONFLICT);
        }
        if (event.revision() < current.revision()) {
            return ProjectionResult.quarantine(event.revision(), event.eventHash(), QuarantineCode.REVISION_BACKWARD);
        }
        if (event.revision() > current.revision() + 1) {
            return ProjectionResult.quarantine(event.revision(), event.eventHash(), QuarantineCode.REVISION_GAP);
        }
        String expectedPrevious = current.revision() == 0 ? ZERO_HASH : current.eventHash();
        if (!expectedPrevious.equals(event.previousHash())) {
            return ProjectionResult.quarantine(event.revision(), event.eventHash(), QuarantineCode.HASH_CONFLICT);
        }
        ProjectedValues next = project(current, event);
        if (current.revision() == 0) {
            jdbc.update("INSERT INTO sf_w2b_projection(tenant_id, subject_ref, last_applied_revision, event_hash, held, last_action_id, last_action_state) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    partition.tenantId(), partition.subjectRef(), event.revision(), event.eventHash(), next.held(),
                    next.lastActionId(), next.lastActionState());
        } else {
            int updated = jdbc.update("UPDATE sf_w2b_projection SET last_applied_revision = ?, event_hash = ?, held = ?, last_action_id = ?, last_action_state = ? WHERE tenant_id = ? AND subject_ref = ? AND last_applied_revision = ? AND event_hash = ?",
                    event.revision(), event.eventHash(), next.held(), next.lastActionId(), next.lastActionState(),
                    partition.tenantId(), partition.subjectRef(), current.revision(), current.eventHash());
            if (updated != 1) return ProjectionResult.quarantine(event.revision(), event.eventHash(), QuarantineCode.CAS_CONFLICT);
        }
        return ProjectionResult.applied(event.revision(), event.eventHash());
    }

    private ProjectionSnapshot readSnapshot(JournalPartition partition) {
        List<ProjectionSnapshot> rows = jdbc.query("SELECT last_applied_revision, event_hash, held, last_action_id, last_action_state FROM sf_w2b_projection WHERE tenant_id = ? AND subject_ref = ?",
                (rs, rowNum) -> new ProjectionSnapshot(partition, rs.getLong("last_applied_revision"), rs.getString("event_hash"),
                        rs.getBoolean("held"), rs.getString("last_action_id"), rs.getString("last_action_state")),
                partition.tenantId(), partition.subjectRef());
        return rows.isEmpty() ? empty(partition) : rows.get(0);
    }

    private static ProjectionSnapshot empty(JournalPartition partition) {
        return new ProjectionSnapshot(partition, 0, ZERO_HASH, false, null, "NONE");
    }

    private static ProjectedValues project(ProjectionSnapshot current, JournalEvent event) {
        return switch (event.type()) {
            case HOLD_STARTED -> new ProjectedValues(true, current.lastActionId(), current.lastActionState());
            case HOLD_RELEASED -> throw new IllegalArgumentException("hold解除eventはprojection対象外です");
            case DISPATCH_AUTHORIZED -> new ProjectedValues(current.held(), event.actionId(), "AUTHORIZED");
            case LEASE_CLAIMED, LEASE_TAKEN_OVER -> new ProjectedValues(current.held(), current.lastActionId(), current.lastActionState());
        };
    }

    private void requireTestProfile() {
        String[] active = environment.getActiveProfiles();
        String[] defaults = environment.getDefaultProfiles();
        if (!Arrays.asList(active).contains("test") || Arrays.stream(active).anyMatch("prod"::equals)
                || Arrays.stream(defaults).anyMatch("prod"::equals)) {
            throw new SecurityException("synthetic projectionはprodを含まないactive test profile専用です");
        }
    }

    private record ProjectedValues(boolean held, String lastActionId, String lastActionState) { }
}

