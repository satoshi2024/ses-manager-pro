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
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate acquireTransaction;
    private final TransactionTemplate fixtureMutationTransaction;
    private final Environment environment;

    JdbcPrivacyPartitionFence(DataSource dataSource, Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
        requireTestProfile();
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(this.dataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        this.acquireTransaction = new TransactionTemplate(transactionManager);
        this.acquireTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.acquireTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.fixtureMutationTransaction = new TransactionTemplate(transactionManager);
        this.fixtureMutationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.fixtureMutationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 現generationを失効させ、新しいownerで永続隔離する。隔離を解除する操作は存在しない。 */
    Lease quarantine(JournalPartition partition, RecoveryDeadline deadline) {
        requireTestProfile();
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(deadline, "deadline").check();
        TransactionTemplate bounded = boundedTransaction(acquireTransaction, deadline);
        try {
            Lease lease = bounded.execute(status -> {
                deadline.requireJdbcSeconds();
                State current = lockState(partition, deadline);
                deadline.check();
                long generation = Math.addExact(current.generation(), 1L);
                String owner = UUID.randomUUID().toString();
                int updated = jdbc(deadline).update("UPDATE sf_w2b_partition_fence SET generation=?, state='QUARANTINED', owner_token=? "
                                + "WHERE tenant_id=? AND subject_ref=? AND generation=?",
                        generation, owner, partition.tenantId(), partition.subjectRef(), current.generation());
                if (updated != 1) throw new IllegalStateException("partition fence CASに失敗しました");
                deadline.check();
                registerBeforeCommit(deadline);
                return new Lease(partition, generation, owner, deadline);
            });
            deadline.check();
            if (lease == null) throw new IllegalStateException("partition fence取得結果がありません");
            return lease;
        } catch (RuntimeException uncertain) {
            throw new AcquisitionUnconfirmedException(uncertain);
        }
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
        RecoveryDeadline deadline = lease.deadline();
        if (deadline != null) deadline.check();
        JdbcTemplate reader = jdbc(deadline);
        List<State> states = reader.query("SELECT generation, state, owner_token FROM sf_w2b_partition_fence WHERE tenant_id=? AND subject_ref=?",
                (rs, row) -> new State(rs.getLong("generation"), rs.getString("state"), rs.getString("owner_token")),
                lease.partition().tenantId(), lease.partition().subjectRef());
        deadline.check();
        if (states.isEmpty()) return false;
        State state = states.get(0);
        return "QUARANTINED".equals(state.status()) && state.generation() == lease.generation()
                && lease.ownerToken().equals(state.ownerToken());
    }

    Snapshot snapshotDuringCleanup(Lease lease) {
        if (lease == null) return null;
        JdbcTemplate reader = new JdbcTemplate(dataSource);
        reader.setQueryTimeout(lease.deadline().requireCleanupJdbcSeconds(1));
        List<State> states = reader.query("SELECT generation, state, owner_token FROM sf_w2b_partition_fence WHERE tenant_id=? AND subject_ref=?",
                (rs, row) -> new State(rs.getLong("generation"), rs.getString("state"), rs.getString("owner_token")),
                lease.partition().tenantId(), lease.partition().subjectRef());
        lease.deadline().checkCleanupRemaining();
        if (states.isEmpty()) return new Snapshot(lease.partition(), 0, "ACTIVE", null);
        State state = states.get(0);
        return new Snapshot(lease.partition(), state.generation(), state.status(), state.ownerToken());
    }

    boolean lockForNormalProjection(JournalPartition partition) {
        requireTestProfile();
        return "ACTIVE".equals(lockState(partition, null).status());
    }

    boolean lockForRecoveryProjection(JournalPartition partition, Lease lease) {
        requireTestProfile();
        if (lease == null || !partition.equals(lease.partition())) return false;
        RecoveryDeadline deadline = lease.deadline();
        deadline.check();
        State state = lockState(partition, deadline);
        deadline.check();
        return "QUARANTINED".equals(state.status()) && state.generation() == lease.generation()
                && lease.ownerToken().equals(state.ownerToken());
    }

    /** fixture-admin専用connectionからのrestoreも同じrow lock/CAS fence内で実行する。 */
    <T> T underCurrentOwner(Lease lease, Function<FixtureSql, T> fixtureMutation) {
        requireTestProfile();
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(fixtureMutation, "fixtureMutation");
        RecoveryDeadline deadline = lease.deadline();
        deadline.check();
        T value = boundedTransaction(fixtureMutationTransaction, deadline).execute(status -> {
            if (!lockForRecoveryProjection(lease.partition(), lease)) {
                throw new StaleOwnerException();
            }
            deadline.check();
            T result = fixtureMutation.apply(new FixtureSql(dataSource, deadline));
            deadline.check();
            registerBeforeCommit(deadline);
            return result;
        });
        deadline.check();
        return value;
    }

    private State lockState(JournalPartition partition, RecoveryDeadline deadline) {
        JdbcTemplate boundedJdbc = deadline == null ? jdbc : jdbc(deadline);
        // 重複行のINSERT IGNOREは共有duplicate-key lock後のFOR UPDATE昇格でdeadlockし得るため、upsertで先に排他ロックを取る。
        boundedJdbc.update("INSERT INTO sf_w2b_partition_fence(tenant_id, subject_ref, generation, state, owner_token) "
                        + "VALUES(?, ?, 0, 'ACTIVE', NULL) ON DUPLICATE KEY UPDATE tenant_id = VALUES(tenant_id)",
                partition.tenantId(), partition.subjectRef());
        if (deadline != null) boundedJdbc.setQueryTimeout(deadline.requireJdbcSeconds());
        List<State> states = boundedJdbc.query("SELECT generation, state, owner_token FROM sf_w2b_partition_fence "
                        + "WHERE tenant_id=? AND subject_ref=? FOR UPDATE",
                (rs, row) -> new State(rs.getLong("generation"), rs.getString("state"), rs.getString("owner_token")),
                partition.tenantId(), partition.subjectRef());
        if (states.size() != 1) throw new IllegalStateException("partition fence rowを一意に取得できません");
        return states.get(0);
    }

    private JdbcTemplate jdbc(RecoveryDeadline deadline) {
        JdbcTemplate bounded = new JdbcTemplate(dataSource);
        bounded.setQueryTimeout(deadline.requireJdbcSeconds());
        return bounded;
    }

    private static TransactionTemplate boundedTransaction(TransactionTemplate source, RecoveryDeadline deadline) {
        TransactionTemplate bounded = new TransactionTemplate(source.getTransactionManager());
        bounded.setPropagationBehavior(source.getPropagationBehavior());
        bounded.setIsolationLevel(source.getIsolationLevel());
        bounded.setTimeout(deadline.requireJdbcSeconds());
        return bounded;
    }

    private static void registerBeforeCommit(RecoveryDeadline deadline) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { deadline.requireJdbcSeconds(); }
                });
    }

    /** fixture mutation向けにSQL単位のdeadline検査を強制する狭いadapter。 */
    static final class FixtureSql {
        private final DataSource dataSource;
        private final RecoveryDeadline deadline;

        private FixtureSql(DataSource dataSource, RecoveryDeadline deadline) {
            this.dataSource = dataSource;
            this.deadline = deadline;
        }

        int update(String sql, Object... arguments) {
            deadline.check();
            JdbcTemplate bounded = new JdbcTemplate(dataSource);
            bounded.setQueryTimeout(deadline.requireJdbcSeconds());
            int changed = bounded.update(sql, arguments);
            deadline.check();
            return changed;
        }
    }

    private void requireTestProfile() {
        String[] active = environment.getActiveProfiles();
        String[] defaults = environment.getDefaultProfiles();
        if (!Arrays.asList(active).contains("test") || Arrays.stream(active).anyMatch("prod"::equals)
                || Arrays.stream(defaults).anyMatch("prod"::equals)) {
            throw new SecurityException("synthetic partition fenceはprodを含まないactive test profile専用です");
        }
    }

    static final class Lease {
        private final JournalPartition partition;
        private final long generation;
        private final String ownerToken;
        private final RecoveryDeadline deadline;

        private Lease(JournalPartition partition, long generation, String ownerToken, RecoveryDeadline deadline) {
            this.partition = Objects.requireNonNull(partition, "partition");
            this.generation = generation;
            this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
            this.deadline = Objects.requireNonNull(deadline, "deadline");
        }

        JournalPartition partition() { return partition; }
        long generation() { return generation; }
        String ownerToken() { return ownerToken; }
        RecoveryDeadline deadline() { return deadline; }
    }
    record Snapshot(JournalPartition partition, long generation, String state, String ownerToken) { }

    private record State(long generation, String status, String ownerToken) { }

    static final class StaleOwnerException extends RuntimeException {
        StaleOwnerException() { super("partition fenceのowner/generationが失効しています"); }
    }

    static final class AcquisitionUnconfirmedException extends RuntimeException {
        private AcquisitionUnconfirmedException(Throwable cause) { super("partition隔離取得結果が未確認です", cause); }
    }
}
