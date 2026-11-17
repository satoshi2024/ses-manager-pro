package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.production.PrivacyFeatureProperties;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 独立 Journal インスタンスの起動時検査（NF07-A1J、journal-witness-grant-design.md §2.3）。
 *
 * <p>journal 設定があるときだけ接続して検査する（設定が無ければ接続を一度も試みない）。検査項目:
 * <ol>
 *   <li>Journal と application DB の {@code @@server_uuid} が異なる（同一 instance への誤接続を拒否する）。</li>
 *   <li>耐久・engine 設定: {@code innodb_flush_log_at_trx_commit=1}、{@code sync_binlog=1}、{@code log_bin=ON}、
 *       {@code read_only=OFF}、{@code super_read_only=OFF}、{@code default_storage_engine=InnoDB}、session time zone
 *       {@code +00:00}、Journal の5表が揃い全て InnoDB。</li>
 *   <li>{@code DATABASE()} が {@code privacy.journal.expected-schema} と一致する。</li>
 *   <li>自身の権限: {@code SHOW GRANTS} の文字列は解析せず、{@code information_schema} の
 *       {@code USER_PRIVILEGES}・{@code SCHEMA_PRIVILEGES}・{@code TABLE_PRIVILEGES}・{@code COLUMN_PRIVILEGES} を
 *       {@code CURRENT_USER()} の grantee で読み、§3.1 の許可集合（appender または reader）と表×操作の単位で突き合わせる。
 *       超過（global 権限、他 schema、列単位、GRANT OPTION、許可集合外の操作）も不足も不適合。role は使わない前提とし、
 *       {@code CURRENT_ROLE()} が {@code NONE} でなければ不適合（role 経由の権限は上の表に現れないため）。
 *       principal は1つ（総括決定の方式A）で、retention flag ON（hold 開始を使う）では appender が必須、flag OFF
 *       （既存 hold の効力確認だけ）では reader または appender。</li>
 *   <li>Journal 用 Flyway 履歴表（{@value PrivacyJournalMigrations#HISTORY_TABLE}）が artifact の migration と版・script 名・
 *       checksum で一致する（未適用・失敗行・artifact に無い版・適用後に変わった script は不適合）。application は
 *       migration を実行しない。</li>
 *   <li>application DB の {@code t_privacy_projection_state.journal_id} が設定と一致する（別 journal への誤接続の検出。
 *       行が無い＝hold 機構を一度も使っていない場合は一致とみなす）。</li>
 * </ol>
 * 照会できない項目も不適合として扱う（fail-closed）。結果は {@link #startupResult()} で参照できる（A1H の
 * {@code PrivacyHoldGate} が {@code UNAVAILABLE} の判断に使う）。
 *
 * <p>prod で retention flag ON なのに {@code AVAILABLE} でなければ（設定欠落を含む）起動を失敗させる（OFF へ倒さない）。
 * flag OFF では起動を止めず WARN（{@value #LOG_CODE_UNAVAILABLE}）を出す。prod 以外では flag ON でも起動を止めない
 * （本番 adapter は prod 以外で生成できず Journal を使う経路が無いため。test profile は既存 test の意味を保つため flag ON。
 * {@code PrivacyProductionConfigurationValidator} が prod だけで動くのと同じ）。理由は固定の code だけで出し、接続文字列・
 * user 名・password・例外 message は出さない。{@link SmartInitializingSingleton} で全 singleton 生成後・Tomcat の connector
 * 開始前に実行する。application DB へは読むだけで書かない。各照会には {@link #QUERY_TIMEOUT_SECONDS} 秒の query timeout を
 * 置き、Journal が TCP を受けて応答しない場合も pool の socket timeout で打ち切る（起動が止まり続けない）。
 *
 * <p>flag は決定ID束縛の有効期間を見ない生の {@code privacy.retention.enabled} で判定する（意図的。署名日前・失効で NF07 が
 * OFF として扱われている間も、実行中に有効期間へ入れば hold 開始が使われ得るため、起動時に Journal を要求する保守側に倒す）。
 */
public class PrivacyJournalStartupVerifier implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PrivacyJournalStartupVerifier.class);

    /** flag OFF のまま Journal を UNAVAILABLE として起動したことを示す固定コード（監視の log 検索用）。 */
    public static final String LOG_CODE_UNAVAILABLE = "NF07_PRIVACY_JOURNAL_UNAVAILABLE";

    /** 起動時検査の各照会（Journal・application DB とも）の query timeout。 */
    static final int QUERY_TIMEOUT_SECONDS = 10;

    /** 検査結果の分類。 */
    public enum Availability {
        /** journal 設定が無い（接続を試みていない）。 */
        NOT_CONFIGURED,
        /** 全項目に適合。 */
        AVAILABLE,
        /** 不適合または照会不能。gate は hold を使った tenant について UNAVAILABLE を返す（A1H）。 */
        UNAVAILABLE
    }

    /** 検査結果。{@code violations} は固定 code の昇順。 */
    public record Result(Availability availability, List<String> violations) {
        public Result {
            Objects.requireNonNull(availability, "availability");
            violations = List.copyOf(violations);
        }
    }

    /** §3.1 の principal。 */
    enum Principal { APPENDER, READER }

    /** 権限の範囲。 */
    enum Scope { GLOBAL, SCHEMA, TABLE, COLUMN }

    /** information_schema の権限1行。 */
    record GrantRow(Scope scope, String schema, String table, String privilege, boolean grantable) {
    }

    /** Journal 用 Flyway 履歴表の1行（checksum は NULL があり得る）。 */
    record HistoryRow(String version, String script, Integer checksum, boolean success) {
    }

    /** appender の許可集合（journal-witness-grant-design.md §3.2。表名 → 操作）。 */
    static final Map<String, Set<String>> APPENDER_TABLE_PRIVILEGES = appenderTablePrivileges();

    /** reader の許可集合（schema 単位の SELECT だけ）。 */
    static final Set<String> READER_SCHEMA_PRIVILEGES = Set.of("SELECT");

    private final PrivacyJournalConnections connections;
    private final PrivacyFeatureProperties featureProperties;
    private final Supplier<JdbcTemplate> applicationJdbc;
    private final boolean productionProfile;
    private volatile Result startupResult = new Result(Availability.NOT_CONFIGURED, List.of());

    /**
     * @param productionProfile prod profile が有効か（true のときだけ flag ON の不適合で起動を止める）
     */
    public PrivacyJournalStartupVerifier(PrivacyJournalConnections connections,
                                         PrivacyFeatureProperties featureProperties,
                                         Supplier<JdbcTemplate> applicationJdbc,
                                         boolean productionProfile) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.featureProperties = featureProperties;
        this.applicationJdbc = applicationJdbc == null ? () -> null : applicationJdbc;
        this.productionProfile = productionProfile;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Result result = verify();
        startupResult = result;
        enforce(result, holdStartRequired(), productionProfile);
    }

    /** 起動時検査の結果（起動前は {@code NOT_CONFIGURED}）。 */
    public Result startupResult() {
        return startupResult;
    }

    /** hold 開始を使う（{@code privacy.retention.enabled=true}）か。 */
    boolean holdStartRequired() {
        return featureProperties != null && featureProperties.getRetention() != null
                && featureProperties.getRetention().isEnabled();
    }

    Result verify() {
        if (!connections.isConfigured()) {
            return new Result(Availability.NOT_CONFIGURED, List.of());
        }
        if (!connections.contractViolations().isEmpty()) {
            return new Result(Availability.UNAVAILABLE,
                    connections.contractViolations().stream().map(code -> "contract." + code).sorted().toList());
        }
        JdbcTemplate application;
        try {
            application = applicationJdbc.get();
        } catch (RuntimeException e) {
            application = null;
        }
        List<String> violations = inspect(connections.dataSource(), application, connections.expectedSchema(),
                connections.journalId(), holdStartRequired(), PrivacyJournalMigrations::fromClasspath);
        return new Result(violations.isEmpty() ? Availability.AVAILABLE : Availability.UNAVAILABLE, violations);
    }

    /**
     * prod で flag ON なのに {@code AVAILABLE} でなければ起動を止める。それ以外は log だけ出す。
     * 例外・log には固定 code だけを入れる。
     */
    static void enforce(Result result, boolean holdStartRequired, boolean productionProfile) {
        String codes = String.join(",", result.violations());
        boolean failClosed = holdStartRequired && productionProfile;
        switch (result.availability()) {
            case NOT_CONFIGURED -> {
                if (failClosed) {
                    throw new IllegalStateException("[privacy-journal] privacy.retention.enabled=true ですが"
                            + " Journal 設定（privacy.journal.*）が無いため起動を中止します: journal.notConfigured");
                }
                log.info("[privacy-journal] Journal 設定が無いため接続せず、起動時検査を行いません");
            }
            case AVAILABLE -> log.info("[privacy-journal] Journal の起動時検査に適合しました");
            case UNAVAILABLE -> {
                if (failClosed) {
                    throw new IllegalStateException("[privacy-journal] privacy.retention.enabled=true ですが"
                            + " Journal の起動時検査に適合しないため起動を中止します: " + codes);
                }
                log.warn("[privacy-journal] {} Journal の起動時検査に適合しないため、hold の効力確認を UNAVAILABLE として"
                        + "扱います（app は起動を続けます）: {}", LOG_CODE_UNAVAILABLE, codes);
            }
        }
    }

    /**
     * Journal へ1接続だけ借りて §2.3 の 1〜5 を、application DB で 6 を検査する。
     * 接続できなければ {@code journal.unreachable} だけを返す（以降の照会をしない）。
     */
    static List<String> inspect(DataSource journalDataSource, JdbcTemplate application, String expectedSchema,
                                String journalId, boolean appenderRequired,
                                Supplier<List<PrivacyJournalMigrations.Migration>> artifactMigrations) {
        Set<String> violations = new TreeSet<>();
        Connection connection;
        try {
            connection = Objects.requireNonNull(journalDataSource, "journalDataSource").getConnection();
        } catch (SQLException | RuntimeException e) {
            return List.of("journal.unreachable");
        }
        JdbcTemplate checkedApplication = applicationTemplate(application);
        try {
            JdbcTemplate journal = journalTemplate(connection);
            checkInstance(journal, checkedApplication, violations);
            checkSchema(journal, expectedSchema, violations);
            checkPrivileges(journal, expectedSchema, appenderRequired, violations);
            checkFlywayHistory(journal, artifactMigrations, violations);
        } finally {
            try {
                connection.close();
            } catch (SQLException | RuntimeException ignored) {
                // 検査結果は確定している。close の失敗は結果を変えない
            }
        }
        checkProjectionState(checkedApplication, journalId, violations);
        return List.copyOf(violations);
    }

    /** 借りた1接続の上で、query timeout 付きで照会する JdbcTemplate。 */
    static JdbcTemplate journalTemplate(Connection connection) {
        JdbcTemplate journal = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        journal.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return journal;
    }

    /**
     * application DB の照会用 JdbcTemplate。共有 Bean の設定は変えず、同じ DataSource で query timeout 付きの写しを作る
     * （DataSource が取れない場合は渡されたものをそのまま使う）。
     */
    static JdbcTemplate applicationTemplate(JdbcTemplate application) {
        if (application == null) {
            return null;
        }
        DataSource dataSource = application.getDataSource();
        if (dataSource == null) {
            return application;
        }
        JdbcTemplate copy = new JdbcTemplate(dataSource);
        copy.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return copy;
    }

    private static void checkInstance(JdbcTemplate journal, JdbcTemplate application, Set<String> violations) {
        Map<String, Object> config;
        try {
            config = journal.queryForMap("SELECT @@server_uuid AS server_uuid, "
                    + "@@innodb_flush_log_at_trx_commit AS flush_commit, @@sync_binlog AS sync_binlog, "
                    + "@@log_bin AS log_bin, @@read_only AS read_only, @@super_read_only AS super_read_only, "
                    + "@@default_storage_engine AS storage_engine, @@session.time_zone AS session_time_zone");
        } catch (RuntimeException e) {
            violations.add("instance.unverifiable");
            return;
        }
        violations.addAll(instanceViolations(config));
        String journalUuid = config.get("server_uuid") == null ? null : config.get("server_uuid").toString();
        String applicationUuid;
        try {
            applicationUuid = application == null ? null
                    : application.queryForObject("SELECT @@server_uuid", String.class);
        } catch (RuntimeException e) {
            applicationUuid = null;
        }
        if (journalUuid == null || journalUuid.isBlank() || applicationUuid == null || applicationUuid.isBlank()) {
            violations.add("instance.serverUuid.unverifiable");
        } else if (journalUuid.trim().equalsIgnoreCase(applicationUuid.trim())) {
            violations.add("instance.sameServerAsApplication");
        }
    }

    /** 耐久・engine・session タイムゾーンの判定（§2.2）。 */
    static List<String> instanceViolations(Map<String, Object> config) {
        List<String> violations = new ArrayList<>();
        if (flag(config.get("flush_commit")) != 1) {
            violations.add("durability.innodbFlushLogAtTrxCommit");
        }
        if (flag(config.get("sync_binlog")) != 1) {
            violations.add("durability.syncBinlog");
        }
        if (flag(config.get("log_bin")) != 1) {
            violations.add("durability.logBin");
        }
        if (flag(config.get("read_only")) != 0) {
            violations.add("instance.readOnly");
        }
        if (flag(config.get("super_read_only")) != 0) {
            violations.add("instance.superReadOnly");
        }
        Object engine = config.get("storage_engine");
        if (engine == null || !"InnoDB".equalsIgnoreCase(engine.toString().trim())) {
            violations.add("instance.defaultStorageEngine");
        }
        Object timeZone = config.get("session_time_zone");
        if (timeZone == null || !"+00:00".equals(timeZone.toString().trim())) {
            violations.add("session.timeZone");
        }
        return violations;
    }

    private static void checkSchema(JdbcTemplate journal, String expectedSchema, Set<String> violations) {
        try {
            String database = journal.queryForObject("SELECT DATABASE()", String.class);
            if (database == null || !database.equals(expectedSchema)) {
                violations.add("schema.databaseMismatch");
            }
            Map<String, String> engines = new LinkedHashMap<>();
            journal.query("SELECT table_name, engine FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE()", rs -> {
                engines.put(rs.getString(1).toLowerCase(Locale.ROOT), rs.getString(2));
            });
            for (String table : ProductionPrivacyComponents.journalTables()) {
                String engine = engines.get(table);
                if (engine == null) {
                    violations.add("schema.tableMissing:" + table);
                } else if (!"InnoDB".equalsIgnoreCase(engine)) {
                    violations.add("schema.tableNotInnoDb:" + table);
                }
            }
        } catch (RuntimeException e) {
            violations.add("schema.unverifiable");
        }
    }

    private static void checkPrivileges(JdbcTemplate journal, String expectedSchema, boolean appenderRequired,
                                        Set<String> violations) {
        try {
            String currentUser = journal.queryForObject("SELECT CURRENT_USER()", String.class);
            String currentRole = journal.queryForObject("SELECT CURRENT_ROLE()", String.class);
            String grantee = grantee(currentUser);
            if (grantee == null) {
                violations.add("privilege.unverifiable");
                return;
            }
            List<GrantRow> grants = new ArrayList<>();
            grants.addAll(journal.query("SELECT PRIVILEGE_TYPE, IS_GRANTABLE FROM information_schema.USER_PRIVILEGES "
                            + "WHERE GRANTEE = ?",
                    (rs, row) -> new GrantRow(Scope.GLOBAL, null, null, rs.getString(1), yes(rs.getString(2))), grantee));
            if (grants.isEmpty()) {
                // 自身の global 行（権限が無ければ USAGE）が見えない＝判定できない
                violations.add("privilege.unverifiable");
                return;
            }
            grants.addAll(journal.query("SELECT TABLE_SCHEMA, PRIVILEGE_TYPE, IS_GRANTABLE "
                            + "FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE = ?",
                    (rs, row) -> new GrantRow(Scope.SCHEMA, rs.getString(1), null, rs.getString(2),
                            yes(rs.getString(3))), grantee));
            grants.addAll(journal.query("SELECT TABLE_SCHEMA, TABLE_NAME, PRIVILEGE_TYPE, IS_GRANTABLE "
                            + "FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE = ?",
                    (rs, row) -> new GrantRow(Scope.TABLE, rs.getString(1), rs.getString(2), rs.getString(3),
                            yes(rs.getString(4))), grantee));
            grants.addAll(journal.query("SELECT TABLE_SCHEMA, TABLE_NAME, PRIVILEGE_TYPE, IS_GRANTABLE "
                            + "FROM information_schema.COLUMN_PRIVILEGES WHERE GRANTEE = ?",
                    (rs, row) -> new GrantRow(Scope.COLUMN, rs.getString(1), rs.getString(2), rs.getString(3),
                            yes(rs.getString(4))), grantee));
            violations.addAll(privilegeViolations(currentRole, grants, expectedSchema, appenderRequired));
        } catch (RuntimeException e) {
            violations.add("privilege.unverifiable");
        }
    }

    /**
     * 権限の判定（§2.3 の 4、§3.1）。appender が必須でなければ、表単位の権限を1つも持たず schema 単位の権限を持つ
     * principal を reader として、それ以外を appender として許可集合と突き合わせる。
     */
    static List<String> privilegeViolations(String currentRole, List<GrantRow> grants, String expectedSchema,
                                            boolean appenderRequired) {
        Set<String> violations = new TreeSet<>();
        if (currentRole == null || !"NONE".equalsIgnoreCase(currentRole.trim())) {
            violations.add("privilege.roleActive");
        }
        Set<String> schemaPrivileges = new TreeSet<>();
        Map<String, Set<String>> tablePrivileges = new TreeMap<>();
        for (GrantRow grant : grants) {
            String privilege = grant.privilege() == null ? "" : grant.privilege().trim().toUpperCase(Locale.ROOT);
            if (grant.grantable()) {
                violations.add("privilege.grantOption");
            }
            switch (grant.scope()) {
                case GLOBAL -> {
                    if (!"USAGE".equals(privilege)) {
                        violations.add("privilege.global");
                    }
                }
                case SCHEMA -> {
                    if (!sameSchema(grant.schema(), expectedSchema)) {
                        violations.add("privilege.otherSchema");
                    } else {
                        schemaPrivileges.add(privilege);
                    }
                }
                case TABLE -> {
                    if (!sameSchema(grant.schema(), expectedSchema)) {
                        violations.add("privilege.otherSchema");
                    } else {
                        String table = grant.table() == null ? "" : grant.table().toLowerCase(Locale.ROOT);
                        tablePrivileges.computeIfAbsent(table, key -> new TreeSet<>()).add(privilege);
                    }
                }
                case COLUMN -> violations.add("privilege.column");
            }
        }
        Principal target = !appenderRequired && tablePrivileges.isEmpty() && !schemaPrivileges.isEmpty()
                ? Principal.READER : Principal.APPENDER;
        Set<String> expectedSchemaPrivileges = target == Principal.READER ? READER_SCHEMA_PRIVILEGES : Set.of();
        Map<String, Set<String>> expectedTablePrivileges =
                target == Principal.APPENDER ? APPENDER_TABLE_PRIVILEGES : Map.of();
        for (String privilege : schemaPrivileges) {
            if (!expectedSchemaPrivileges.contains(privilege)) {
                violations.add("privilege.excess:schema." + privilege);
            }
        }
        for (String privilege : expectedSchemaPrivileges) {
            if (!schemaPrivileges.contains(privilege)) {
                violations.add("privilege.missing:schema." + privilege);
            }
        }
        tablePrivileges.forEach((table, privileges) -> {
            Set<String> allowed = expectedTablePrivileges.getOrDefault(table, Set.of());
            for (String privilege : privileges) {
                if (!allowed.contains(privilege)) {
                    violations.add("privilege.excess:" + table + "." + privilege);
                }
            }
        });
        expectedTablePrivileges.forEach((table, privileges) -> {
            Set<String> granted = tablePrivileges.getOrDefault(table, Set.of());
            for (String privilege : privileges) {
                if (!granted.contains(privilege)) {
                    violations.add("privilege.missing:" + table + "." + privilege);
                }
            }
        });
        return List.copyOf(violations);
    }

    private static void checkFlywayHistory(JdbcTemplate journal,
                                           Supplier<List<PrivacyJournalMigrations.Migration>> artifactMigrations,
                                           Set<String> violations) {
        List<PrivacyJournalMigrations.Migration> artifact;
        try {
            artifact = artifactMigrations.get();
        } catch (RuntimeException e) {
            violations.add("flyway.artifactUnreadable");
            return;
        }
        List<HistoryRow> history;
        try {
            history = journal.query("SELECT version, script, checksum, success FROM "
                            + PrivacyJournalMigrations.HISTORY_TABLE,
                    (rs, row) -> {
                        int checksum = rs.getInt(3);
                        return new HistoryRow(rs.getString(1), rs.getString(2), rs.wasNull() ? null : checksum,
                                rs.getBoolean(4));
                    });
        } catch (RuntimeException e) {
            violations.add("flyway.historyUnreadable");
            return;
        }
        violations.addAll(historyViolations(artifact, history));
    }

    /** Journal 用 Flyway 履歴と artifact の migration の照合（§2.3 の 5）。 */
    static List<String> historyViolations(List<PrivacyJournalMigrations.Migration> artifact, List<HistoryRow> history) {
        Set<String> violations = new TreeSet<>();
        if (artifact == null || artifact.isEmpty()) {
            violations.add("flyway.artifactEmpty");
            return List.copyOf(violations);
        }
        Map<MigrationVersion, HistoryRow> applied = new LinkedHashMap<>();
        for (HistoryRow row : history) {
            if (!row.success()) {
                violations.add("flyway.failedMigration");
                continue;
            }
            if (row.version() == null || row.version().isBlank()) {
                continue;
            }
            try {
                applied.put(MigrationVersion.fromVersion(row.version().trim()), row);
            } catch (RuntimeException e) {
                violations.add("flyway.historyUnreadable");
            }
        }
        for (PrivacyJournalMigrations.Migration migration : artifact) {
            HistoryRow row = applied.get(migration.version());
            if (row == null) {
                violations.add("flyway.pending:" + migration.version());
            } else if (!migration.script().equals(row.script())) {
                violations.add("flyway.scriptMismatch:" + migration.version());
            } else if (row.checksum() == null || row.checksum() != migration.checksum()) {
                // 適用後に script が書き換えられた artifact（Flyway の validate と同じ判定）
                violations.add("flyway.checksumMismatch:" + migration.version());
            }
        }
        Set<MigrationVersion> known = new TreeSet<>();
        artifact.forEach(migration -> known.add(migration.version()));
        for (MigrationVersion version : applied.keySet()) {
            if (!known.contains(version)) {
                violations.add("flyway.unknownVersion:" + version);
            }
        }
        return List.copyOf(violations);
    }

    private static void checkProjectionState(JdbcTemplate application, String journalId, Set<String> violations) {
        if (application == null) {
            violations.add("projection.unverifiable");
            return;
        }
        try {
            Integer mismatched = application.queryForObject(
                    "SELECT COUNT(*) FROM t_privacy_projection_state WHERE journal_id <> ?", Integer.class, journalId);
            if (mismatched == null) {
                violations.add("projection.unverifiable");
            } else if (mismatched > 0) {
                violations.add("projection.journalIdMismatch");
            }
        } catch (RuntimeException e) {
            violations.add("projection.unverifiable");
        }
    }

    /** {@code CURRENT_USER()}（{@code user@host}）を information_schema の grantee 表記（{@code 'user'@'host'}）にする。 */
    static String grantee(String currentUser) {
        if (currentUser == null) {
            return null;
        }
        int at = currentUser.lastIndexOf('@');
        if (at <= 0 || currentUser.indexOf('\'') >= 0) {
            return null;
        }
        return "'" + currentUser.substring(0, at) + "'@'" + currentUser.substring(at + 1) + "'";
    }

    /** GRANT 文の schema 名の {@code \_}・{@code \%}（wildcard の escape）を外して比べる。 */
    private static boolean sameSchema(String granted, String expectedSchema) {
        if (granted == null || expectedSchema == null) {
            return false;
        }
        return granted.replace("\\_", "_").replace("\\%", "%").equals(expectedSchema);
    }

    private static boolean yes(String value) {
        return value != null && "YES".equalsIgnoreCase(value.trim());
    }

    /** system variable の数値・ON/OFF を 1/0 にする。解釈できなければ -1（不適合扱い）。 */
    private static int flag(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (value == null) {
            return -1;
        }
        String text = value.toString().trim();
        if ("ON".equalsIgnoreCase(text)) {
            return 1;
        }
        if ("OFF".equalsIgnoreCase(text)) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Map<String, Set<String>> appenderTablePrivileges() {
        Map<String, Set<String>> privileges = new TreeMap<>();
        privileges.put("pj_head", Set.of("SELECT", "INSERT", "UPDATE"));
        for (String table : List.of("pj_event", "pj_command", "pj_action", "pj_lease")) {
            privileges.put(table, Set.of("SELECT", "INSERT"));
        }
        privileges.put(PrivacyJournalMigrations.HISTORY_TABLE, Set.of("SELECT"));
        return java.util.Collections.unmodifiableMap(privileges);
    }
}
