package com.ses.privacy.production.retention.hold;

import com.ses.migration.PrivacyJournalFlywayJobConfig;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.Availability;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.Result;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF07-A1J: Journal の起動時検査（{@link PrivacyJournalStartupVerifier}、journal-witness-grant-design.md §2.3）を実 MySQL で
 * 検証する。耐久設定の不適合・binlog 無効・read_only・application DB と同じ {@code server_uuid}・過剰権限・権限不足・
 * role 経由の権限・Flyway 履歴の不一致・{@code journal_id} の不一致を与え、それぞれ不適合（retention flag ON なら起動拒否）
 * になることを確かめる。
 *
 * <p>container は3つ: Journal（耐久設定あり）、application DB（{@code t_privacy_projection_state} だけを持つ）、
 * binlog 無効の Journal。Journal schema は専用 Flyway job の設定そのもので作る。global 変数・user・行の変更は
 * 各 test の finally で元に戻す（実行順に依存しない）。user は test が生成したものだけを使う。
 */
@Tag("mysql")
@Testcontainers
class PrivacyJournalStartupVerifierMySqlTest {
    private static final String ROOT_PASSWORD = UUID.randomUUID().toString();
    private static final String SCHEMA = PrivacyJournalTestPrincipals.SCHEMA;
    private static final String JOURNAL_ID = "journal-a1j-01";

    @Container
    static final MySQLContainer<?> JOURNAL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(SCHEMA)
            .withUsername("root")
            .withPassword(ROOT_PASSWORD)
            .withCommand("--log-bin=mysql-bin", "--sync-binlog=1", "--innodb-flush-log-at-trx-commit=1");

    @Container
    static final MySQLContainer<?> APPLICATION = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("root")
            .withPassword(ROOT_PASSWORD);

    @Container
    static final MySQLContainer<?> NO_BINLOG = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(SCHEMA)
            .withUsername("root")
            .withPassword(ROOT_PASSWORD)
            .withCommand("--skip-log-bin", "--innodb-flush-log-at-trx-commit=1");

    private static JdbcTemplate journalRoot;
    private static JdbcTemplate applicationRoot;
    private static JdbcTemplate noBinlogRoot;
    private static String appender;
    private static String appenderPassword;
    private static String reader;
    private static String readerPassword;

    @BeforeAll
    static void prepare() {
        PrivacyJournalFlywayJobConfig.configure(JOURNAL.getJdbcUrl(), "root", ROOT_PASSWORD).load().migrate();
        PrivacyJournalFlywayJobConfig.configure(NO_BINLOG.getJdbcUrl(), "root", ROOT_PASSWORD).load().migrate();
        journalRoot = root(JOURNAL);
        applicationRoot = root(APPLICATION);
        noBinlogRoot = root(NO_BINLOG);
        appender = PrivacyJournalTestPrincipals.uniqueName("nf07_app");
        appenderPassword = PrivacyJournalTestPrincipals.randomPassword();
        PrivacyJournalTestPrincipals.createAppender(journalRoot, appender, appenderPassword);
        reader = PrivacyJournalTestPrincipals.uniqueName("nf07_rdr");
        readerPassword = PrivacyJournalTestPrincipals.randomPassword();
        PrivacyJournalTestPrincipals.createReader(journalRoot, reader, readerPassword);
        // application DB 側は検査が読む列だけ（V190 の t_privacy_projection_state と同じ主キー）
        applicationRoot.execute("CREATE TABLE t_privacy_projection_state (tenant_id VARCHAR(100) NOT NULL, "
                + "journal_id VARCHAR(64) NOT NULL, PRIMARY KEY (tenant_id, journal_id)) ENGINE=InnoDB");
        applicationRoot.update("INSERT INTO t_privacy_projection_state (tenant_id, journal_id) VALUES ('default', ?)",
                JOURNAL_ID);
    }

    @Test
    void 適合するappenderはflag_ONでも適合し起動を止めない() {
        List<String> violations = inspect(JOURNAL, appender, appenderPassword, true);

        assertThat(violations).isEmpty();
        assertThatCode(() -> PrivacyJournalStartupVerifier.enforce(
                new Result(Availability.AVAILABLE, violations), true, true)).doesNotThrowAnyException();
    }

    @Test
    void readerはflag_OFFでだけ適合しflag_ONでは権限不足になる() {
        assertThat(inspect(JOURNAL, reader, readerPassword, false)).isEmpty();
        assertThat(inspect(JOURNAL, reader, readerPassword, true))
                .contains("privilege.missing:pj_head.INSERT", "privilege.missing:pj_event.INSERT");
    }

