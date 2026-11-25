package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.ProductionPrivacyComponents;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 *       principal は1つ（総括決定の方式A）。hold の効力の結果では reader または appender、開放の条件の結果（hold 開始を使う）では
 *       appender が必須（下の2つの結果）。</li>
 *   <li>Journal 用 Flyway 履歴表（{@value PrivacyJournalMigrations#HISTORY_TABLE}）が artifact の migration と版・script 名・
 *       checksum で一致する（未適用・失敗行・artifact に無い版・適用後に変わった script は不適合）。application は
 *       migration を実行しない。</li>
 *   <li>application DB の {@code t_privacy_projection_state.journal_id} が設定と一致する（別 journal への誤接続の検出。
 *       行が無い＝hold 機構を一度も使っていない場合は一致とみなす）。</li>
 * </ol>
 * 照会できない項目も不適合として扱う（fail-closed）。
 *
 * <p>結果は2つに分ける（feature-enablement-console design §8.5 の 3。review P2-4。FE-C03）。どちらも機能の開閉（flag・開放状態）を
 * 読まない。</p>
 * <ul>
 *   <li><b>hold の効力の結果</b>（{@link #protectionResult()}。{@link #startupResult()} も同じ値）: reader（{@code nf07_journal_reader}）と
 *       appender のどちらの principal でも {@code AVAILABLE}（appender を要求しない判定）。起動時に検査し、{@link #reverify()} は成功した
 *       時だけ更新して失敗では下げない。hold gate（{@code PrivacyHoldJournal#availability}）はこれを読む。したがって開閉・principal の
 *       種類・再検査の一時の失敗で hold gate が {@code UNAVAILABLE} になり、既存の hold を守るために文書の処分・retention の purge・
 *       孤児 file の掃除が止まることは無い（保持・保全を閉じた後も hold は保護を続ける）。</li>
 *   <li><b>開放の条件の結果</b>（{@link #enablementResult()}）: appender の権限を要求する。{@link #reverify()}（承認の時と定期の評価。
 *       間隔の下限 {@code app.feature-enablement.journal-reverify-interval}）は成否のどちらでも更新する。保持・保全の開放の条件
 *       （要件 §6.11 の (e)）と hold の開始（append）の判定がこれを読む。{@code journal.unreachable} は評価できない類。</li>
 * </ul>
 *
 * <p>起動は止めない（prod でも WARN。{@value #LOG_CODE_UNAVAILABLE}・{@value #LOG_CODE_ENABLEMENT_UNAVAILABLE}。以前は prod で
 * retention flag ON なら不適合で起動を止めた）。Journal の接続の設定は起動時に要る（{@link PrivacyJournalConnections}）ため、設定の
 * 追加は配備の設定の変更と再起動を要する。理由は固定の code だけで出し、接続文字列・user 名・password・例外 message は出さない。
 * {@link SmartInitializingSingleton} で全 singleton 生成後・Tomcat の connector 開始前に実行する。application DB へは読むだけで
 * 書かない。各照会には {@link #QUERY_TIMEOUT_SECONDS} 秒の query timeout を置き、Journal が TCP を受けて応答しない場合も pool の
 * socket timeout で打ち切る（起動が止まり続けない）。</p>
 */
public class PrivacyJournalStartupVerifier implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PrivacyJournalStartupVerifier.class);

    /** hold の効力の結果が UNAVAILABLE のまま起動したことを示す固定コード（監視の log 検索用）。 */
    public static final String LOG_CODE_UNAVAILABLE = "NF07_PRIVACY_JOURNAL_UNAVAILABLE";

    /** 開放の条件の結果（appender）が UNAVAILABLE のまま起動したことを示す固定コード（保持・保全は開けない・開いていれば閉じる）。 */
    public static final String LOG_CODE_ENABLEMENT_UNAVAILABLE = "NF07_PRIVACY_JOURNAL_APPENDER_UNAVAILABLE";

    /** 再検査の間隔の下限の既定（{@code app.feature-enablement.journal-reverify-interval}）。 */
    static final Duration DEFAULT_REVERIFY_INTERVAL = Duration.ofMinutes(10);

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

    /** 1回の検査の2つの結果（hold の効力・開放の条件）。 */
    record Inspection(List<String> protection, List<String> enablement) {
        Inspection {
            protection = List.copyOf(protection);
            enablement = List.copyOf(enablement);
        }
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
    private final Supplier<JdbcTemplate> applicationJdbc;
    private final boolean productionProfile;
    private final Duration reverifyInterval;
    private final Clock clock;
    private volatile Result protectionResult = new Result(Availability.NOT_CONFIGURED, List.of());
    private volatile Result enablementResult = new Result(Availability.NOT_CONFIGURED, List.of());
    private volatile Instant lastVerifiedAt;
    private final Object reverifyLock = new Object();
    private final Supplier<Inspection> inspector;

    /**
     * @param productionProfile prod profile が有効か（log の文言だけに使う。起動は止めない）
     */
    public PrivacyJournalStartupVerifier(PrivacyJournalConnections connections, Supplier<JdbcTemplate> applicationJdbc,
                                         boolean productionProfile) {
        this(connections, applicationJdbc, productionProfile, DEFAULT_REVERIFY_INTERVAL, Clock.systemUTC());
    }

    /**
     * @param reverifyInterval {@link #reverify()} の間隔の下限（前回の検査からこの時間が経つまでは前回の結果を返す）
     */
    public PrivacyJournalStartupVerifier(PrivacyJournalConnections connections, Supplier<JdbcTemplate> applicationJdbc,
                                         boolean productionProfile, Duration reverifyInterval, Clock clock) {
        this(connections, applicationJdbc, productionProfile, reverifyInterval, clock, null);
    }

    /** 試験用: 検査を差し替える（{@code inspector} が null なら Journal へ接続して検査する）。 */
    PrivacyJournalStartupVerifier(PrivacyJournalConnections connections, Supplier<JdbcTemplate> applicationJdbc,
                                  boolean productionProfile, Duration reverifyInterval, Clock clock,
                                  Supplier<Inspection> inspector) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.applicationJdbc = applicationJdbc == null ? () -> null : applicationJdbc;
        this.productionProfile = productionProfile;
        this.reverifyInterval = reverifyInterval == null || reverifyInterval.isNegative()
                ? DEFAULT_REVERIFY_INTERVAL : reverifyInterval;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.inspector = inspector;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Inspection inspection = inspectNow();
        Result protection = result(inspection.protection());
        Result enablement = result(inspection.enablement());
        protectionResult = protection;
        enablementResult = enablement;
        lastVerifiedAt = clock.instant();
        enforce(protection, enablement, productionProfile);
    }

    /** hold の効力の結果（{@link #protectionResult()} と同じ。起動前は {@code NOT_CONFIGURED}）。 */
    public Result startupResult() {
        return protectionResult;
    }

    /** hold の効力の結果（reader・appender のどちらでも可。再検査は成功した時だけ更新する）。 */
    public Result protectionResult() {
        return protectionResult;
    }

    /** 開放の条件の結果（appender を要する。再検査のたびに更新する）。 */
    public Result enablementResult() {
        return enablementResult;
    }

    /**
     * 再検査する（承認の時と定期の評価で呼ぶ）。前回の検査から間隔の下限が経っていなければ照会せずに前回の開放の条件の結果を返す。
     * 開放の条件の結果は成否のどちらでも更新し、hold の効力の結果は {@code AVAILABLE} になった時だけ更新する（一時の失敗で
     * hold gate を下げない。起動時に使えなかった場合も成功すれば回復する）。
     */
    public Result reverify() {
        synchronized (reverifyLock) {
            Instant now = clock.instant();
            Instant last = lastVerifiedAt;
            if (last != null && now.isBefore(last.plus(reverifyInterval))) {
                return enablementResult;
            }
            Inspection inspection = inspectNow();
            Result protection = result(inspection.protection());
            Result enablement = result(inspection.enablement());
            enablementResult = enablement;
            if (protection.availability() == Availability.AVAILABLE) {
                protectionResult = protection;
            }
            lastVerifiedAt = now;
            return enablement;
        }
    }

    /** 開放の条件の結果を検査する（{@code appenderRequired=true}。互換のため残す）。 */
    Result verify() {
        return result(verifyBoth().enablement());
    }

    private Result result(List<String> violations) {
        if (!connections.isConfigured()) {
            return new Result(Availability.NOT_CONFIGURED, List.of());
        }
        return new Result(violations.isEmpty() ? Availability.AVAILABLE : Availability.UNAVAILABLE, violations);
    }

    private Inspection inspectNow() {
        return inspector == null ? verifyBoth() : inspector.get();
    }

    Inspection verifyBoth() {
        if (!connections.isConfigured()) {
            return new Inspection(List.of(), List.of());
        }
        if (!connections.contractViolations().isEmpty()) {
            List<String> contract = connections.contractViolations().stream().map(code -> "contract." + code).sorted()
                    .toList();
            return new Inspection(contract, contract);
        }
        JdbcTemplate application;
        try {
            application = applicationJdbc.get();
        } catch (RuntimeException e) {
            application = null;
        }
        return inspectBoth(connections.dataSource(), application, connections.expectedSchema(), connections.journalId(),
                PrivacyJournalMigrations::fromClasspath);
    }

    /**
     * 起動を止めず、結果を log に出す（固定 code だけ）。hold の効力の結果が使えなければ WARN（hold を使った tenant の破壊的経路は
     * gate が止める）、開放の条件の結果だけが使えなければ WARN（保持・保全は開けない・開いていれば閉じる）。
     */
    static void enforce(Result protection, Result enablement, boolean productionProfile) {
        switch (protection.availability()) {
            case NOT_CONFIGURED -> log.info("[privacy-journal] Journal 設定が無いため接続せず、起動時検査を行いません"
                    + "（保持・保全は開放の条件を満たしません）");
            case AVAILABLE -> {
                if (enablement.availability() == Availability.AVAILABLE) {
                    log.info("[privacy-journal] Journal の起動時検査に適合しました（hold の効力・開放の条件とも）");
                } else {
                    log.warn("[privacy-journal] {} Journal は hold の効力の確認に使えますが、appender の権限が無いため保持・保全の"
                                    + "開放の条件を満たしません（prod={}。起動は続けます）: {}", LOG_CODE_ENABLEMENT_UNAVAILABLE,
                            productionProfile, String.join(",", enablement.violations()));
                }
            }
            case UNAVAILABLE -> log.warn("[privacy-journal] {} Journal の起動時検査に適合しないため、hold の効力確認を UNAVAILABLE"
                    + "として扱います（prod={}。app は起動を続けます）: {}", LOG_CODE_UNAVAILABLE, productionProfile,
                    String.join(",", protection.violations()));
        }
    }

    /**
     * Journal へ1接続だけ借りて §2.3 の 1〜5 を、application DB で 6 を検査する（{@code appenderRequired} で選んだ側の結果）。
     * 接続できなければ {@code journal.unreachable} だけを返す（以降の照会をしない）。
     */
    static List<String> inspect(DataSource journalDataSource, JdbcTemplate application, String expectedSchema,
                                String journalId, boolean appenderRequired,
                                Supplier<List<PrivacyJournalMigrations.Migration>> artifactMigrations) {
        Inspection inspection = inspectBoth(journalDataSource, application, expectedSchema, journalId,
                artifactMigrations);
        return appenderRequired ? inspection.enablement() : inspection.protection();
    }

    /**
     * 1回の接続で2つの結果を作る。権限は同じ照会の結果を、appender を要求しない判定（hold の効力）と要求する判定（開放の条件）の
     * 2通りで突き合わせる。権限以外の項目は両方に入る。
     */
    static Inspection inspectBoth(DataSource journalDataSource, JdbcTemplate application, String expectedSchema,
                                  String journalId, Supplier<List<PrivacyJournalMigrations.Migration>> artifactMigrations) {
        Set<String> common = new TreeSet<>();
        Set<String> protection = new TreeSet<>();
        Set<String> enablement = new TreeSet<>();
        Connection connection;
        try {
            connection = Objects.requireNonNull(journalDataSource, "journalDataSource").getConnection();
        } catch (SQLException | RuntimeException e) {
            return new Inspection(List.of("journal.unreachable"), List.of("journal.unreachable"));
        }
        JdbcTemplate checkedApplication = applicationTemplate(application);
        try {
            JdbcTemplate journal = journalTemplate(connection);
            checkInstance(journal, checkedApplication, common);
            checkSchema(journal, expectedSchema, common);
            Privileges privileges = readPrivileges(journal, common);
            if (privileges != null) {
                protection.addAll(privilegeViolations(privileges.currentRole(), privileges.grants(), expectedSchema,
                        false));
                enablement.addAll(privilegeViolations(privileges.currentRole(), privileges.grants(), expectedSchema,
                        true));
            }
            checkFlywayHistory(journal, artifactMigrations, common);
        } finally {
            try {
                connection.close();
            } catch (SQLException | RuntimeException ignored) {
                // 検査結果は確定している。close の失敗は結果を変えない
            }
        }
        checkProjectionState(checkedApplication, journalId, common);
        protection.addAll(common);
        enablement.addAll(common);
        return new Inspection(List.copyOf(protection), List.copyOf(enablement));
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

    /** 自身の権限の照会の結果。 */
    record Privileges(String currentRole, List<GrantRow> grants) {
    }

    /** 自身の権限を照会する（照会できなければ {@code privilege.unverifiable} を足して null）。 */
    private static Privileges readPrivileges(JdbcTemplate journal, Set<String> violations) {
        try {
            String currentUser = journal.queryForObject("SELECT CURRENT_USER()", String.class);
            String currentRole = journal.queryForObject("SELECT CURRENT_ROLE()", String.class);
            String grantee = grantee(currentUser);
            if (grantee == null) {
                violations.add("privilege.unverifiable");
                return null;
            }
            List<GrantRow> grants = new ArrayList<>();
            grants.addAll(journal.query("SELECT PRIVILEGE_TYPE, IS_GRANTABLE FROM information_schema.USER_PRIVILEGES "
                            + "WHERE GRANTEE = ?",
                    (rs, row) -> new GrantRow(Scope.GLOBAL, null, null, rs.getString(1), yes(rs.getString(2))), grantee));
            if (grants.isEmpty()) {
                // 自身の global 行（権限が無ければ USAGE）が見えない＝判定できない
                violations.add("privilege.unverifiable");
                return null;
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
            return new Privileges(currentRole, List.copyOf(grants));
        } catch (RuntimeException e) {
            violations.add("privilege.unverifiable");
            return null;
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

    static void checkFlywayHistory(JdbcTemplate journal,
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
            // 照会の失敗（接続の切断・timeout）は評価できない類（一度の瞬断で保持・保全を閉じない。review rb-fe-c P2-2）。版を解釈できない
            // 行（下の historyUnreadable）は実際の不一致で不成立のまま
            violations.add("flyway.history.unverifiable");
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
