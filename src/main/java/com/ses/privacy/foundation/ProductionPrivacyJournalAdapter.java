package com.ses.privacy.foundation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 独立 Journal インスタンス（{@code privacy_journal} の {@code pj_*} 表）への本番 adapter（NF07-A1J、W-6 の code 側）。
 *
 * <p>合成 {@link JdbcPrivacyJournalAdapter} とその {@code requireTestProfile()} には手を入れない（I-6）。共通部の抽出も
 * 合成層のコードを変えるため行わず、JDBC 処理は本番用に別に書く。代わりに次を合成 adapter と同一に保つ:
 * <ul>
 *   <li>command の payload hash（{@link PrivacyJournalPort.JournalCommand} の既存計算をそのまま使う）</li>
 *   <li>event hash の canonical 形式（domain {@value #EVENT_DOMAIN} を含む長さ付き field 列と SHA-256）</li>
 *   <li>append の判定順（replay → revision CAS → hold・lease・認可の規則）と、読み取り時の chain・projection 検証</li>
 * </ul>
 * 同一入力で合成 adapter と hash chain が一致することは {@code ProductionPrivacyJournalAdapterMySqlTest} が固定する
 * （重複したコードの乖離はそこで検出する）。
 *
 * <p>生成は {@link ProductionPrivacyComponents#productionPrivacyJournal} 経由だけ（package-private。prod profile 以外では
 * 生成できない）。生成時に DB へ触れない。状態変更は Journal 上の単一短 transaction で行い、ambient transaction 内からの
 * 呼出しは拒否する。transaction には {@link #APPEND_TRANSACTION_TIMEOUT} の上限を置き、各 statement の timeout は
 * その残り時間から設定される（journal-witness-grant-design.md §2.4）。ACK を確認できない失敗は成功・失敗のどちらとも
 * 推定せず {@link JournalUnavailableException} を返し、呼出し側は同じ command ID で照会・再送する（再送は元の ACK を返す）。
 * hold 解除（{@code HOLD_RELEASED}）は {@link #append} では常に拒否し、検証済みの proof（{@link VerifiedHoldRelease}）を
 * 受け取る {@link #appendHoldRelease} だけが append する（NF07-REL）。解除の append は同じ transaction の中で head を lock し、
 * command の再送（元の ACK）→ revision CAS → proof の判定（{@link VerifiedHoldRelease#requireAppendable}: journal head・
 * hold revision・hold ID・期限）の順に確かめる。合成 adapter の解除の拒否は変えない（I-6）。
 */
final class ProductionPrivacyJournalAdapter implements PrivacyJournalPort, HoldReleaseJournalPort {

    /** append・読み取りの transaction 上限。{@code privacy.journal.pending-timeout} はこれより長くなければならない。 */
    static final Duration APPEND_TRANSACTION_TIMEOUT = Duration.ofSeconds(10);

    /** 本番 Journal の表（合成 schema の sf_journal_* と同じ形）。 */
    static final List<String> JOURNAL_TABLES = List.of("pj_head", "pj_event", "pj_command", "pj_action", "pj_lease");

    /** event hash の domain。合成 adapter と hash chain を一致させるため同じ値にする（変更は Plan Review を要する）。 */
    private static final String EVENT_DOMAIN = "SES-SF-W2-EVENT-V1";

    private static final String EVENT_COLUMNS = "revision, command_id, payload_hash, event_type, case_id, action_id, "
            + "request_hash, worker_id, expected_lease_generation, lease_until, previous_hash, event_hash, created_at";
    private static final String HEAD_COLUMNS = "revision, event_hash, held, lease_generation, lease_owner, lease_until";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    ProductionPrivacyJournalAdapter(DataSource journalDataSource, ProductionEnvironmentGuard guard, Clock clock) {
        Objects.requireNonNull(guard, "guard").requireProductionProfile();
        DataSource dataSource = Objects.requireNonNull(journalDataSource, "journalDataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        int timeoutSeconds = (int) APPEND_TRANSACTION_TIMEOUT.toSeconds();
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.writeTransaction.setTimeout(timeoutSeconds);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setTimeout(timeoutSeconds);
    }

    @Override
    public JournalAck append(JournalCommand command) {
        Objects.requireNonNull(command, "command");
        requireNoAmbientTransaction();
        if (command.type() == CommandType.HOLD_RELEASED) {
            throw new SecurityException("hold解除は検証済みの解除proof（appendHoldRelease）でだけappendします");
        }
        return appendDurably(command, null);
    }

    @Override
    public JournalAck appendHoldRelease(VerifiedHoldRelease release) {
        Objects.requireNonNull(release, "release");
        requireNoAmbientTransaction();
        return appendDurably(release.command(), release);
    }

    private JournalAck appendDurably(JournalCommand command, VerifiedHoldRelease release) {
        try {
            JournalAck ack = writeTransaction.execute(status -> appendInTransaction(command, release));
            if (ack == null) {
                throw new JournalUnavailableException("journal ACKを確認できません", null);
            }
            return ack;
        } catch (JournalConflictException | SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            if (JournalAccessDeniedException.isAccessDenied(e)) {
                // 権限の拒否は文の実行の前に返り transaction は rollback される（append は起きていない）。ACK 不明にせず確定の拒否にする
                // （呼出し側は pending を戻す。review rb-fe-c P3-1）
                throw new JournalAccessDeniedException("journal principalにappendの権限がありません", null);
            }
            throw new JournalUnavailableException("journal durable appendに失敗しました", e);
        }
    }

    @Override
    public JournalHead head(JournalPartition partition) {
        Objects.requireNonNull(partition, "partition");
        requireNoAmbientTransaction();
        return readOnly(() -> {
            JournalHead result = selectHead(partition);
            verifyChain(partition, result, selectEvents(partition));
            return result;
        });
    }

    @Override
    public List<JournalEvent> events(JournalPartition partition) {
        Objects.requireNonNull(partition, "partition");
        requireNoAmbientTransaction();
        return readOnly(() -> {
            JournalHead current = selectHead(partition);
            List<JournalEvent> currentEvents = selectEvents(partition);
            verifyChain(partition, current, currentEvents);
            return currentEvents;
        });
    }

    private <T> T readOnly(java.util.function.Supplier<T> body) {
        try {
            return readTransaction.execute(status -> body.get());
        } catch (JournalUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new JournalUnavailableException("journalを照会できません", e);
        }
    }

    private JournalAck appendInTransaction(JournalCommand command, VerifiedHoldRelease release) {
        requireDurableJournalConfiguration();
        JournalPartition p = command.partition();
        // 重複行の INSERT IGNORE は共有 duplicate-key lock 後の FOR UPDATE 昇格で deadlock し得るため、
        // upsert で先に排他 lock を取得してから同じ head を読む（合成 adapter と同じ）。
        jdbc.update("INSERT INTO pj_head (tenant_id, subject_ref, revision, event_hash, held, lease_generation, "
                        + "lease_owner, lease_until) VALUES (?, ?, 0, ?, FALSE, 0, NULL, NULL) "
                        + "ON DUPLICATE KEY UPDATE tenant_id = VALUES(tenant_id)",
                p.tenantId(), p.subjectRef(), zeroHash());
        JournalHead head = jdbc.queryForObject("SELECT " + HEAD_COLUMNS
                        + " FROM pj_head WHERE tenant_id = ? AND subject_ref = ? FOR UPDATE",
                headMapper(p), p.tenantId(), p.subjectRef());
        List<JournalEvent> events = selectEvents(p);
        verifyChain(p, head, events);
        Instant eventAt = clock.instant().truncatedTo(ChronoUnit.MICROS);

        List<JournalAck> prior = jdbc.query("SELECT payload_hash, revision, event_hash FROM pj_command "
                        + "WHERE tenant_id = ? AND subject_ref = ? AND command_id = ?",
                (rs, row) -> {
                    if (!command.payloadHash().equals(rs.getString("payload_hash"))) {
                        throw new JournalConflictException("command IDの再利用内容が一致しません");
                    }
                    return new JournalAck(command.commandId(), rs.getLong("revision"), rs.getString("event_hash"), true);
                }, p.tenantId(), p.subjectRef(), command.commandId());
        if (!prior.isEmpty()) {
            return prior.get(0);
        }
        requireAppendAllowed(command, head, events, eventAt, release);

        boolean leaseCommand = isLeaseCommand(command.type());
        long revision = head.revision() + 1;
        String eventHash = hash(canonical(p, revision, command.commandId(), command.payloadHash(), command.type(),
                command.caseId(), command.actionId(), command.requestHash(), head.eventHash(), command.workerId(),
                command.expectedLeaseGeneration(), command.leaseUntil(), eventAt));
        boolean held = command.type() == CommandType.HOLD_STARTED
                || (command.type() != CommandType.HOLD_RELEASED && head.held());
        long generation = leaseCommand ? head.leaseGeneration() + 1 : head.leaseGeneration();
        String owner = leaseCommand ? command.workerId() : head.leaseOwner();
        Instant leaseUntil = leaseCommand ? command.leaseUntil() : head.leaseUntil();

        jdbc.update("INSERT INTO pj_event (tenant_id, subject_ref, revision, command_id, payload_hash, event_type, "
                        + "case_id, action_id, request_hash, worker_id, expected_lease_generation, lease_until, "
                        + "previous_hash, event_hash, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.tenantId(), p.subjectRef(), revision, command.commandId(), command.payloadHash(),
                command.type().name(), command.caseId(), command.actionId(), command.requestHash(),
                command.workerId(), command.expectedLeaseGeneration(), timestamp(command.leaseUntil()),
                head.eventHash(), eventHash, Timestamp.from(eventAt));
        if (command.type() == CommandType.DISPATCH_AUTHORIZED) {
            jdbc.update("INSERT INTO pj_action (action_id, tenant_id, subject_ref, case_id, request_hash, "
                            + "authorization_revision) VALUES (?, ?, ?, ?, ?, ?)",
                    command.actionId(), p.tenantId(), p.subjectRef(), command.caseId(), command.requestHash(), revision);
        }
        if (leaseCommand) {
            jdbc.update("INSERT INTO pj_lease (tenant_id, subject_ref, generation, worker_id, lease_until, "
                            + "event_revision) VALUES (?, ?, ?, ?, ?, ?)",
                    p.tenantId(), p.subjectRef(), generation, owner, Timestamp.from(leaseUntil), revision);
        }
        jdbc.update("INSERT INTO pj_command (command_id, tenant_id, subject_ref, payload_hash, revision, event_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                command.commandId(), p.tenantId(), p.subjectRef(), command.payloadHash(), revision, eventHash);
        int updated = jdbc.update("UPDATE pj_head SET revision = ?, event_hash = ?, held = ?, lease_generation = ?, "
                        + "lease_owner = ?, lease_until = ? WHERE tenant_id = ? AND subject_ref = ? AND revision = ?",
                revision, eventHash, held, generation, owner, timestamp(leaseUntil),
                p.tenantId(), p.subjectRef(), head.revision());
        if (updated != 1) {
            throw new JournalConflictException("journal head CASに失敗しました");
        }
        return new JournalAck(command.commandId(), revision, eventHash, false);
    }

    /**
     * revision CAS と hold・lease・認可の規則（HOLD_RELEASED 以外は合成 adapter と同じ判定）。HOLD_RELEASED は検証済みの
     * proof が無ければ拒否し、proof の判定（head・hold revision・hold ID・期限）を通ったときだけ許す（NF07-REL）。
     */
    private void requireAppendAllowed(JournalCommand command, JournalHead head, List<JournalEvent> events,
                                      Instant eventAt, VerifiedHoldRelease release) {
        CommandType type = command.type();
        if (head.revision() != command.expectedRevision()) {
            throw new JournalConflictException("journal revisionが競合しました");
        }
        if (type == CommandType.HOLD_STARTED && head.held()) {
            throw new JournalConflictException("holdは既に有効です");
        }
        if (type == CommandType.HOLD_RELEASED) {
            if (release == null || !release.command().equals(command)) {
                throw new SecurityException("hold解除は検証済みの解除proofでだけappendします");
            }
            release.requireAppendable(head, events, eventAt);
        }
        if (type == CommandType.DISPATCH_AUTHORIZED) {
            if (head.held()) {
                throw new JournalConflictException("hold中の新規認可を拒否します");
            }
            if (head.leaseGeneration() != command.expectedLeaseGeneration()
                    || !command.workerId().equals(head.leaseOwner()) || head.leaseUntil() == null
                    || !eventAt.isBefore(head.leaseUntil())) {
                throw new JournalConflictException("staleまたは期限切れleaseによる認可を拒否します");
            }
            List<String> hashes = jdbc.query("SELECT request_hash FROM pj_action "
                            + "WHERE tenant_id = ? AND subject_ref = ? AND action_id = ?",
                    (rs, row) -> rs.getString(1), command.partition().tenantId(), command.partition().subjectRef(),
                    command.actionId());
            if (!hashes.isEmpty()) {
                if (!command.requestHash().equals(hashes.get(0))) {
                    throw new JournalConflictException("action IDのrequest hashが競合しました");
                }
                throw new JournalConflictException("既存actionは別commandで再認可できません");
            }
        }
        if (isLeaseCommand(type)) {
            if (head.leaseGeneration() != command.expectedLeaseGeneration()) {
                throw new JournalConflictException("lease generationが競合しました");
            }
            if (command.leaseUntil() == null || !eventAt.isBefore(command.leaseUntil())) {
                throw new JournalConflictException("期限切れleaseを発行できません");
            }
            if (type == CommandType.LEASE_CLAIMED && head.leaseOwner() != null && head.leaseUntil() != null
                    && eventAt.isBefore(head.leaseUntil())) {
                throw new JournalConflictException("有効leaseが残っています");
            }
            if (type == CommandType.LEASE_TAKEN_OVER && (head.leaseOwner() == null || head.leaseUntil() == null
                    || eventAt.isBefore(head.leaseUntil()))) {
                throw new JournalConflictException("takeover可能な期限切れleaseがありません");
            }
        }
    }

    /** append のたびに primary の耐久設定と表の engine を確かめる（実行中の設定変更も検出する）。 */
    private void requireDurableJournalConfiguration() {
        Map<String, Object> config = jdbc.queryForMap("SELECT @@innodb_flush_log_at_trx_commit AS flush_commit, "
                + "@@sync_binlog AS sync_binlog, @@log_bin AS log_bin, @@read_only AS read_only, "
                + "@@super_read_only AS super_read_only, @@default_storage_engine AS storage_engine");
        if (((Number) config.get("flush_commit")).intValue() != 1
                || ((Number) config.get("sync_binlog")).intValue() != 1
                || ((Number) config.get("log_bin")).intValue() != 1
                || ((Number) config.get("read_only")).intValue() != 0
                || ((Number) config.get("super_read_only")).intValue() != 0
                || !"InnoDB".equalsIgnoreCase(String.valueOf(config.get("storage_engine")))) {
            throw new JournalUnavailableException("journal primaryのdurability設定が必要条件を満たしません", null);
        }
        Integer tables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND engine = 'InnoDB' AND table_name IN "
                + "('pj_head','pj_event','pj_command','pj_action','pj_lease')", Integer.class);
        if (tables == null || tables != JOURNAL_TABLES.size()) {
            throw new JournalUnavailableException("journal schemaにInnoDB primary tableが揃っていません", null);
        }
    }

    private JournalHead selectHead(JournalPartition partition) {
        List<JournalHead> rows = jdbc.query("SELECT " + HEAD_COLUMNS
                        + " FROM pj_head WHERE tenant_id = ? AND subject_ref = ?",
                headMapper(partition), partition.tenantId(), partition.subjectRef());
        return rows.isEmpty() ? new JournalHead(partition, 0, zeroHash(), false, 0, null, null) : rows.get(0);
    }

    private List<JournalEvent> selectEvents(JournalPartition partition) {
        return jdbc.query("SELECT " + EVENT_COLUMNS
                        + " FROM pj_event WHERE tenant_id = ? AND subject_ref = ? ORDER BY revision",
                (rs, row) -> new JournalEvent(rs.getLong("revision"), rs.getString("command_id"),
                        rs.getString("payload_hash"), CommandType.valueOf(rs.getString("event_type")),
                        rs.getString("case_id"), rs.getString("action_id"), rs.getString("request_hash"),
                        rs.getString("worker_id"), rs.getLong("expected_lease_generation"),
                        instant(rs.getTimestamp("lease_until")), rs.getString("previous_hash"),
                        rs.getString("event_hash"), rs.getTimestamp("created_at").toInstant()),
                partition.tenantId(), partition.subjectRef());
    }

    private static RowMapper<JournalHead> headMapper(JournalPartition partition) {
        return (rs, row) -> new JournalHead(partition, rs.getLong("revision"), rs.getString("event_hash"),
                rs.getBoolean("held"), rs.getLong("lease_generation"), rs.getString("lease_owner"),
                instant(rs.getTimestamp("lease_until")));
    }

    /** event 正本から head・hold・lease を再計算し、head と各 projection 表が一致することを確かめる。 */
    private void verifyChain(JournalPartition partition, JournalHead head, List<JournalEvent> events) {
        String previous = zeroHash();
        long revision = 1;
        boolean held = false;
        String activeHoldId = null;
        long leaseGeneration = 0;
        String leaseOwner = null;
        Instant leaseUntil = null;
        for (JournalEvent event : events) {
            String expected = hash(canonical(partition, event.revision(), event.commandId(), event.payloadHash(),
                    event.type(), event.caseId(), event.actionId(), event.requestHash(), previous, event.workerId(),
                    event.expectedLeaseGeneration(), event.leaseUntil(), event.createdAt()));
            if (event.revision() != revision || !previous.equals(event.previousHash())
                    || !expected.equals(event.eventHash())) {
                throw new JournalUnavailableException("journal event chainが不正です", null);
            }
            if (event.type() == CommandType.HOLD_STARTED) {
                if (held) {
                    throw new JournalUnavailableException("重複hold eventを検出しました", null);
                }
                held = true;
                activeHoldId = event.caseId();
            } else if (event.type() == CommandType.HOLD_RELEASED) {
                // 解除は有効な hold に対してだけ、その hold ID（case_id）で1回（NF07-REL）
                if (!held || event.caseId() == null || !event.caseId().equals(activeHoldId)) {
                    throw new JournalUnavailableException("有効なholdに対応しないhold解除eventを検出しました", null);
                }
                // 解除は claim の digest（action_id）を持つ（REL review P3-6。verifier の proof からだけ append する）
                if (event.actionId() == null || !event.actionId().matches("[0-9a-f]{64}")) {
                    throw new JournalUnavailableException("claimのdigestを持たないhold解除eventを検出しました", null);
                }
                held = false;
                activeHoldId = null;
            } else if (isLeaseCommand(event.type())) {
                if (event.expectedLeaseGeneration() != leaseGeneration || event.workerId() == null
                        || event.leaseUntil() == null
                        || (event.type() == CommandType.LEASE_CLAIMED && leaseOwner != null
                        && event.createdAt().isBefore(leaseUntil))
                        || (event.type() == CommandType.LEASE_TAKEN_OVER && (leaseOwner == null || leaseUntil == null
                        || event.createdAt().isBefore(leaseUntil)))
                        || !event.createdAt().isBefore(event.leaseUntil())) {
                    throw new JournalUnavailableException("lease event projectionが不正です", null);
                }
                leaseGeneration++;
                leaseOwner = event.workerId();
                leaseUntil = event.leaseUntil();
            } else if (event.type() == CommandType.DISPATCH_AUTHORIZED) {
                if (held || event.expectedLeaseGeneration() != leaseGeneration || leaseOwner == null
                        || !leaseOwner.equals(event.workerId()) || leaseUntil == null
                        || !event.createdAt().isBefore(leaseUntil)) {
                    throw new JournalUnavailableException("認可eventのhold/lease projectionが不正です", null);
                }
            }
            previous = event.eventHash();
            revision++;
        }
        if (head.revision() != revision - 1 || !head.eventHash().equals(previous) || head.held() != held
                || head.leaseGeneration() != leaseGeneration || !Objects.equals(head.leaseOwner(), leaseOwner)
                || !Objects.equals(head.leaseUntil(), leaseUntil)) {
            throw new JournalUnavailableException("journal headとevent chainが一致しません", null);
        }
        verifyProjections(partition, events);
    }

    private void verifyProjections(JournalPartition partition, List<JournalEvent> events) {
        List<List<String>> commands = jdbc.query("SELECT command_id, payload_hash, revision, event_hash FROM pj_command "
                        + "WHERE tenant_id = ? AND subject_ref = ?",
                (rs, row) -> List.of(rs.getString("command_id"), rs.getString("payload_hash"),
                        Long.toString(rs.getLong("revision")), rs.getString("event_hash")),
                partition.tenantId(), partition.subjectRef());
        List<List<String>> eventCommands = events.stream().map(e -> List.of(e.commandId(), e.payloadHash(),
                Long.toString(e.revision()), e.eventHash())).toList();
        if (commands.size() != eventCommands.size() || !commands.containsAll(eventCommands)) {
            throw new JournalUnavailableException("command projectionとevent正本が一致しません", null);
        }

        List<List<String>> actions = jdbc.query("SELECT action_id, case_id, request_hash, authorization_revision "
                        + "FROM pj_action WHERE tenant_id = ? AND subject_ref = ?",
                (rs, row) -> List.of(rs.getString("action_id"), rs.getString("case_id"), rs.getString("request_hash"),
                        Long.toString(rs.getLong("authorization_revision"))),
                partition.tenantId(), partition.subjectRef());
        List<List<String>> eventActions = events.stream().filter(e -> e.type() == CommandType.DISPATCH_AUTHORIZED)
                .map(e -> List.of(e.actionId(), e.caseId(), e.requestHash(), Long.toString(e.revision()))).toList();
        if (actions.size() != eventActions.size() || !actions.containsAll(eventActions)) {
            throw new JournalUnavailableException("action projectionとevent正本が一致しません", null);
        }

        List<List<String>> leases = jdbc.query("SELECT generation, worker_id, lease_until, event_revision FROM pj_lease "
                        + "WHERE tenant_id = ? AND subject_ref = ?",
                (rs, row) -> List.of(Long.toString(rs.getLong("generation")), rs.getString("worker_id"),
                        rs.getTimestamp("lease_until").toInstant().toString(),
                        Long.toString(rs.getLong("event_revision"))),
                partition.tenantId(), partition.subjectRef());
        List<List<String>> eventLeases = events.stream().filter(e -> isLeaseCommand(e.type()))
                .map(e -> List.of(Long.toString(e.expectedLeaseGeneration() + 1), e.workerId(),
                        e.leaseUntil().toString(), Long.toString(e.revision()))).toList();
        if (leases.size() != eventLeases.size() || !leases.containsAll(eventLeases)) {
            throw new JournalUnavailableException("lease projectionとevent正本が一致しません", null);
        }
    }

    private static void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("durable journal portはambient transaction内から呼び出せません");
        }
    }

    private static boolean isLeaseCommand(CommandType type) {
        return type == CommandType.LEASE_CLAIMED || type == CommandType.LEASE_TAKEN_OVER;
    }

    /** 合成 adapter の event canonical と同じ field 順・同じ符号化（長さ付き、null は -1）。 */
    private static String canonical(JournalPartition partition, long revision, String commandId, String payloadHash,
                                    CommandType type, String caseId, String actionId, String requestHash,
                                    String previous, String workerId, long expectedLeaseGeneration,
                                    Instant leaseUntil, Instant eventAt) {
        StringBuilder bytes = new StringBuilder();
        field(bytes, EVENT_DOMAIN);
        field(bytes, partition.tenantId());
        field(bytes, partition.subjectRef());
        field(bytes, Long.toString(revision));
        field(bytes, commandId);
        field(bytes, payloadHash);
        field(bytes, type.name());
        field(bytes, caseId);
        field(bytes, actionId);
        field(bytes, requestHash);
        field(bytes, previous);
        field(bytes, workerId);
        field(bytes, Long.toString(expectedLeaseGeneration));
        field(bytes, leaseUntil == null ? null : leaseUntil.toString());
        field(bytes, eventAt.toString());
        return bytes.toString();
    }

    private static void field(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String zeroHash() {
        return "0".repeat(64);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }
}
