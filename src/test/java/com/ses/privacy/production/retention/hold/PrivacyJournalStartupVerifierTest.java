package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.PrivacyFeatureProperties;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.Availability;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.GrantRow;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.HistoryRow;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.Result;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.Scope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Journal の設定契約（journal-witness-grant-design.md §2.1）と起動時検査（§2.3）の判定部分を Docker 無しで固定する。
 * 実 MySQL への照会は {@code PrivacyJournalStartupVerifierMySqlTest}（shard-2）が確かめる。
 */
class PrivacyJournalStartupVerifierTest {

    private static final String SCHEMA = "privacy_journal";
    private static final String VALID_URL = "jdbc:mysql://journal.internal:3306/privacy_journal?sslMode=VERIFY_IDENTITY";
    private static final String USER = "nf07_journal_appender";
    private static final String SECRET = "unit-test-only-credential";

    // ---------------------------------------------------------------- 設定契約

    @Test
    void 適合する設定は違反が無くpoolを1つだけ作る() {
        AtomicInteger created = new AtomicInteger();
        HikariDataSource pool = mock(HikariDataSource.class);
        PrivacyJournalConnections connections = new PrivacyJournalConnections(valid(), config -> {
            created.incrementAndGet();
            return pool;
        });

        assertThat(connections.isConfigured()).isTrue();
        assertThat(connections.contractViolations()).isEmpty();
        assertThat(connections.isPoolCreated()).isTrue();
        assertThat(created).hasValue(1);
        connections.close();
        verify(pool).close();
    }

    @Test
    void 設定が無ければpoolを作らず接続を試みない() {
        AtomicInteger created = new AtomicInteger();
        PrivacyJournalConnections connections = new PrivacyJournalConnections(new PrivacyJournalProperties(), config -> {
            created.incrementAndGet();
            return mock(HikariDataSource.class);
        });

        assertThat(connections.isConfigured()).isFalse();
        assertThat(connections.isPoolCreated()).isFalse();
        assertThat(connections.contractViolations()).isEmpty();
        assertThat(created).hasValue(0);
        assertThatThrownBy(() -> connections.openJournalPort(null, null))
                .isInstanceOf(com.ses.privacy.foundation.JournalUnavailableException.class);
    }

    @Test
    void journal_idだけの部分的な設定も設定ありとして契約違反にしpoolを作らない() {
        PrivacyJournalProperties properties = new PrivacyJournalProperties();
        properties.setJournalId("journal-prod-01");
        AtomicInteger created = new AtomicInteger();
        PrivacyJournalConnections connections = new PrivacyJournalConnections(properties, config -> {
            created.incrementAndGet();
            return mock(HikariDataSource.class);
        });

        assertThat(connections.isConfigured()).isTrue();
        assertThat(connections.contractViolations()).contains("datasource.url.missing",
                "datasource.username.missing", "datasource.password.missing");
        assertThat(connections.isPoolCreated()).isFalse();
        assertThat(created).hasValue(0);
    }

    @Test
    void URLの契約違反を固定codeで返す() {
        assertUrlViolation("jdbc:h2:mem:journal", "datasource.url.scheme");
        assertUrlViolation("jdbc:mysql:loadbalance://a:3306,b:3306/privacy_journal?sslMode=VERIFY_IDENTITY",
                "datasource.url.scheme");
        assertUrlViolation("jdbc:mysql://a:3306,b:3306/privacy_journal?sslMode=VERIFY_IDENTITY", "datasource.url.host");
        assertUrlViolation("jdbc:mysql://user:pw@a:3306/privacy_journal?sslMode=VERIFY_IDENTITY", "datasource.url.host");
        assertUrlViolation(VALID_URL + "&autoReconnect=true", "datasource.url.autoReconnect");
        assertUrlViolation(VALID_URL + "&autoReconnectForPools=yes", "datasource.url.autoReconnect");
        assertUrlViolation("jdbc:mysql://a:3306/privacy_journal", "datasource.url.sslModeNotVerifyIdentity");
        assertUrlViolation("jdbc:mysql://a:3306/privacy_journal?sslMode=REQUIRED", "datasource.url.sslModeNotVerifyIdentity");
        assertUrlViolation(VALID_URL + "&useSSL=false", "datasource.url.sslDisabled");
        assertUrlViolation(VALID_URL + "&serverTimezone=Asia/Tokyo", "datasource.url.timeZoneOverride");
        assertUrlViolation(VALID_URL + "&connectionTimeZone=SERVER", "datasource.url.timeZoneOverride");
        assertThat(violations(p -> p.getDatasource().setUrl("jdbc:MYSQL://a/privacy_journal?SSLMODE=verify_identity")))
                .as("scheme と parameter 名の大文字小文字は区別しない").isEmpty();
    }

