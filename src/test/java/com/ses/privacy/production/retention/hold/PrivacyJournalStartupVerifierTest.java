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

    /**
     * RB-NF07-HOLD（RB-NF05 review からの転送）: 複数 host へ切り替える URL の各形を NF06（{@code ImportJdbcReconnectGuard}）と
     * 同じ範囲で拒否する。scheme の形は scheme の違反、{@code jdbc:mysql://} の host 部の形は host の違反になる。host 部は
     * {@code ;} で終わらないため、NF06 で取りこぼした {@code h1;x,h2} も {@code ,} で拒否する。
     */
    @Test
    void 複数hostへ切り替えるURLはfailover_loadbalance_replication_SRVの全ての形を拒否する() {
        String tail = "/privacy_journal?sslMode=VERIFY_IDENTITY";
        for (String scheme : List.of("jdbc:mysql:loadbalance://a:3306,b:3306", "jdbc:mysql:replication://a:3306,b:3306",
                "jdbc:mysql:loadbalance://a:3306", "jdbc:mysql:replication://a:3306", "jdbc:mysql+srv://_mysql._tcp.journal",
                "jdbc:mysql:loadbalance+srv://_mysql._tcp.journal", "jdbc:mysql:replication+srv://_mysql._tcp.journal",
                "jdbc:mysqlx://a:33060", "JDBC:MYSQL:LOADBALANCE://a:3306", "jdbc:mariadb://a:3306",
                "jdbc:mariadb:failover://a:3306,b:3306", "jdbc:mariadb:sequential://a:3306,b:3306",
                "jdbc:mariadb:loadbalance://a:3306,b:3306", "jdbc:mariadb:replication://a:3306,b:3306",
                "jdbc:mariadb:aurora://a:3306")) {
            assertUrlViolation(scheme + tail, "datasource.url.scheme");
        }
        for (String hosts : List.of("a:3306,b:3306", "a,b", "a:3306, b:3306", "a:3306,", ",a:3306", "[a:3306,b:3306]",
                "address=(host=a)(port=3306),address=(host=b)(port=3306)", "address=(host=a)(port=3306)",
                "(host=a,port=3306),(host=b,port=3306)", "(host=a,port=3306)", "a;x,b", "a:3306;b:3306,c:3306",
                "a;failOverReadOnly=false,b")) {
            assertUrlViolation("jdbc:mysql://" + hosts + tail, "datasource.url.host");
        }
        assertThat(violations(p -> p.getDatasource().setUrl(VALID_URL))).as("単一 host の host:port は通る").isEmpty();
    }

    /**
     * RB-NF07-HOLD: driver（Connector/J）は {@code #} 以降を fragment として捨てる。本検査は {@code #} を区切りにしないため、
     * {@code #} の後ろに置いた {@code sslMode} を検査だけが読み、driver は既定の TLS 設定で接続し得る。{@code #} は位置に関係なく拒否する。
     */
    @Test
    void URLのfragmentは位置に関係なく拒否しfragmentの後ろのsslModeで検査を通さない() {
        assertUrlViolation("jdbc:mysql://journal.internal:3306/privacy_journal#?sslMode=VERIFY_IDENTITY",
                "datasource.url.fragment");
        assertUrlViolation(VALID_URL + "#x", "datasource.url.fragment");
        assertUrlViolation("jdbc:mysql://journal.internal#x:3306/privacy_journal?sslMode=VERIFY_IDENTITY",
                "datasource.url.fragment");
        assertThat(violations(p -> p.getDatasource().setUrl("jdbc:mysql://a#,b/privacy_journal?sslMode=VERIFY_IDENTITY")))
                .as("fragment の前後に分けた複数 host も拒否する").contains("datasource.url.host", "datasource.url.fragment");
    }

    @Test
    void URLの符号化_認証情報_重複_timeout上書きで検査を迂回させない() {
        // driver は key・値を percent-decode するため、符号化した sslMode・autoReconnect は検査を迂回し得る（Review P3-4）
        assertUrlViolation("jdbc:mysql://a:3306/privacy_journal?sslMode=VERIFY_IDENTITY&sslM%6Fde=DISABLED",
                "datasource.url.percentEncoded");
        assertUrlViolation(VALID_URL + "&autoReconnect=tr%75e", "datasource.url.percentEncoded");
        assertUrlViolation(VALID_URL + "&user=nf07_journal_appender", "datasource.url.credentials");
        assertUrlViolation(VALID_URL + "&password=" + SECRET, "datasource.url.credentials");
        assertUrlViolation(VALID_URL + "&password1=" + SECRET, "datasource.url.credentials");
        assertUrlViolation("jdbc:mysql://a:3306/privacy_journal?sslMode=DISABLED&sslMode=VERIFY_IDENTITY",
                "datasource.url.duplicateParameter");
        assertUrlViolation(VALID_URL + "&SSLMODE=VERIFY_IDENTITY", "datasource.url.duplicateParameter");
        assertUrlViolation(VALID_URL + "&socketTimeout=0", "datasource.url.timeoutOverride");
        assertUrlViolation(VALID_URL + "&connectTimeout=600000", "datasource.url.timeoutOverride");
        assertThat(String.join(",", violations(p -> p.getDatasource().setUrl(VALID_URL + "&password=" + SECRET))))
                .as("違反 code に URL の secret を含めない").doesNotContain(SECRET);
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
    }

    @Test
    void pending_timeoutはappendの在途時間の上限より長くなければ違反() {
        // 上限 = connection-timeout（既定5秒）+ append の transaction 上限10秒 + socket timeout 20秒 = 35秒（Review P2-1）
        assertThat(PrivacyJournalConnections.appendInFlightUpperBound(PrivacyJournalProperties.DEFAULT_CONNECTION_TIMEOUT))
                .isEqualTo(Duration.ofSeconds(35));
        assertThat(PrivacyJournalConnections.create(valid()).appendInFlightUpperBound()).isEqualTo(Duration.ofSeconds(35));
        assertThat(violations(p -> p.setPendingTimeout(Duration.ofSeconds(11))))
                .as("append の transaction 上限だけを超える値では足りない")
                .contains("pendingTimeout.notLongerThanAppendInFlight");
        assertThat(violations(p -> p.setPendingTimeout(Duration.ofSeconds(35))))
                .contains("pendingTimeout.notLongerThanAppendInFlight");
        assertThat(violations(p -> p.setPendingTimeout(Duration.ofSeconds(36)))).isEmpty();
        assertThat(violations(p -> {
            p.getDatasource().setConnectionTimeout(Duration.ofSeconds(30));
            p.setPendingTimeout(Duration.ofSeconds(60));
        })).as("接続待ちを長くすれば上限も延びる（30+10+20=60秒）").contains("pendingTimeout.notLongerThanAppendInFlight");
        assertThat(violations(p -> {
            p.getDatasource().setConnectionTimeout(Duration.ofSeconds(1));
            p.setPendingTimeout(Duration.ofSeconds(32));
        })).isEmpty();
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
    void pool設定は起動時に接続せずsession時区をUTCに固定しsocket_timeoutを持つ() {
        HikariConfig config = PrivacyJournalConnections.poolConfig(valid());

        assertThat(config.getPoolName()).isEqualTo("privacy-journal");
        assertThat(config.getMinimumIdle()).isZero();
        assertThat(config.getInitializationFailTimeout()).isNegative();
        assertThat(config.getMaximumPoolSize()).isEqualTo(PrivacyJournalProperties.DEFAULT_MAX_POOL_SIZE);
        assertThat(config.getConnectionTimeout()).isEqualTo(PrivacyJournalProperties.DEFAULT_CONNECTION_TIMEOUT.toMillis());
        assertThat(config.getConnectionInitSql()).isEqualTo("SET SESSION time_zone = '+00:00'");
        assertThat(config.isRegisterMbeans()).isFalse();
        assertThat(config.getJdbcUrl()).as("URL・principal・password は Hikari の設定に入れない").isNull();
        assertThat(config.getUsername()).isNull();
        assertThat(config.getPassword()).isNull();
        assertThat(config.getDataSource()).isInstanceOf(SanitizingJournalDriverDataSource.class);
        SanitizingJournalDriverDataSource driver = (SanitizingJournalDriverDataSource) config.getDataSource();
        assertThat(driver.hasPassword()).isTrue();
        assertThat(driver.connectionPropertiesWithoutSecret())
                .containsEntry("user", USER)
                .containsEntry("connectionTimeZone", "UTC")
                .containsEntry("preserveInstants", "true")
                .containsEntry("autoReconnect", "false")
                .containsEntry("connectTimeout", "5000")
                .containsEntry("socketTimeout", "20000")
                .doesNotContainKey("password");
        assertThat(PrivacyJournalConnections.SOCKET_TIMEOUT)
                .as("socket timeout は append の transaction 上限より長い（正常な statement を切らない）")
                .isGreaterThan(com.ses.privacy.foundation.ProductionPrivacyComponents.journalAppendTimeout());
        assertThat(driver.toString()).doesNotContain("journal.internal", USER, SECRET);
    }

    @Test
    void driverの接続失敗はuser名_host_driverのmessageを固定文言に置き換える() {
        java.sql.Driver denying = deniedDriver();
        SanitizingJournalDriverDataSource dataSource = new SanitizingJournalDriverDataSource(VALID_URL,
                new java.util.Properties(), () -> denying);

        SQLException failure = org.assertj.core.api.Assertions.catchThrowableOfType(dataSource::getConnection,
                SQLException.class);

        assertThat(failure.getMessage()).startsWith(SanitizingJournalDriverDataSource.FAILURE_MESSAGE)
                .doesNotContain(USER, "10.0.0.5", "journal.internal", "Access denied");
        assertThat(failure.getSQLState()).isEqualTo("28000");
        assertThat(failure.getErrorCode()).isEqualTo(1045);
        assertThat(failure.getCause()).as("元の例外は cause にも付けない").isNull();
    }

    @Test
    void Hikari経由の接続失敗でも例外の連鎖にuser名とhostが出ない() throws Exception {
        PrivacyJournalProperties properties = valid();
        properties.getDatasource().setConnectionTimeout(Duration.ofMillis(250));
        HikariConfig config = PrivacyJournalConnections.poolConfig(properties);
        java.util.Properties driverProperties = new java.util.Properties();
        driverProperties.putAll(((SanitizingJournalDriverDataSource) config.getDataSource())
                .connectionPropertiesWithoutSecret());
        java.sql.Driver denying = deniedDriver();
        config.setDataSource(new SanitizingJournalDriverDataSource(VALID_URL, driverProperties, () -> denying));

        try (HikariDataSource pool = new HikariDataSource(config)) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(pool::getConnection);

            assertThat(failure).isInstanceOf(SQLException.class);
            for (Throwable current = failure; current != null; current = current.getCause()) {
                assertThat(String.valueOf(current.getMessage()))
                        .doesNotContain(USER, "10.0.0.5", "journal.internal", SECRET, "Access denied");
            }
        }
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
                List.of(applied("1", "V1__privacy_journal_core.sql", true)))).isEmpty();
        assertThat(PrivacyJournalStartupVerifier.historyViolations(artifact, List.of()))
                .containsExactly("flyway.pending:1");
        assertThat(PrivacyJournalStartupVerifier.historyViolations(artifact, List.of(
                applied("1", "V1__privacy_journal_core.sql", true),
                applied("2", "V2__later.sql", false)))).containsExactly("flyway.failedMigration");
        assertThat(PrivacyJournalStartupVerifier.historyViolations(artifact, List.of(
                applied("1", "V1__privacy_journal_core.sql", true),
                applied("2", "V2__later.sql", true)))).containsExactly("flyway.unknownVersion:2");
        assertThat(PrivacyJournalStartupVerifier.historyViolations(artifact, List.of(
                applied("1", "V1__other.sql", true)))).containsExactly("flyway.scriptMismatch:1");
        assertThat(PrivacyJournalStartupVerifier.historyViolations(List.of(), List.of()))
                .containsExactly("flyway.artifactEmpty");
    }

    @Test
    void 適用後にscriptが変わればchecksumの不一致で不適合() {
        List<PrivacyJournalMigrations.Migration> artifact = List.of(migration("1", "V1__privacy_journal_core.sql"));

        assertThat(PrivacyJournalStartupVerifier.historyViolations(artifact, List.of(new HistoryRow(
                "1", "V1__privacy_journal_core.sql", CHECKSUM + 1, true)))).containsExactly("flyway.checksumMismatch:1");
        assertThat(PrivacyJournalStartupVerifier.historyViolations(artifact, List.of(new HistoryRow(
                "1", "V1__privacy_journal_core.sql", null, true)))).as("checksum が無い履歴も一致とみなさない")
                .containsExactly("flyway.checksumMismatch:1");
    }

    @Test
    void checksumはFlywayのChecksumCalculatorと同じ値になる() throws Exception {
        String v1;
        try (var in = PrivacyJournalStartupVerifierTest.class.getClassLoader()
                .getResourceAsStream("db/privacy-journal/V1__privacy_journal_core.sql")) {
            v1 = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String content : List.of(v1, v1.replace("\n", "\r\n"), "﻿" + v1, "SELECT 1;", "a\nb", "",
                "日本語のcomment\n-- 末尾改行なし")) {
            assertThat(PrivacyJournalMigrations.checksum(content))
                    .isEqualTo(org.flywaydb.core.internal.resolver.ChecksumCalculator.calculate(
                            new org.flywaydb.core.internal.resource.StringResource(content)));
        }
        assertThat(PrivacyJournalMigrations.fromClasspath())
                .extracting(PrivacyJournalMigrations.Migration::checksum)
                .startsWith(PrivacyJournalMigrations.checksum(v1));
    }

    @Test
    void artifactのJournal用migrationをclasspathから版の昇順で読む() {
        assertThat(PrivacyJournalMigrations.fromClasspath())
                .extracting(PrivacyJournalMigrations.Migration::script)
                .startsWith("V1__privacy_journal_core.sql");
    }

    @Test
    void 起動時検査の照会にはquery_timeoutを置き共有のapplication_JdbcTemplateは変えない() {
        assertThat(PrivacyJournalStartupVerifier.journalTemplate(mock(java.sql.Connection.class)).getQueryTimeout())
                .isEqualTo(PrivacyJournalStartupVerifier.QUERY_TIMEOUT_SECONDS);
        DataSource applicationDataSource = mock(DataSource.class);
        JdbcTemplate shared = new JdbcTemplate(applicationDataSource);
        JdbcTemplate checked = PrivacyJournalStartupVerifier.applicationTemplate(shared);

        assertThat(checked).isNotSameAs(shared);
        assertThat(checked.getDataSource()).isSameAs(applicationDataSource);
        assertThat(checked.getQueryTimeout()).isEqualTo(PrivacyJournalStartupVerifier.QUERY_TIMEOUT_SECONDS);
        assertThat(shared.getQueryTimeout()).as("共有 Bean の設定は変えない").isEqualTo(-1);
        assertThat(PrivacyJournalStartupVerifier.applicationTemplate(null)).isNull();
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

    /** test 用の固定 checksum（履歴と artifact を一致させる側の値）。 */
    private static final int CHECKSUM = 12345;

    private static PrivacyJournalMigrations.Migration migration(String version, String script) {
        return new PrivacyJournalMigrations.Migration(MigrationVersion.fromVersion(version), script, CHECKSUM);
    }

    private static HistoryRow applied(String version, String script, boolean success) {
        return new HistoryRow(version, script, CHECKSUM, success);
    }

    /** Connector/J の認証失敗と同じ形（user 名・接続元 host を message に含む）の例外を投げる driver。 */
    private static java.sql.Driver deniedDriver() {
        return (java.sql.Driver) Proxy.newProxyInstance(PrivacyJournalStartupVerifierTest.class.getClassLoader(),
                new Class<?>[]{java.sql.Driver.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "connect" -> throw new SQLException("Access denied for user '" + USER
                            + "'@'10.0.0.5' to journal.internal (using password: YES)", "28000", 1045);
                    case "acceptsURL" -> true;
                    case "getMajorVersion", "getMinorVersion" -> 8;
                    case "jdbcCompliant" -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "denied-driver";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
