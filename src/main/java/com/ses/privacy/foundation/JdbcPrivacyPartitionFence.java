package com.ses.privacy.foundation;

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
import java.util.UUID;
import java.util.function.Function;

/** test profile限定の永続partition隔離fence。解除APIは持たない。 */
final class JdbcPrivacyPartitionFence {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate acquireTransaction;
    private final TransactionTemplate fixtureMutationTransaction;
    private final Environment environment;

    JdbcPrivacyPartitionFence(DataSource dataSource, Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
        requireTestProfile();
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        this.acquireTransaction = new TransactionTemplate(transactionManager);
        this.acquireTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.acquireTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.fixtureMutationTransaction = new TransactionTemplate(transactionManager);
        this.fixtureMutationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.fixtureMutationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 現generationを失効させ、新しいownerで永続隔離する。隔離を解除する操作は存在しない。 */
    Lease quarantine(JournalPartition partition) {
        requireTestProfile();
        Objects.requireNonNull(partition, "partition");
        return acquireTransaction.execute(status -> {
            State current = lockState(partition);
            long generation = Math.addExact(current.generation(), 1L);
            String owner = UUID.randomUUID().toString();
            int updated = jdbc.update("UPDATE sf_w2b_partition_fence SET generation=?, state='QUARANTINED', owner_token=? "
                            + "WHERE tenant_id=? AND subject_ref=? AND generation=?",
                    generation, owner, partition.tenantId(), partition.subjectRef(), current.generation());
            if (updated != 1) throw new IllegalStateException("partition fence CASに失敗しました");
            return new Lease(partition, generation, owner);
        });
    }

    Snapshot snapshot(JournalPartition partition) {
        requireTestProfile();
        List<State> states = jdbc.query("SELECT generation, state, owner_token FROM sf_w2b_partition_fence "
                        + "WHERE tenant_id=? AND subject_ref=?",
                (rs, row) -> new State(rs.getLong("generation"), rs.getString("state"), rs.getString("owner_token")),
                partition.tenantId(), partition.subjectRef());
        if (states.isEmpty()) return new Snapshot(partition, 0, "ACTIVE", null);
        State state = states.get(0);
        return new Snapshot(partition, state.generation(), state.status(), state.ownerToken());
    }

    boolean isCurrentOwner(Lease lease) {
        if (lease == null) return false;
        Snapshot state = snapshot(lease.partition());
        return "QUARANTINED".equals(state.state()) && state.generation() == lease.generation()
                && lease.ownerToken().equals(state.ownerToken());
    }

    boolean lockForNormalProjection(JournalPartition partition) {
        requireTestProfile();
        return "ACTIVE".equals(lockState(partition).status());
    }

    boolean lockForRecoveryProjection(JournalPartition partition, Lease lease) {
        requireTestProfile();
        if (lease == null || !partition.equals(lease.partition())) return false;
        State state = lockState(partition);
        return "QUARANTINED".equals(state.status()) && state.generation() == lease.generation()
                && lease.ownerToken().equals(state.ownerToken());
    }

    /** fixture-admin専用connectionからのrestoreも同じrow lock/CAS fence内で実行する。 */
    <T> T underCurrentOwner(Lease lease, Function<JdbcTemplate, T> fixtureMutation) {
        requireTestProfile();
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(fixtureMutation, "fixtureMutation");
        return fixtureMutationTransaction.execute(status -> {
            if (!lockForRecoveryProjection(lease.partition(), lease)) {
                throw new StaleOwnerException();
            }
            return fixtureMutation.apply(jdbc);
        });
    }

    /** 最終projection readbackとowner検査を同じrow lock/transaction内で行う。 */
    <T> T readUnderCurrentOwner(Lease lease, Function<JdbcTemplate, T> readback) {
        requireTestProfile();
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(readback, "readback");
        return fixtureMutationTransaction.execute(status -> {
            if (!lockForRecoveryProjection(lease.partition(), lease)) throw new StaleOwnerException();
            return readback.apply(jdbc);
        });
    }

    private State lockState(JournalPartition partition) {
        jdbc.update("INSERT IGNORE INTO sf_w2b_partition_fence(tenant_id, subject_ref, generation, state, owner_token) "
                        + "VALUES(?, ?, 0, 'ACTIVE', NULL)", partition.tenantId(), partition.subjectRef());
        List<State> states = jdbc.query("SELECT generation, state, owner_token FROM sf_w2b_partition_fence "
                        + "WHERE tenant_id=? AND subject_ref=? FOR UPDATE",
                (rs, row) -> new State(rs.getLong("generation"), rs.getString("state"), rs.getString("owner_token")),
                partition.tenantId(), partition.subjectRef());
        if (states.size() != 1) throw new IllegalStateException("partition fence rowを一意に取得できません");
        return states.get(0);
    }

    private void requireTestProfile() {
        String[] active = environment.getActiveProfiles();
        String[] defaults = environment.getDefaultProfiles();
        if (!Arrays.asList(active).contains("test") || Arrays.stream(active).anyMatch("prod"::equals)
                || Arrays.stream(defaults).anyMatch("prod"::equals)) {
            throw new SecurityException("synthetic partition fenceはprodを含まないactive test profile専用です");
        }
    }

    record Lease(JournalPartition partition, long generation, String ownerToken) { }
    record Snapshot(JournalPartition partition, long generation, String state, String ownerToken) { }

    private record State(long generation, String status, String ownerToken) { }

    static final class StaleOwnerException extends RuntimeException {
        private StaleOwnerException() { super("partition fenceのowner/generationが失効しています"); }
    }
}
