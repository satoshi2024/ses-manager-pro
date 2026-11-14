package com.ses.privacy.foundation;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Synthetic W2 adapter。publicな接続生成経路やSpring bean登録は持たない。
 * DISPATCH_AUTHORIZEDは合成journal上のhold/CAS/lease判定であり、policy/case proofや送信許可ではない。
 */
final class JdbcPrivacyJournalAdapter implements PrivacyJournalPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final Environment environment;
    private final Clock clock;

    JdbcPrivacyJournalAdapter(DataSource dataSource, Environment environment, Clock clock) {
        this.environment = Objects.requireNonNull(environment, "environment");
        requireTestProfile();
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        var transactionManager = new DataSourceTransactionManager(dataSource);
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public JournalAck append(JournalCommand command) {
        requireTestProfile();
        requireNoAmbientTransaction();
        if (command.type() == CommandType.HOLD_RELEASED)
            throw new SecurityException("hold解除はW2aの権限モデル対象外のため拒否します");
        try {
            JournalAck ack = writeTransaction.execute(status -> appendInTransaction(command));
            if (ack == null) throw new JournalUnavailableException("journal ACKを確認できません", null);
            return ack;
        } catch (JournalConflictException | SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new JournalUnavailableException("journal durable appendに失敗しました", e);
        }
    }

    private JournalAck appendInTransaction(JournalCommand command) {
        requireDurableJournalConfiguration();
        var p = command.partition();
        // 重複行のINSERT IGNOREは共有duplicate-key lock後のFOR UPDATE昇格でdeadlockし得る。
        // upsertで先に排他ロックを取得してから同じheadを読む。
        jdbc.update("INSERT INTO sf_journal_head (tenant_id, subject_ref, revision, event_hash, held, lease_generation, lease_owner, lease_until) VALUES (?, ?, 0, ?, FALSE, 0, NULL, NULL) ON DUPLICATE KEY UPDATE tenant_id = VALUES(tenant_id)", p.tenantId(), p.subjectRef(), zeroHash());
        JournalHead head = lockedHead(p);
        verifyChain(p, head);
        Instant eventAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<JournalAck> prior = jdbc.query("SELECT payload_hash, revision, event_hash FROM sf_journal_command WHERE tenant_id = ? AND subject_ref = ? AND command_id = ?",
                (rs, row) -> {
                    if (!command.payloadHash().equals(rs.getString("payload_hash")))
                        throw new JournalConflictException("command IDの再利用内容が一致しません");
                    return new JournalAck(command.commandId(), rs.getLong("revision"), rs.getString("event_hash"), true);
                }, p.tenantId(), p.subjectRef(), command.commandId());
        if (!prior.isEmpty()) return prior.get(0);
        if (head.revision() != command.expectedRevision()) throw new JournalConflictException("journal revisionが競合しました");
        if (command.type() == CommandType.HOLD_STARTED && head.held()) throw new JournalConflictException("holdは既に有効です");
        if (command.type() == CommandType.HOLD_RELEASED && !head.held()) throw new JournalConflictException("解除対象holdがありません");
        if (command.type() == CommandType.DISPATCH_AUTHORIZED && head.held()) throw new JournalConflictException("hold中の新規認可を拒否します");
        if (command.type() == CommandType.DISPATCH_AUTHORIZED && (head.leaseGeneration() != command.expectedLeaseGeneration()
                || !command.workerId().equals(head.leaseOwner()) || head.leaseUntil() == null
                || !eventAt.isBefore(head.leaseUntil())))
            throw new JournalConflictException("staleまたは期限切れleaseによる認可を拒否します");
        if (command.type() == CommandType.DISPATCH_AUTHORIZED) {
            List<String> hashes = jdbc.query("SELECT request_hash FROM sf_journal_action WHERE tenant_id = ? AND subject_ref = ? AND action_id = ?",
                    (rs, row) -> rs.getString(1), p.tenantId(), p.subjectRef(), command.actionId());
            if (!hashes.isEmpty()) {
                if (!command.requestHash().equals(hashes.get(0))) throw new JournalConflictException("action IDのrequest hashが競合しました");
                throw new JournalConflictException("既存actionは別commandで再認可できません");
            }
        }
        if (command.type() == CommandType.LEASE_CLAIMED || command.type() == CommandType.LEASE_TAKEN_OVER) {
            if (head.leaseGeneration() != command.expectedLeaseGeneration()) throw new JournalConflictException("lease generationが競合しました");
            if (command.leaseUntil() == null || !eventAt.isBefore(command.leaseUntil())) throw new JournalConflictException("期限切れleaseを発行できません");
            if (command.type() == CommandType.LEASE_CLAIMED && head.leaseOwner() != null && head.leaseUntil() != null
                    && eventAt.isBefore(head.leaseUntil())) throw new JournalConflictException("有効leaseが残っています");
            if (command.type() == CommandType.LEASE_TAKEN_OVER && (head.leaseOwner() == null || head.leaseUntil() == null
                    || eventAt.isBefore(head.leaseUntil()))) throw new JournalConflictException("takeover可能な期限切れleaseがありません");
        }
        long revision = head.revision() + 1;
        String eventHash = hash(canonical(command, revision, head.eventHash(), eventAt));
        boolean held = command.type() == CommandType.HOLD_STARTED || (command.type() != CommandType.HOLD_RELEASED && head.held());
        long generation = (command.type() == CommandType.LEASE_CLAIMED || command.type() == CommandType.LEASE_TAKEN_OVER)
                ? head.leaseGeneration() + 1 : head.leaseGeneration();
        String owner = (command.type() == CommandType.LEASE_CLAIMED || command.type() == CommandType.LEASE_TAKEN_OVER)
                ? command.workerId() : head.leaseOwner();
        Instant leaseUntil = (command.type() == CommandType.LEASE_CLAIMED || command.type() == CommandType.LEASE_TAKEN_OVER)
                ? command.leaseUntil() : head.leaseUntil();
        jdbc.update("INSERT INTO sf_journal_event (tenant_id, subject_ref, revision, command_id, payload_hash, event_type, case_id, action_id, request_hash, worker_id, expected_lease_generation, lease_until, previous_hash, event_hash, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.tenantId(), p.subjectRef(), revision, command.commandId(), command.payloadHash(), command.type().name(),
                command.caseId(), command.actionId(), command.requestHash(), command.workerId(), command.expectedLeaseGeneration(),
                command.leaseUntil() == null ? null : Timestamp.from(command.leaseUntil()), head.eventHash(), eventHash, Timestamp.from(eventAt));
        if (command.type() == CommandType.DISPATCH_AUTHORIZED) {
            jdbc.update("INSERT INTO sf_journal_action (action_id, tenant_id, subject_ref, case_id, request_hash, authorization_revision) VALUES (?, ?, ?, ?, ?, ?)",
                    command.actionId(), p.tenantId(), p.subjectRef(), command.caseId(), command.requestHash(), revision);
        }
        if (command.type() == CommandType.LEASE_CLAIMED || command.type() == CommandType.LEASE_TAKEN_OVER) {
            jdbc.update("INSERT INTO sf_journal_lease (tenant_id, subject_ref, generation, worker_id, lease_until, event_revision) VALUES (?, ?, ?, ?, ?, ?)",
                    p.tenantId(), p.subjectRef(), generation, owner, Timestamp.from(leaseUntil), revision);
        }
        jdbc.update("INSERT INTO sf_journal_command (command_id, tenant_id, subject_ref, payload_hash, revision, event_hash) VALUES (?, ?, ?, ?, ?, ?)",
                command.commandId(), p.tenantId(), p.subjectRef(), command.payloadHash(), revision, eventHash);
        int updated = jdbc.update("UPDATE sf_journal_head SET revision = ?, event_hash = ?, held = ?, lease_generation = ?, lease_owner = ?, lease_until = ? WHERE tenant_id = ? AND subject_ref = ? AND revision = ?",
                revision, eventHash, held, generation, owner, leaseUntil == null ? null : Timestamp.from(leaseUntil),
                p.tenantId(), p.subjectRef(), head.revision());
        if (updated != 1) throw new JournalConflictException("journal head CASに失敗しました");
        return new JournalAck(command.commandId(), revision, eventHash, false);
    }

    private void requireDurableJournalConfiguration() {
        var config = jdbc.queryForMap("SELECT @@innodb_flush_log_at_trx_commit AS flush_commit, @@sync_binlog AS sync_binlog, @@log_bin AS log_bin, @@read_only AS read_only, @@super_read_only AS super_read_only, @@default_storage_engine AS storage_engine");
        if (((Number) config.get("flush_commit")).intValue() != 1
                || ((Number) config.get("sync_binlog")).intValue() != 1
                || ((Number) config.get("log_bin")).intValue() != 1
                || ((Number) config.get("read_only")).intValue() != 0
                || ((Number) config.get("super_read_only")).intValue() != 0
                || !"InnoDB".equalsIgnoreCase(config.get("storage_engine").toString()))
            throw new JournalUnavailableException("journal primaryのdurability設定が必要条件を満たしません", null);
        Integer tables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('sf_journal_head','sf_journal_event','sf_journal_command','sf_journal_action','sf_journal_lease') AND engine = 'InnoDB'", Integer.class);
        if (tables == null || tables != 5)
            throw new JournalUnavailableException("journal schemaにInnoDB primary tableが揃っていません", null);
    }

    @Override
    public JournalHead head(JournalPartition partition) {
        requireTestProfile();
        requireNoAmbientTransaction();
        return readTransaction.execute(status -> {
            JournalHead result = selectHead(partition);
            verifyChain(partition, result);
            return result;
        });
    }

    @Override
    public List<JournalEvent> events(JournalPartition partition) {
        requireTestProfile();
        requireNoAmbientTransaction();
        return readTransaction.execute(status -> {
            JournalHead current = selectHead(partition);
            List<JournalEvent> currentEvents = selectEvents(partition);
            verifyChain(partition, current, currentEvents);
            return currentEvents;
        });
    }

    private JournalHead selectHead(JournalPartition partition) {
        List<JournalHead> rows = jdbc.query("SELECT revision, event_hash, held, lease_generation, lease_owner, lease_until FROM sf_journal_head WHERE tenant_id = ? AND subject_ref = ?",
                (rs, row) -> new JournalHead(partition, rs.getLong("revision"), rs.getString("event_hash"), rs.getBoolean("held"),
                        rs.getLong("lease_generation"), rs.getString("lease_owner"), rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant()),
                partition.tenantId(), partition.subjectRef());
        return rows.isEmpty() ? new JournalHead(partition, 0, zeroHash(), false, 0, null, null) : rows.get(0);
    }

    private List<JournalEvent> selectEvents(JournalPartition partition) {
        return jdbc.query("SELECT revision, command_id, payload_hash, event_type, case_id, action_id, request_hash, worker_id, expected_lease_generation, lease_until, previous_hash, event_hash, created_at FROM sf_journal_event WHERE tenant_id = ? AND subject_ref = ? ORDER BY revision",
                (rs, row) -> new JournalEvent(rs.getLong("revision"), rs.getString("command_id"), rs.getString("payload_hash"),
                        CommandType.valueOf(rs.getString("event_type")), rs.getString("case_id"), rs.getString("action_id"),
                        rs.getString("request_hash"), rs.getString("worker_id"), rs.getLong("expected_lease_generation"),
                        rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant(),
                        rs.getString("previous_hash"), rs.getString("event_hash"), rs.getTimestamp("created_at").toInstant()),
                partition.tenantId(), partition.subjectRef());
    }

    private JournalHead lockedHead(JournalPartition partition) {
        return jdbc.queryForObject("SELECT revision, event_hash, held, lease_generation, lease_owner, lease_until FROM sf_journal_head WHERE tenant_id = ? AND subject_ref = ? FOR UPDATE",
                (rs, row) -> new JournalHead(partition, rs.getLong("revision"), rs.getString("event_hash"), rs.getBoolean("held"),
                        rs.getLong("lease_generation"), rs.getString("lease_owner"), rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant()),
                partition.tenantId(), partition.subjectRef());
    }

    private void verifyChain(JournalPartition partition, JournalHead head) {
        verifyChain(partition, head, jdbc.query("SELECT revision, command_id, payload_hash, event_type, case_id, action_id, request_hash, worker_id, expected_lease_generation, lease_until, previous_hash, event_hash, created_at FROM sf_journal_event WHERE tenant_id = ? AND subject_ref = ? ORDER BY revision",
                (rs, row) -> new JournalEvent(rs.getLong("revision"), rs.getString("command_id"), rs.getString("payload_hash"),
                        CommandType.valueOf(rs.getString("event_type")), rs.getString("case_id"), rs.getString("action_id"),
                        rs.getString("request_hash"), rs.getString("worker_id"), rs.getLong("expected_lease_generation"),
                        rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant(),
                        rs.getString("previous_hash"), rs.getString("event_hash"), rs.getTimestamp("created_at").toInstant()),
                partition.tenantId(), partition.subjectRef()));
    }

    private void verifyChain(JournalPartition partition, JournalHead head, List<JournalEvent> events) {
        String previous = zeroHash();
        long revision = 1;
        boolean held = false;
        long leaseGeneration = 0;
        String leaseOwner = null;
        Instant leaseUntil = null;
        for (JournalEvent event : events) {
            if (event.revision() != revision || !previous.equals(event.previousHash())
                    || !hash(canonical(event, partition, previous)).equals(event.eventHash())) {
                throw new JournalUnavailableException("journal event chainが不正です", null);
            }
            if (event.type() == CommandType.HOLD_STARTED) {
                if (held) throw new JournalUnavailableException("重複hold eventを検出しました", null);
                held = true;
            } else if (event.type() == CommandType.HOLD_RELEASED) {
                throw new JournalUnavailableException("未実装のhold解除eventを検出しました", null);
            } else if (event.type() == CommandType.LEASE_CLAIMED || event.type() == CommandType.LEASE_TAKEN_OVER) {
                if (event.expectedLeaseGeneration() != leaseGeneration || event.workerId() == null || event.leaseUntil() == null
                        || (event.type() == CommandType.LEASE_CLAIMED && leaseOwner != null && event.createdAt().isBefore(leaseUntil))
                        || (event.type() == CommandType.LEASE_TAKEN_OVER && (leaseOwner == null || leaseUntil == null
                        || event.createdAt().isBefore(leaseUntil))) || !event.createdAt().isBefore(event.leaseUntil()))
                    throw new JournalUnavailableException("lease event projectionが不正です", null);
                leaseGeneration++;
                leaseOwner = event.workerId();
                leaseUntil = event.leaseUntil();
            } else if (event.type() == CommandType.DISPATCH_AUTHORIZED) {
                if (held || event.expectedLeaseGeneration() != leaseGeneration || leaseOwner == null
                        || !leaseOwner.equals(event.workerId()) || leaseUntil == null || !event.createdAt().isBefore(leaseUntil))
                    throw new JournalUnavailableException("認可eventのhold/lease projectionが不正です", null);
            }
            previous = event.eventHash();
            revision++;
        }
        long lastRevision = revision - 1;
        if (head.revision() != lastRevision || !head.eventHash().equals(previous) || head.held() != held
                || head.leaseGeneration() != leaseGeneration || !Objects.equals(head.leaseOwner(), leaseOwner)
                || !Objects.equals(head.leaseUntil(), leaseUntil)) {
            throw new JournalUnavailableException("journal headとevent chainが一致しません", null);
        }
        verifyProjections(partition, events);
    }

    private void verifyProjections(JournalPartition partition, List<JournalEvent> events) {
        var commands = jdbc.query("SELECT command_id, payload_hash, revision, event_hash FROM sf_journal_command WHERE tenant_id = ? AND subject_ref = ?",
                (rs, row) -> List.of(rs.getString("command_id"), rs.getString("payload_hash"),
                        Long.toString(rs.getLong("revision")), rs.getString("event_hash")), partition.tenantId(), partition.subjectRef());
        var eventCommands = events.stream().map(e -> List.of(e.commandId(), e.payloadHash(), Long.toString(e.revision()), e.eventHash())).toList();
        if (commands.size() != eventCommands.size() || !commands.containsAll(eventCommands))
            throw new JournalUnavailableException("command projectionとevent正本が一致しません", null);

        var actions = jdbc.query("SELECT action_id, case_id, request_hash, authorization_revision FROM sf_journal_action WHERE tenant_id = ? AND subject_ref = ?",
                (rs, row) -> List.of(rs.getString("action_id"), rs.getString("case_id"), rs.getString("request_hash"),
                        Long.toString(rs.getLong("authorization_revision"))), partition.tenantId(), partition.subjectRef());
        var eventActions = events.stream().filter(e -> e.type() == CommandType.DISPATCH_AUTHORIZED)
                .map(e -> List.of(e.actionId(), e.caseId(), e.requestHash(), Long.toString(e.revision()))).toList();
        if (actions.size() != eventActions.size() || !actions.containsAll(eventActions))
            throw new JournalUnavailableException("action projectionとevent正本が一致しません", null);

        var leases = jdbc.query("SELECT generation, worker_id, lease_until, event_revision FROM sf_journal_lease WHERE tenant_id = ? AND subject_ref = ?",
                (rs, row) -> List.of(Long.toString(rs.getLong("generation")), rs.getString("worker_id"),
                        rs.getTimestamp("lease_until").toInstant().toString(), Long.toString(rs.getLong("event_revision"))),
                partition.tenantId(), partition.subjectRef());
        var eventLeases = events.stream().filter(e -> e.type() == CommandType.LEASE_CLAIMED || e.type() == CommandType.LEASE_TAKEN_OVER)
                .map(e -> List.of(Long.toString(e.expectedLeaseGeneration() + 1), e.workerId(), e.leaseUntil().toString(), Long.toString(e.revision()))).toList();
        if (leases.size() != eventLeases.size() || !leases.containsAll(eventLeases))
            throw new JournalUnavailableException("lease projectionとevent正本が一致しません", null);
    }

    private void requireTestProfile() {
        String[] active = environment.getActiveProfiles();
        String[] defaults = environment.getDefaultProfiles();
        boolean hasProd = java.util.stream.Stream.concat(java.util.Arrays.stream(active), java.util.Arrays.stream(defaults)).anyMatch("prod"::equals);
        if (hasProd || !java.util.Arrays.asList(active).contains("test")) throw new SecurityException("synthetic journalはactive test profile専用です");
    }

    private void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("durable journal portはambient transaction内から呼び出せません");
    }

    private static String canonical(JournalCommand c, long revision, String previous, Instant eventAt) {
        StringBuilder bytes = new StringBuilder();
        field(bytes, "SES-SF-W2-EVENT-V1");
        field(bytes, c.partition().tenantId());
        field(bytes, c.partition().subjectRef());
        field(bytes, Long.toString(revision));
        field(bytes, c.commandId());
        field(bytes, c.payloadHash());
        field(bytes, c.type().name());
        field(bytes, c.caseId());
        field(bytes, c.actionId());
        field(bytes, c.requestHash());
        field(bytes, previous);
        field(bytes, c.workerId());
        field(bytes, Long.toString(c.expectedLeaseGeneration()));
        field(bytes, c.leaseUntil() == null ? null : c.leaseUntil().toString());
        field(bytes, eventAt.toString());
        return bytes.toString();
    }

    private static String canonical(JournalEvent e, JournalPartition partition, String previous) {
        StringBuilder bytes = new StringBuilder();
        field(bytes, "SES-SF-W2-EVENT-V1");
        field(bytes, partition.tenantId());
        field(bytes, partition.subjectRef());
        field(bytes, Long.toString(e.revision()));
        field(bytes, e.commandId());
        field(bytes, e.payloadHash());
        field(bytes, e.type().name());
        field(bytes, e.caseId());
        field(bytes, e.actionId());
        field(bytes, e.requestHash());
        field(bytes, previous);
        field(bytes, e.workerId());
        field(bytes, Long.toString(e.expectedLeaseGeneration()));
        field(bytes, e.leaseUntil() == null ? null : e.leaseUntil().toString());
        field(bytes, e.createdAt().toString());
        return bytes.toString();
    }
    private static void field(StringBuilder target, String value) {
        if (value == null) target.append("-1:");
        else target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }
    private static String zeroHash() { return "0".repeat(64); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256が利用できません", e); }
    }
}