    @Test
    void pool_timeout_schema_journal_id_pendingの範囲を検査する() {
        assertThat(violations(p -> p.getDatasource().setMaxPoolSize(0))).contains("datasource.maxPoolSize.outOfRange");
        assertThat(violations(p -> p.getDatasource().setMaxPoolSize(9))).contains("datasource.maxPoolSize.outOfRange");
        assertThat(violations(p -> p.getDatasource().setMaxPoolSize(8))).isEmpty();
        assertThat(violations(p -> p.getDatasource().setConnectionTimeout(Duration.ofMillis(100))))
                .contains("datasource.connectionTimeout.outOfRange");
        assertThat(violations(p -> p.getDatasource().setConnectionTimeout(Duration.ofSeconds(31))))
                .contains("datasource.connectionTimeout.outOfRange");
        assertThat(violations(p -> p.getDatasource().setConnectionTimeout(Duration.ofSeconds(30)))).isEmpty();
        assertThat(violations(p -> p.setExpectedSchema("privacy-journal"))).contains("expectedSchema.invalid");
        assertThat(violations(p -> p.setJournalId("abc"))).contains("journalId.invalid");
        assertThat(violations(p -> p.setJournalId("journal/01"))).contains("journalId.invalid");
        assertThat(violations(p -> p.setPendingTimeout(Duration.ofSeconds(10))))
                .contains("pendingTimeout.notLongerThanAppendTimeout");
        assertThat(violations(p -> p.setPendingTimeout(Duration.ofSeconds(11)))).isEmpty();
    }

    @Test
    void 契約違反のcodeとtoStringは接続文字列やprincipalやpasswordを含まない() {
        List<String> codes = violations(p -> p.getDatasource().setUrl(
                "jdbc:mysql://secret-host.internal:3306/privacy_journal?autoReconnect=true"));
        PrivacyJournalProperties properties = valid();

        assertThat(String.join(",", codes)).doesNotContain("secret-host", USER, SECRET);
        assertThat(properties.toString()).doesNotContain("journal.internal", USER, SECRET, "journal-prod-01");
    }

    @Test
    void pool設定は起動時に接続せずsession時区をUTCに固定する() {
        HikariConfig config = PrivacyJournalConnections.poolConfig(valid());

        assertThat(config.getPoolName()).isEqualTo("privacy-journal");
        assertThat(config.getMinimumIdle()).isZero();
        assertThat(config.getInitializationFailTimeout()).isNegative();
        assertThat(config.getMaximumPoolSize()).isEqualTo(PrivacyJournalProperties.DEFAULT_MAX_POOL_SIZE);
        assertThat(config.getConnectionTimeout()).isEqualTo(PrivacyJournalProperties.DEFAULT_CONNECTION_TIMEOUT.toMillis());
        assertThat(config.getConnectionInitSql()).isEqualTo("SET SESSION time_zone = '+00:00'");
        assertThat(config.getDataSourceProperties()).containsEntry("connectionTimeZone", "UTC")
                .containsEntry("preserveInstants", "true").containsEntry("autoReconnect", "false");
        assertThat(config.isRegisterMbeans()).isFalse();
    }

    // ---------------------------------------------------------------- 権限