    @Test
    void innodb_flush_log_at_trx_commitが1でなければflag_ONの起動を拒否する() {
        journalRoot.execute("SET GLOBAL innodb_flush_log_at_trx_commit = 2");
        try {
            List<String> violations = inspect(JOURNAL, appender, appenderPassword, true);

            assertThat(violations).contains("durability.innodbFlushLogAtTrxCommit");
            assertThatThrownBy(() -> PrivacyJournalStartupVerifier.enforce(
                    new Result(Availability.UNAVAILABLE, violations), true, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("durability.innodbFlushLogAtTrxCommit")
                    .hasMessageNotContaining(appender)
                    .hasMessageNotContaining(appenderPassword);
            assertThatCode(() -> PrivacyJournalStartupVerifier.enforce(
                    new Result(Availability.UNAVAILABLE, violations), false, true))
                    .as("flag OFF では起動を止めない").doesNotThrowAnyException();
        } finally {
            journalRoot.execute("SET GLOBAL innodb_flush_log_at_trx_commit = 1");
        }
    }

    @Test
    void sync_binlogが1でなければ不適合() {
        journalRoot.execute("SET GLOBAL sync_binlog = 0");
        try {
            assertThat(inspect(JOURNAL, appender, appenderPassword, true)).contains("durability.syncBinlog");
        } finally {
            journalRoot.execute("SET GLOBAL sync_binlog = 1");
        }
    }

    @Test
    void binlogが無効なinstanceは不適合() {
        String user = PrivacyJournalTestPrincipals.uniqueName("nf07_nob");
        String password = PrivacyJournalTestPrincipals.randomPassword();
        PrivacyJournalTestPrincipals.createAppender(noBinlogRoot, user, password);
        try {
            assertThat(inspect(NO_BINLOG, user, password, true)).contains("durability.logBin");
        } finally {
            PrivacyJournalTestPrincipals.dropUser(noBinlogRoot, user);
        }
    }

    @Test
    void read_onlyのinstanceは不適合() {
        journalRoot.execute("SET GLOBAL read_only = ON");
        try {
            assertThat(inspect(JOURNAL, appender, appenderPassword, true)).contains("instance.readOnly");
        } finally {
            journalRoot.execute("SET GLOBAL read_only = OFF");
        }
    }

    @Test
    void application_DBと同じserver_uuidのinstanceは不適合() {
        assertThat(inspect(JOURNAL, appender, appenderPassword, true, journalRoot, SCHEMA))
                .contains("instance.sameServerAsApplication");
    }

    @Test
    void session時区を固定しない接続は不適合() {
        DataSource raw = new DriverManagerDataSource(JOURNAL.getJdbcUrl(), appender, appenderPassword);

        assertThat(PrivacyJournalStartupVerifier.inspect(raw, applicationRoot, SCHEMA, JOURNAL_ID, true,
                PrivacyJournalMigrations::fromClasspath)).contains("session.timeZone");
    }

    @Test
    void 許可集合を超える権限は不適合() {
        String user = PrivacyJournalTestPrincipals.uniqueName("nf07_exc");
        String password = PrivacyJournalTestPrincipals.randomPassword();
        PrivacyJournalTestPrincipals.createAppender(journalRoot, user, password);
        try {
            journalRoot.execute("GRANT DELETE ON " + SCHEMA + ".pj_event TO " + PrivacyJournalTestPrincipals.grantee(user));

            assertThat(inspect(JOURNAL, user, password, true)).contains("privilege.excess:pj_event.DELETE");
        } finally {
            PrivacyJournalTestPrincipals.dropUser(journalRoot, user);
        }
    }

    @Test
    void 必要な権限が欠けていれば不適合() {
        String user = PrivacyJournalTestPrincipals.uniqueName("nf07_mis");
        String password = PrivacyJournalTestPrincipals.randomPassword();
        PrivacyJournalTestPrincipals.createAppender(journalRoot, user, password);
        try {
            journalRoot.execute("REVOKE INSERT ON " + SCHEMA + ".pj_lease FROM " + PrivacyJournalTestPrincipals.grantee(user));

            assertThat(inspect(JOURNAL, user, password, true)).contains("privilege.missing:pj_lease.INSERT");
        } finally {
            PrivacyJournalTestPrincipals.dropUser(journalRoot, user);
        }
    }

    @Test
    void role経由の権限は不適合() {
        String user = PrivacyJournalTestPrincipals.uniqueName("nf07_rol");
        String role = PrivacyJournalTestPrincipals.uniqueName("nf07_role");
        String password = PrivacyJournalTestPrincipals.randomPassword();
        journalRoot.execute("CREATE ROLE '" + role + "'");
        PrivacyJournalTestPrincipals.createUser(journalRoot, user, password);
        try {
            PrivacyJournalTestPrincipals.grantAppender(journalRoot, "'" + role + "'");
            journalRoot.execute("GRANT '" + role + "' TO " + PrivacyJournalTestPrincipals.grantee(user));
            journalRoot.execute("SET DEFAULT ROLE '" + role + "' TO " + PrivacyJournalTestPrincipals.grantee(user));

            assertThat(inspect(JOURNAL, user, password, true)).contains("privilege.roleActive");
        } finally {
            PrivacyJournalTestPrincipals.dropUser(journalRoot, user);
            journalRoot.execute("DROP ROLE IF EXISTS '" + role + "'");
        }
    }

    @Test
    void global権限と他schemaへの書込権限は不適合() {
        String user = PrivacyJournalTestPrincipals.uniqueName("nf07_glb");
        String password = PrivacyJournalTestPrincipals.randomPassword();
        String otherSchema = PrivacyJournalTestPrincipals.uniqueName("nf07_other");
        PrivacyJournalTestPrincipals.createAppender(journalRoot, user, password);
        journalRoot.execute("CREATE DATABASE " + otherSchema);
        try {
            journalRoot.execute("GRANT PROCESS ON *.* TO " + PrivacyJournalTestPrincipals.grantee(user));
            journalRoot.execute("GRANT INSERT ON " + otherSchema + ".* TO " + PrivacyJournalTestPrincipals.grantee(user));

            assertThat(inspect(JOURNAL, user, password, true)).contains("privilege.global", "privilege.otherSchema");
        } finally {
            PrivacyJournalTestPrincipals.dropUser(journalRoot, user);
            journalRoot.execute("DROP DATABASE IF EXISTS " + otherSchema);
        }
    }

    @Test
    void 接続先schemaがexpected_schemaと違えば不適合() {
        assertThat(inspect(JOURNAL, appender, appenderPassword, true, applicationRoot, "privacy_journal_other"))
                .contains("schema.databaseMismatch");
    }

    @Test
    void Flyway履歴に失敗行やartifactに無い版があれば不適合() {
        journalRoot.update("INSERT INTO " + PrivacyJournalTestPrincipals.HISTORY_TABLE + " (installed_rank, version, "
                + "description, type, script, checksum, installed_by, execution_time, success) "
                + "VALUES (900, '2', 'later', 'SQL', 'V2__later.sql', 0, 'root', 0, 1), "
                + "(901, '3', 'failed', 'SQL', 'V3__failed.sql', 0, 'root', 0, 0)");
        try {
            assertThat(inspect(JOURNAL, appender, appenderPassword, true))
                    .contains("flyway.unknownVersion:2", "flyway.failedMigration");
        } finally {
            journalRoot.update("DELETE FROM " + PrivacyJournalTestPrincipals.HISTORY_TABLE
                    + " WHERE installed_rank IN (900, 901)");
        }
    }

    @Test
    void projection_stateのjournal_idが設定と違えば不適合() {
        applicationRoot.update("INSERT INTO t_privacy_projection_state (tenant_id, journal_id) VALUES ('tenant-x', ?)",
                "journal-other-01");
        try {
            assertThat(inspect(JOURNAL, appender, appenderPassword, true)).contains("projection.journalIdMismatch");
        } finally {
            applicationRoot.update("DELETE FROM t_privacy_projection_state WHERE journal_id = ?", "journal-other-01");
        }
    }

    // ---------------------------------------------------------------- helper

    private static List<String> inspect(MySQLContainer<?> container, String user, String password,
                                        boolean appenderRequired) {
        return inspect(container, user, password, appenderRequired, applicationRoot, SCHEMA);
    }

    private static List<String> inspect(MySQLContainer<?> container, String user, String password,
                                        boolean appenderRequired, JdbcTemplate application, String expectedSchema) {
        try (HikariDataSource pool = PrivacyJournalTestPools.pool(container.getJdbcUrl(), user, password)) {
            return PrivacyJournalStartupVerifier.inspect(pool, application, expectedSchema, JOURNAL_ID,
                    appenderRequired, PrivacyJournalMigrations::fromClasspath);
        }
    }

    private static JdbcTemplate root(MySQLContainer<?> container) {
        return new JdbcTemplate(new DriverManagerDataSource(container.getJdbcUrl(), "root", ROOT_PASSWORD));
    }
}
