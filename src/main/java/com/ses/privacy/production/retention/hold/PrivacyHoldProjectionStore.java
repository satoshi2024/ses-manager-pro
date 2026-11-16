package com.ses.privacy.production.retention.hold;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * application DB の hold projection（{@code t_legal_hold}）と追随位置（{@code t_privacy_projection_state}）の読み書き（NF07-A1H）。
 *
 * <p>MyBatis を通さず JDBC で読む（同じ transaction で lock を取った後に読み直すとき、session の 1 次 cache が古い値を返さない
 * ようにするため。F2 Review §7 (a)）。lock 後に読み直す照会は locking read（{@code FOR UPDATE}）にする（InnoDB の
 * REPEATABLE READ では通常の SELECT は transaction 開始時の snapshot を返すため。同 (b)）。日時は JST（{@code Asia/Tokyo}）の
 * 業務時刻で書く（application の他の DATETIME 列と同じ）。
 *
 * <p>{@code last_applied_seq} と {@code last_applied_hash} は tenant の projection 全体の要約である。hold は対象ごとの
 * Journal partition に入るため（partition の head は1つの hold ごと）、tenant の追随位置は「反映済みの HOLD_STARTED event の
 * 件数」と、その (partition, revision, event hash, hold ID) の集合の順序に依らない digest（{@link #digest}）で表す。gate は
 * projection 側と Journal 側の両方からこの digest を計算して一致を確かめる。
 */
final class PrivacyHoldProjectionStore {

    static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    static final String STATUS_ACTIVE = "ACTIVE";
    private static final String DIGEST_DOMAIN = "NF07-HOLD-PROJECTION-V1";

    private static final String STATE_COLUMNS = "tenant_id, journal_id, last_applied_seq, last_applied_hash, "
            + "pending_command_id, pending_since, row_version";
    private static final String HOLD_COLUMNS = "hold_id, target_type, subject_ref, data_element_id, status, reason_code, "
            + "ticket_ref, requested_by, requested_at, journal_revision, journal_event_hash";

    private final JdbcTemplate jdbc;

    PrivacyHoldProjectionStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** tenant の追随位置の行（journal ごと。通常は0件か1件）。lock しない。 */
    List<StateRow> stateRows(String tenantId) {
        return jdbc.query("SELECT " + STATE_COLUMNS + " FROM t_privacy_projection_state WHERE tenant_id = ?",
                STATE_MAPPER, tenantId);
    }

    /** tenant の追随位置の行を locking read で読む（呼出し側の transaction 内）。 */
    List<StateRow> lockStateRows(String tenantId) {
        return jdbc.query("SELECT " + STATE_COLUMNS + " FROM t_privacy_projection_state WHERE tenant_id = ? FOR UPDATE",
                STATE_MAPPER, tenantId);
    }

    /** 設定の journal の行を locking read で読む。 */
    Optional<StateRow> lockState(String tenantId, String journalId) {
        return jdbc.query("SELECT " + STATE_COLUMNS + " FROM t_privacy_projection_state "
                        + "WHERE tenant_id = ? AND journal_id = ? FOR UPDATE", STATE_MAPPER, tenantId, journalId)
                .stream().findFirst();
    }

    /**
     * 行が無ければ作り、あれば排他 lock だけを取る（重複時に共有 lock から排他 lock へ昇格させないため、INSERT IGNORE ではなく
     * no-op の upsert で先に排他 lock を取る。Journal adapter と同じ理由）。
     */
    void ensureStateLocked(String tenantId, String journalId, Instant now) {
        jdbc.update("INSERT INTO t_privacy_projection_state (tenant_id, journal_id, engaged_at, last_applied_seq, row_version) "
                        + "VALUES (?, ?, ?, 0, 0) ON DUPLICATE KEY UPDATE row_version = row_version",
                tenantId, journalId, local(now));
    }

    /** pending を CAS で記録する（他の command が pending なら0件）。時刻は micro 秒に切り詰めて書く（DATETIME(6) と一致させる）。 */
    int recordPending(String tenantId, String journalId, String commandId, Instant since) {
        return jdbc.update("UPDATE t_privacy_projection_state SET pending_command_id = ?, pending_since = ?, "
                        + "row_version = row_version + 1 WHERE tenant_id = ? AND journal_id = ? AND pending_command_id IS NULL",
                commandId, local(micros(since)), tenantId, journalId);
    }

    /**
     * reconciler が timeout を判定したときの {@code pending_since} のままのときだけ pending を消す。判定の後に開始側が再送の直前の
     * CAS（{@link #refreshPending}）で時刻を進めていれば0件になり、消さない（Review P2-1）。
     */
    int clearExpiredPending(String tenantId, String journalId, String commandId, Instant observedSince) {
        return jdbc.update("UPDATE t_privacy_projection_state SET pending_command_id = NULL, pending_since = NULL, "
                        + "row_version = row_version + 1 WHERE tenant_id = ? AND journal_id = ? AND pending_command_id = ? "
                        + "AND pending_since = ?",
                tenantId, journalId, commandId, local(observedSince));
    }

    /**
     * 指定の command が pending のときだけ {@code pending_since} を進める（ACK 不明の後の再送の直前。Review P2-1）。
     * 0件なら（reconciler が既に消した等）再送してはならない。
     */
    int refreshPending(String tenantId, String journalId, String commandId, Instant since) {
        return jdbc.update("UPDATE t_privacy_projection_state SET pending_since = ?, row_version = row_version + 1 "
                        + "WHERE tenant_id = ? AND journal_id = ? AND pending_command_id = ?",
                local(micros(since)), tenantId, journalId, commandId);
    }

    /** 指定の command が pending のときだけ消す。 */
    int clearPending(String tenantId, String journalId, String commandId) {
        return jdbc.update("UPDATE t_privacy_projection_state SET pending_command_id = NULL, pending_since = NULL, "
                        + "row_version = row_version + 1 WHERE tenant_id = ? AND journal_id = ? AND pending_command_id = ?",
                tenantId, journalId, commandId);
    }

    /**
     * 追随位置を projection の現状に合わせ、{@code clearCommandId} が pending ならその pending も消す（null なら pending に触れない）。
     */
    void advance(String tenantId, String journalId, long seq, String hash, String clearCommandId, Instant verifiedAt) {
        jdbc.update("UPDATE t_privacy_projection_state SET last_applied_seq = ?, last_applied_hash = ?, verified_at = ?, "
                        + "row_version = row_version + 1 WHERE tenant_id = ? AND journal_id = ?",
                seq, hash, local(verifiedAt), tenantId, journalId);
        if (clearCommandId != null) {
            clearPending(tenantId, journalId, clearCommandId);
        }
    }

    /** 照合が一致したことを記録する（追随位置は変えない。pending が残る間は記録しない）。 */
    void markVerified(String tenantId, String journalId, long seq, Instant verifiedAt) {
        jdbc.update("UPDATE t_privacy_projection_state SET verified_at = ? WHERE tenant_id = ? AND journal_id = ? "
                + "AND last_applied_seq = ? AND pending_command_id IS NULL", local(verifiedAt), tenantId, journalId, seq);
    }

    /** tenant の hold projection（状態を問わない）。 */
    List<HoldRow> holds(String tenantId, boolean lock) {
        return jdbc.query("SELECT " + HOLD_COLUMNS + " FROM t_legal_hold WHERE tenant_id = ? ORDER BY id"
                + (lock ? " FOR UPDATE" : ""), HOLD_MAPPER, tenantId);
    }

    /** 同じ対象の ACTIVE な hold があるか（locking read）。 */
    boolean activeHoldExists(String tenantId, PrivacyHoldTargetType type, String target) {
        String column = type == PrivacyHoldTargetType.SUBJECT ? "subject_ref" : "data_element_id";
        List<String> ids = jdbc.query("SELECT hold_id FROM t_legal_hold WHERE tenant_id = ? AND target_type = ? AND "
                        + column + " = ? AND status = 'ACTIVE' FOR UPDATE",
                (rs, row) -> rs.getString(1), tenantId, type.name(), target);
        return !ids.isEmpty();
    }

    /** hold ID の projection があるか（locking read）。 */
    boolean holdExists(String tenantId, String holdId) {
        return !jdbc.query("SELECT hold_id FROM t_legal_hold WHERE tenant_id = ? AND hold_id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), tenantId, holdId).isEmpty();
    }

    /**
     * reconciler が先に {@code RECONCILED} の印で復元した行を、同じ event の開始者自身の値で置き換える（Review P3-2）。
     * 同じ hold ID・revision・event hash で、まだ {@code RECONCILED} の行だけが対象（開始者の値を後から上書きしない）。
     */
    int adoptReconciledMetadata(String tenantId, NewHold hold) {
        return jdbc.update("UPDATE t_legal_hold SET reason_code = ?, reason_hash = ?, ticket_ref = ?, requested_by = ?, "
                        + "requested_at = ? WHERE tenant_id = ? AND hold_id = ? AND journal_revision = ? "
                        + "AND journal_event_hash = ? AND reason_code = ?",
                hold.reasonCode(), hold.reasonHash(), hold.ticketRef(), hold.requestedBy(), local(hold.requestedAt()),
                tenantId, hold.holdId(), hold.journalRevision(), hold.journalEventHash(), PrivacyHoldService.RECONCILED);
    }

    /** ACK 済みの hold を projection へ書く。 */
    void insertHold(String tenantId, NewHold hold) {
        jdbc.update("INSERT INTO t_legal_hold (tenant_id, hold_id, target_type, subject_ref, data_element_id, hold_kind, status, "
                        + "reason_code, reason_hash, ticket_ref, requested_by, requested_at, journal_revision, journal_event_hash) "
                        + "VALUES (?, ?, ?, ?, ?, 'UNKNOWN', 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)",
                tenantId, hold.holdId(), hold.targetType().name(),
                hold.targetType() == PrivacyHoldTargetType.SUBJECT ? hold.target() : null,
                hold.targetType() == PrivacyHoldTargetType.DATA_ELEMENT ? hold.target() : null,
                hold.reasonCode(), hold.reasonHash(), hold.ticketRef(), hold.requestedBy(), local(hold.requestedAt()),
                hold.journalRevision(), hold.journalEventHash());
    }

    /** 設定の journal に追随位置の行がある tenant。 */
    List<String> tenantsWithState(String journalId) {
        return jdbc.query("SELECT tenant_id FROM t_privacy_projection_state WHERE journal_id = ? ORDER BY tenant_id",
                (rs, row) -> rs.getString(1), journalId);
    }

    /** projection の digest（{@link #digest}）。 */
    static String projectionDigest(Collection<HoldRow> holds) {
        List<DigestEntry> entries = new ArrayList<>();
        for (HoldRow hold : holds) {
            entries.add(new DigestEntry(hold.partitionRef(), hold.journalRevision(), hold.journalEventHash(), hold.holdId()));
        }
        return digest(entries);
    }

    /**
     * 反映済み hold の集合の digest。各要素を長さ付き field にして辞書順に並べ、domain 付きで SHA-256 を取る（順序に依らない）。
     * 空集合は null（{@code last_applied_hash} の初期値と同じ）。
     */
    static String digest(Collection<DigestEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (DigestEntry entry : entries) {
            StringBuilder line = new StringBuilder();
            field(line, entry.partitionRef());
            field(line, Long.toString(entry.revision()));
            field(line, entry.eventHash());
            field(line, entry.holdId());
            lines.add(line.toString());
        }
        lines.sort(null);
        StringBuilder canonical = new StringBuilder();
        field(canonical, DIGEST_DOMAIN);
        for (String line : lines) {
            canonical.append(line).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    private static void field(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
    }

    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    static LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZONE);
    }

    static Instant instant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }

    private static final RowMapper<StateRow> STATE_MAPPER = (rs, row) -> new StateRow(rs.getString("tenant_id"),
            rs.getString("journal_id"), rs.getLong("last_applied_seq"), rs.getString("last_applied_hash"),
            rs.getString("pending_command_id"), instant(rs.getObject("pending_since", LocalDateTime.class)),
            rs.getLong("row_version"));

    private static final RowMapper<HoldRow> HOLD_MAPPER = (rs, row) -> new HoldRow(rs.getString("hold_id"),
            PrivacyHoldTargetType.parse(rs.getString("target_type")).orElse(null), rs.getString("subject_ref"),
            rs.getString("data_element_id"), rs.getString("status"), rs.getString("reason_code"),
            rs.getString("ticket_ref"), rs.getLong("requested_by"),
            instant(rs.getObject("requested_at", LocalDateTime.class)), rs.getLong("journal_revision"),
            rs.getString("journal_event_hash"));

    /** 追随位置の1行。 */
    record StateRow(String tenantId, String journalId, long lastAppliedSeq, String lastAppliedHash,
                    String pendingCommandId, Instant pendingSince, long rowVersion) {
    }

    /** projection の1行。{@code targetType} が解釈できない行は null（gate は不一致として扱う）。 */
    record HoldRow(String holdId, PrivacyHoldTargetType targetType, String subjectRef, String dataElementId,
                   String status, String reasonCode, String ticketRef, long requestedBy, Instant requestedAt,
                   long journalRevision, String journalEventHash) {

        /** Journal 上の partition の参照（{@link PrivacyHoldPartitions}）。解釈できない行は null。 */
        String partitionRef() {
            if (targetType == PrivacyHoldTargetType.SUBJECT) {
                return PrivacyHoldPartitions.subjectPartition(subjectRef);
            }
            if (targetType == PrivacyHoldTargetType.DATA_ELEMENT) {
                return PrivacyHoldPartitions.dataElementPartition(dataElementId);
            }
            return null;
        }

        boolean active() {
            return STATUS_ACTIVE.equals(status);
        }
    }

    /** projection へ書く hold。{@code target} は SUBJECT なら subject 参照、DATA_ELEMENT なら data element ID。 */
    record NewHold(String holdId, PrivacyHoldTargetType targetType, String target, String reasonCode,
                   String reasonHash, String ticketRef, long requestedBy, Instant requestedAt, long journalRevision,
                   String journalEventHash) {
    }

    /** digest の1要素。 */
    record DigestEntry(String partitionRef, long revision, String eventHash, String holdId) {
    }
}