    @Test
    void appenderの許可集合ちょうどならflag_ONでもOFFでも適合する() {
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("NONE", appender(), SCHEMA, true)).isEmpty();
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("NONE", appender(), SCHEMA, false)).isEmpty();
    }

    @Test
    void readerはflag_OFFでだけ適合しflag_ONでは権限不足と超過になる() {
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("NONE", reader(), SCHEMA, false)).isEmpty();
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("NONE", reader(), SCHEMA, true))
                .contains("privilege.excess:schema.SELECT", "privilege.missing:pj_head.INSERT",
                        "privilege.missing:pj_head.UPDATE", "privilege.missing:pj_lease.INSERT");
    }

    @Test
    void 許可集合を超える権限と欠けた権限を表と操作の単位で検出する() {
        List<GrantRow> excess = new ArrayList<>(appender());
        excess.add(table("pj_event", "DELETE"));
        excess.add(table("pj_event", "UPDATE"));
        excess.add(new GrantRow(Scope.SCHEMA, SCHEMA, null, "CREATE", false));
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("NONE", excess, SCHEMA, true))
                .containsExactly("privilege.excess:pj_event.DELETE", "privilege.excess:pj_event.UPDATE",
                        "privilege.excess:schema.CREATE");

        List<GrantRow> missing = new ArrayList<>(appender());
        missing.removeIf(row -> "pj_lease".equals(row.table()) && "INSERT".equals(row.privilege()));
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("NONE", missing, SCHEMA, true))
                .containsExactly("privilege.missing:pj_lease.INSERT");
    }

    @Test
    void role_global権限_他schema_列単位_GRANT_OPTIONは全て不適合() {
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("`journal_role`@`%`", appender(), SCHEMA, true))
                .contains("privilege.roleActive");
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations(null, appender(), SCHEMA, true))
                .contains("privilege.roleActive");

        List<GrantRow> global = new ArrayList<>(appender());
        global.add(new GrantRow(Scope.GLOBAL, null, null, "PROCESS", false));
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("NONE", global, SCHEMA, true))
                .containsExactly("privilege.global");

        List<GrantRow> otherSchema = new ArrayList<>(appender());
        otherSchema.add(new GrantRow(Scope.SCHEMA, "ses_manager_db", null, "INSERT", false));
        otherSchema.add(new GrantRow(Scope.TABLE, "ses_manager_db", "t_legal_hold", "UPDATE", false));
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("NONE", otherSchema, SCHEMA, true))
                .containsExactly("privilege.otherSchema");

        List<GrantRow> column = new ArrayList<>(appender());
        column.add(new GrantRow(Scope.COLUMN, SCHEMA, "pj_event", "UPDATE", false));
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("NONE", column, SCHEMA, true))
                .containsExactly("privilege.column");

        List<GrantRow> grantOption = new ArrayList<>(appender());
        grantOption.set(1, new GrantRow(Scope.TABLE, SCHEMA, "pj_head", "SELECT", true));
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("NONE", grantOption, SCHEMA, true))
                .containsExactly("privilege.grantOption");
    }

    @Test
    void schema名のwildcard_escapeは同じschemaとして扱う() {
        List<GrantRow> escapedReader = List.of(new GrantRow(Scope.GLOBAL, null, null, "USAGE", false),
                new GrantRow(Scope.SCHEMA, "privacy\\_journal", null, "SELECT", false));
        assertThat(PrivacyJournalStartupVerifier.privilegeViolations("NONE", escapedReader, SCHEMA, false)).isEmpty();
    }

    @Test
    void CURRENT_USERをinformation_schemaのgrantee表記にする() {
        assertThat(PrivacyJournalStartupVerifier.grantee("nf07_journal_appender@%")).isEqualTo("'nf07_journal_appender'@'%'");
        assertThat(PrivacyJournalStartupVerifier.grantee("a@b@10.0.0.%")).isEqualTo("'a@b'@'10.0.0.%'");
        assertThat(PrivacyJournalStartupVerifier.grantee("no-host")).isNull();
        assertThat(PrivacyJournalStartupVerifier.grantee("quote'd@%")).isNull();
        assertThat(PrivacyJournalStartupVerifier.grantee(null)).isNull();
    }

    // ---------------------------------------------------------------- instance・Flyway 履歴

    @Test
    void 耐久設定と時区の不適合を項目ごとに返す() {
        assertThat(PrivacyJournalStartupVerifier.instanceViolations(durable())).isEmpty();
        assertThat(PrivacyJournalStartupVerifier.instanceViolations(with("flush_commit", 2)))
                .containsExactly("durability.innodbFlushLogAtTrxCommit");
        assertThat(PrivacyJournalStartupVerifier.instanceViolations(with("sync_binlog", 0)))
                .containsExactly("durability.syncBinlog");
        assertThat(PrivacyJournalStartupVerifier.instanceViolations(with("log_bin", "OFF")))
                .containsExactly("durability.logBin");
        assertThat(PrivacyJournalStartupVerifier.instanceViolations(with("read_only", 1)))
                .containsExactly("instance.readOnly");
        assertThat(PrivacyJournalStartupVerifier.instanceViolations(with("super_read_only", true)))
                .containsExactly("instance.superReadOnly");
        assertThat(PrivacyJournalStartupVerifier.instanceViolations(with("storage_engine", "MyISAM")))
                .containsExactly("instance.defaultStorageEngine");
        assertThat(PrivacyJournalStartupVerifier.instanceViolations(with("session_time_zone", "SYSTEM")))
                .containsExactly("session.timeZone");
        assertThat(PrivacyJournalStartupVerifier.instanceViolations(with("sync_binlog", null)))
                .as("値が読めなければ不適合").containsExactly("durability.syncBinlog");
    }

    @Test
    void Flyway履歴がartifactと一致しなければ不適合() {
        List<PrivacyJournalMigrations.Migration> artifact = List.of(migration("1", "V1__privacy_journal_core.sql"));

        assertThat(PrivacyJournalStartupVerifier.historyViolations(artifact,
                List.of(new HistoryRow("1", "V1__privacy_journal_core.sql", true)))).isEmpty();
        assertThat(PrivacyJournalStartupVerifier.historyViolations(artifact, List.of()))
                .containsExactly("flyway.pending:1");
        assertThat(PrivacyJournalStartupVerifier.historyViolations(artifact, List.of(
                new HistoryRow("1", "V1__privacy_journal_core.sql", true),
                new HistoryRow("2", "V2__later.sql", false)))).containsExactly("flyway.failedMigration");
        assertThat(PrivacyJournalStartupVerifier.historyViolations(artifact, List.of(
                new HistoryRow("1", "V1__privacy_journal_core.sql", true),
                new HistoryRow("2", "V2__later.sql", true)))).containsExactly("flyway.unknownVersion:2");
        assertThat(PrivacyJournalStartupVerifier.historyViolations(artifact, List.of(
                new HistoryRow("1", "V1__other.sql", true)))).containsExactly("flyway.scriptMismatch:1");
        assertThat(PrivacyJournalStartupVerifier.historyViolations(List.of(), List.of()))
                .containsExactly("flyway.artifactEmpty");
    }

    @Test
    void artifactのJournal用migrationをclasspathから版の昇順で読む() {
        assertThat(PrivacyJournalMigrations.fromClasspath())
                .extracting(PrivacyJournalMigrations.Migration::script)
                .startsWith("V1__privacy_journal_core.sql");
    }

    // ---------------------------------------------------------------- 起動時の扱い

    @Test
    void 設定が無ければ接続もapplication_DBの照会もせずNOT_CONFIGURED() {
        AtomicInteger applicationLookups = new AtomicInteger();
        PrivacyJournalStartupVerifier verifier = new PrivacyJournalStartupVerifier(
                PrivacyJournalConnections.create(new PrivacyJournalProperties()), features(false), () -> {
                    applicationLookups.incrementAndGet();
                    return null;
                }, true);

        verifier.afterSingletonsInstantiated();

        assertThat(verifier.startupResult()).isEqualTo(new Result(Availability.NOT_CONFIGURED, List.of()));
        assertThat(applicationLookups).hasValue(0);
    }

    @Test
    void 契約違反は接続せずUNAVAILABLEでflag_ONなら起動を止める() {
        PrivacyJournalProperties properties = valid();
        properties.getDatasource().setUrl(VALID_URL + "&autoReconnect=true");
        AtomicInteger applicationLookups = new AtomicInteger();

        PrivacyJournalStartupVerifier off = new PrivacyJournalStartupVerifier(
                PrivacyJournalConnections.create(properties), features(false), () -> {
                    applicationLookups.incrementAndGet();
                    return null;
                }, true);
        off.afterSingletonsInstantiated();
        assertThat(off.startupResult().availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(off.startupResult().violations()).containsExactly("contract.datasource.url.autoReconnect");
        assertThat(applicationLookups).hasValue(0);

        PrivacyJournalStartupVerifier nonProduction = new PrivacyJournalStartupVerifier(
                PrivacyJournalConnections.create(properties), features(true), () -> null, false);
        assertThatCode(nonProduction::afterSingletonsInstantiated).as("prod 以外では flag ON でも起動を止めない")
                .doesNotThrowAnyException();
        assertThat(nonProduction.startupResult().availability()).isEqualTo(Availability.UNAVAILABLE);

        PrivacyJournalStartupVerifier on = new PrivacyJournalStartupVerifier(
                PrivacyJournalConnections.create(properties), features(true), () -> null, true);
        assertThatThrownBy(on::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contract.datasource.url.autoReconnect")
                .hasMessageNotContaining("journal.internal")
                .hasMessageNotContaining(USER)
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void 接続できなければjournal_unreachableだけを返しapplication_DBを照会しない() {
        AtomicInteger attempts = new AtomicInteger();
        DataSource unreachable = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        attempts.incrementAndGet();
                        throw new SQLException("Communications link failure to journal.internal:3306 as " + USER);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        JdbcTemplate application = mock(JdbcTemplate.class);

        List<String> violations = PrivacyJournalStartupVerifier.inspect(unreachable, application, SCHEMA,
                "journal-prod-01", true, () -> List.of(migration("1", "V1__privacy_journal_core.sql")));

        assertThat(violations).containsExactly("journal.unreachable");
        assertThat(attempts).hasValue(1);
        org.mockito.Mockito.verifyNoInteractions(application);
    }

    @Test
    void prodのflag_ONは設定欠落とUNAVAILABLEで起動を止めflag_OFFとprod以外は止めない() {
        assertThatThrownBy(() -> PrivacyJournalStartupVerifier.enforce(
                new Result(Availability.NOT_CONFIGURED, List.of()), true, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("journal.notConfigured");
        assertThatThrownBy(() -> PrivacyJournalStartupVerifier.enforce(
                new Result(Availability.UNAVAILABLE, List.of("durability.syncBinlog")), true, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("durability.syncBinlog");
        assertThatCode(() -> PrivacyJournalStartupVerifier.enforce(
                new Result(Availability.AVAILABLE, List.of()), true, true)).doesNotThrowAnyException();
        assertThatCode(() -> PrivacyJournalStartupVerifier.enforce(
                new Result(Availability.UNAVAILABLE, List.of("journal.unreachable")), false, true))
                .doesNotThrowAnyException();
        assertThatCode(() -> PrivacyJournalStartupVerifier.enforce(
                new Result(Availability.NOT_CONFIGURED, List.of()), false, true)).doesNotThrowAnyException();
        assertThatCode(() -> PrivacyJournalStartupVerifier.enforce(
                new Result(Availability.NOT_CONFIGURED, List.of()), true, false))
                .as("test profile は flag ON（application-test.yml）でも journal 設定を持たない").doesNotThrowAnyException();
        assertThatCode(() -> PrivacyJournalStartupVerifier.enforce(
                new Result(Availability.UNAVAILABLE, List.of("journal.unreachable")), true, false))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- helper

    private static void assertUrlViolation(String url, String code) {
        assertThat(violations(p -> p.getDatasource().setUrl(url))).as(url).contains(code);
    }

    private static List<String> violations(Consumer<PrivacyJournalProperties> change) {
        PrivacyJournalProperties properties = valid();
        change.accept(properties);
        return PrivacyJournalConnections.contractViolations(properties);
    }

    private static PrivacyJournalProperties valid() {
        PrivacyJournalProperties properties = new PrivacyJournalProperties();
        properties.getDatasource().setUrl(VALID_URL);
        properties.getDatasource().setUsername(USER);
        properties.getDatasource().setPassword(SECRET);
        properties.setJournalId("journal-prod-01");
        return properties;
    }

    private static PrivacyFeatureProperties features(boolean retention) {
        PrivacyFeatureProperties properties = new PrivacyFeatureProperties();
        properties.getRetention().setEnabled(retention);
        return properties;
    }

    private static List<GrantRow> appender() {
        List<GrantRow> rows = new ArrayList<>();
        rows.add(new GrantRow(Scope.GLOBAL, null, null, "USAGE", false));
        rows.add(table("pj_head", "SELECT"));
        rows.add(table("pj_head", "INSERT"));
        rows.add(table("pj_head", "UPDATE"));
        for (String journalTable : List.of("pj_event", "pj_command", "pj_action", "pj_lease")) {
            rows.add(table(journalTable, "SELECT"));
            rows.add(table(journalTable, "INSERT"));
        }
        rows.add(table("flyway_schema_history_privacy_journal", "SELECT"));
        return rows;
    }

    private static List<GrantRow> reader() {
        return List.of(new GrantRow(Scope.GLOBAL, null, null, "USAGE", false),
                new GrantRow(Scope.SCHEMA, SCHEMA, null, "SELECT", false));
    }

    private static GrantRow table(String table, String privilege) {
        return new GrantRow(Scope.TABLE, SCHEMA, table, privilege, false);
    }

    private static Map<String, Object> durable() {
        Map<String, Object> config = new HashMap<>();
        config.put("flush_commit", 1L);
        config.put("sync_binlog", 1L);
        config.put("log_bin", 1L);
        config.put("read_only", 0L);
        config.put("super_read_only", 0L);
        config.put("storage_engine", "InnoDB");
        config.put("session_time_zone", "+00:00");
        return config;
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> config = durable();
        config.put(key, value);
        return config;
    }

    private static PrivacyJournalMigrations.Migration migration(String version, String script) {
        return new PrivacyJournalMigrations.Migration(MigrationVersion.fromVersion(version), script);
    }
}
