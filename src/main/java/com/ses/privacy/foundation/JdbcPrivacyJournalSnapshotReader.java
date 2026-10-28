package com.ses.privacy.foundation;

import com.ses.privacy.foundation.PrivacyJournalPort.CommandType;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
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

/** test-profile専用journal read-only snapshot reader。SQLはSELECTのみ。 */
final class JdbcPrivacyJournalSnapshotReader implements PrivacyJournalSnapshotReader {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;
    private final Environment environment;
    private final DataSource dataSource;

    JdbcPrivacyJournalSnapshotReader(DataSource dataSource, Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
        requireTestProfile();
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(this.dataSource);
        this.read = new TransactionTemplate(new DataSourceTransactionManager(this.dataSource));
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Snapshot read(JournalPartition partition) {
        return read(partition, null);
    }

    @Override
    public Snapshot read(JournalPartition partition, RecoveryDeadline deadline) {
        requireTestProfile();
        Objects.requireNonNull(partition, "partition");
        JdbcTemplate reader = deadline == null ? jdbc : boundedJdbc(deadline);
        TransactionTemplate readerTx = deadline == null ? read : boundedTransaction(deadline);
        if (deadline != null) deadline.check();
        Snapshot result = readerTx.execute(status -> {
            if (deadline != null) reader.setQueryTimeout(deadline.requireJdbcSeconds());
            List<JournalEvent> events = reader.query("SELECT revision, command_id, payload_hash, event_type, case_id, action_id, request_hash, worker_id, expected_lease_generation, lease_until, previous_hash, event_hash, created_at FROM sf_journal_event WHERE tenant_id=? AND subject_ref=? ORDER BY revision",
                    (rs, row) -> new JournalEvent(rs.getLong("revision"), rs.getString("command_id"),
                            rs.getString("payload_hash"), CommandType.valueOf(rs.getString("event_type")),
                            rs.getString("case_id"), rs.getString("action_id"), rs.getString("request_hash"),
                            rs.getString("worker_id"), rs.getLong("expected_lease_generation"),
                            rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant(),
                            rs.getString("previous_hash"), rs.getString("event_hash"),
                            rs.getTimestamp("created_at").toInstant()), partition.tenantId(), partition.subjectRef());
            if (deadline != null) reader.setQueryTimeout(deadline.requireJdbcSeconds());
            List<JournalHead> heads = reader.query("SELECT revision, event_hash, held, lease_generation, lease_owner, lease_until FROM sf_journal_head WHERE tenant_id=? AND subject_ref=?",
                    (rs, row) -> new JournalHead(partition, rs.getLong("revision"), rs.getString("event_hash"),
                            rs.getBoolean("held"), rs.getLong("lease_generation"), rs.getString("lease_owner"),
                            rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant()),
                    partition.tenantId(), partition.subjectRef());
            JournalHead head = heads.isEmpty()
                    ? new JournalHead(partition, 0, "0".repeat(64), false, 0, null, null) : heads.get(0);
            if (deadline != null) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) { deadline.requireJdbcSeconds(); }
                        });
            }
            return new Snapshot(head, events);
        });
        if (deadline != null) deadline.check();
        return result;
    }

    private JdbcTemplate boundedJdbc(RecoveryDeadline deadline) {
        JdbcTemplate bounded = new JdbcTemplate(dataSource);
        bounded.setQueryTimeout(deadline.requireJdbcSeconds());
        return bounded;
    }

    private TransactionTemplate boundedTransaction(RecoveryDeadline deadline) {
        TransactionTemplate bounded = new TransactionTemplate(read.getTransactionManager());
        bounded.setPropagationBehavior(read.getPropagationBehavior());
        bounded.setIsolationLevel(read.getIsolationLevel());
        bounded.setTimeout(deadline.requireJdbcSeconds());
        return bounded;
    }

    private void requireTestProfile() {
        String[] active = environment.getActiveProfiles();
        String[] defaults = environment.getDefaultProfiles();
        if (!Arrays.asList(active).contains("test") || Arrays.stream(active).anyMatch("prod"::equals)
                || Arrays.stream(defaults).anyMatch("prod"::equals)) {
            throw new SecurityException("synthetic journal readerはprodを含まないactive test profile専用です");
        }
    }
}
