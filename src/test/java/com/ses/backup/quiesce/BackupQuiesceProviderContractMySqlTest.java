package com.ses.backup.quiesce;

import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * RB-QUIESCE BWQ-011（U-4。design.md §5.3・§7・§9・§14）: backup の provider の SQL（{@code ops/backup/providers/quiesce-db.d}）と
 * Java の静止応答（{@link BackupQuiesceResponder}）が、同じ表・状態・時刻の意味で動くことを実 MySQL 8.4 で固定する。
 *
 * <ul>
 *   <li>provider の SQL は shell の provider（{@code lib/quiesce-db-common.sh} の {@code quiesce_db::run_sql}）と同じ形で実行する:
 *       先頭の印の後に {@code SET SESSION time_zone = '+00:00'}・{@code innodb_lock_wait_timeout = 5}・検査した値の
 *       {@code SET @p_…}、続けて file の文（行全体の注釈を除き、行末の {@code ;} で分ける）。接続は README「source 側の
 *       account の権限」と同じ権限だけの user（{@code bq_backup}。schema の {@code SELECT} 等と、表を作った後の要求の表への
 *       表単位の {@code INSERT}・{@code UPDATE}）。</li>
 *   <li>静止応答は PROCESS 権限の無い app の account（{@code bq_app}）で、session の time_zone は {@code +09:00}（provider と違える）。</li>
 *   <li>判定は SQL の {@code VERDICT} の行で見る。観測した接続元の照合は shell と同じ規則（{@link #foreignHosts}）で行う。</li>
 * </ul>
 * 各 SQL file の実行と判定・ack の状態の推移を日本語の log に出す（BWQ-011 の Demo）。
 */
@Tag("mysql")
@Testcontainers
class BackupQuiesceProviderContractMySqlTest {

    private static final Logger log = LoggerFactory.getLogger(BackupQuiesceProviderContractMySqlTest.class);
    private static final String DATABASE = "ses_bq_contract";
    private static final String APP_USER = "bq_app";
    private static final String APP_PASSWORD = "bq_app_pw_0011";
    private static final String BACKUP_USER = "bq_backup";
    private static final String BACKUP_PASSWORD = "bq_backup_pw_0011";
    private static final Path SQL_DIR = Path.of("ops/backup/providers/quiesce-db.d");
    /** provider の既定（design.md §12.4）。 */
    private static final int STALE = 60;
    private static final int DEAD = 300;
    private static final int LEASE = 60;
    private static final int RENEW = 15;
    private static final int MARGIN = 5;
    private static final int LEASE_MAX = 300;

    /** 表を作る前の表単位の GRANT の結果（§19 の未実測の前提の記録）。 */
    private static String grantBeforeTableOutcome;

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName(DATABASE).withUsername("root").withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10));

    private final List<BackupQuiesceResponder> responders = new ArrayList<>();
    private final List<BackupQuiesceLifecycleAnchor> anchors = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    @BeforeAll
    static void schema() throws Exception {
        try (Connection root = root(); Statement st = root.createStatement()) {
            st.execute("CREATE USER '" + BACKUP_USER + "'@'%' IDENTIFIED BY '" + BACKUP_PASSWORD + "'");
            // §19: 存在しない表への表単位の GRANT は CREATE を含めないと通らないか（MySQL の文書による。結果を記録する）
            Throwable early = catchThrowable(() -> st.execute("GRANT INSERT, UPDATE ON " + DATABASE
                    + ".sys_backup_quiesce_request TO '" + BACKUP_USER + "'@'%'"));
            grantBeforeTableOutcome = early == null ? "通った" : "拒否（" + early.getMessage() + "）";
            log.info("[RB-QUIESCE §19] 表を作る前の表単位の GRANT INSERT, UPDATE（MySQL {}）: {}", MYSQL.getDockerImageName(),
                    grantBeforeTableOutcome);
            st.execute("REVOKE ALL PRIVILEGES, GRANT OPTION FROM '" + BACKUP_USER + "'@'%'");
        }
        try (Connection root = root()) {
            ScriptUtils.executeSqlScript(root,
                    new FileSystemResource("src/main/resources/db/migration/V202__backup_write_quiesce_signal.sql"));
        }
        try (Connection root = root(); Statement st = root.createStatement()) {
            st.execute("CREATE TABLE t_business_marker (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "note VARCHAR(32) NOT NULL) ENGINE=InnoDB");
            st.execute("CREATE USER '" + APP_USER + "'@'%' IDENTIFIED BY '" + APP_PASSWORD + "'");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + DATABASE + ".* TO '" + APP_USER + "'@'%'");
            // README「source 側の account の権限」と同じ（表単位の INSERT・UPDATE は表を作った後に付ける。design.md §4）
            st.execute("GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON " + DATABASE + ".* TO '" + BACKUP_USER + "'@'%'");
            st.execute("GRANT RELOAD, REPLICATION CLIENT, SHOW_ROUTINE ON *.* TO '" + BACKUP_USER + "'@'%'");
            st.execute("GRANT INSERT, UPDATE ON " + DATABASE + ".sys_backup_quiesce_request TO '" + BACKUP_USER + "'@'%'");
        }
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection root = root(); Statement st = root.createStatement()) {
            st.executeUpdate("DELETE FROM sys_backup_quiesce_ack");
            st.executeUpdate("DELETE FROM sys_backup_quiesce_instance");
            st.executeUpdate("DELETE FROM sys_backup_quiesce_request");
            st.executeUpdate("DELETE FROM t_business_marker");
        }
    }

    @AfterEach
    void stop() throws InterruptedException {
        for (BackupQuiesceResponder responder : responders) {
            responder.destroy();
        }
        for (int i = anchors.size() - 1; i >= 0; i--) {
            anchors.get(i).destroy();
        }
        for (Thread thread : threads) {
            thread.join(70_000);
        }
    }

    // ---------------------------------------------------------------- 正常系

    @Test
    void 取得してackを待ちverifyが通り解除するとRESUMEDになる() throws Exception {
        BackupQuiesceLifecycleAnchor anchor = anchor(new BackupQuiesceProperties(), true);
        start(anchor);

        Rows precheck = sql("precheck", common(false));
        assertThat(precheck.verdict()).as(precheck.toString()).isEqualTo("OK");
        Request request = insertRequest("ONLINE", "CHECKPOINT", LEASE);
        Rows acquired = pollUntilDecided(request.id(), false, Duration.ofSeconds(20));
        assertThat(acquired.verdict()).as(acquired.toString()).isEqualTo("OK");
        assertThat(acquired.instances("REQUIRED")).hasSize(1);
        assertThat(acquired.instances("REQUIRED").get(0).get(5)).isEqualTo("QUIESCED");
        assertThat(foreignHosts(acquired)).as("観測した接続元は ack した instance の接続元だけ").isEmpty();
        assertThat(anchor.gate().state()).isEqualTo(BackupWriteGate.State.CLOSED);
        long consistency = acquired.nowEpochMicros();

        Rows verified = verify(request.id(), consistency, Duration.ofSeconds(15));
        assertThat(verified.verdict()).as(verified.toString()).isEqualTo("OK");
        assertThat(foreignHosts(verified)).isEmpty();
        assertThat(verified.instances("REQUIRED").get(0).get(13)).as("verify の後に観測し直した").isEqualTo("1");

        Rows released = sql("release", List.of("int:request_id=" + request.id(), "enum:status=RELEASED",
                "enum:reason=COMPLETED"));
        assertThat(released.first("RELEASE").get(1)).isEqualTo("1");
        awaitTrue(() -> "OK".equals(sqlQuietly("resume-check", List.of("int:request_id=" + request.id(),
                "int:stale_seconds=" + STALE)).verdict()), Duration.ofSeconds(15), "再開（RESUMED）");
        assertThat(ackState(request.id())).isEqualTo("RESUMED");
        awaitTrue(() -> anchor.gate().state() == BackupWriteGate.State.OPEN, Duration.ofSeconds(10), "解除で開く");
        log.info("[RB-QUIESCE BWQ-011] 正常系: 事前の確認 OK → 要求 {} → QUIESCED → verify OK → RELEASED → RESUMED",
                request.id());
    }

    @Test
    void backupのuserは業務の表とackの表に書けず要求の表だけに書ける() throws Exception {
        try (Connection backup = backup(); Statement st = backup.createStatement()) {
            assertThat(catchThrowable(() -> st.executeUpdate("INSERT INTO t_business_marker (note) VALUES ('x')")))
                    .as("業務の表への INSERT は拒否").isInstanceOf(SQLException.class);
            assertThat(catchThrowable(() -> st.executeUpdate("UPDATE sys_backup_quiesce_ack SET state = 'RESUMED'")))
                    .as("ack の表は app だけが書く").isInstanceOf(SQLException.class);
            assertThat(catchThrowable(() -> st.executeUpdate("DELETE FROM sys_backup_quiesce_request")))
                    .as("要求の表の DELETE は与えない").isInstanceOf(SQLException.class);
        }
        Request request = insertRequest("ONLINE", "CHECKPOINT", LEASE);
        assertThat(request.id()).isPositive();
        log.info("[RB-QUIESCE §19] 表を作る前の表単位の GRANT: {}", grantBeforeTableOutcome);
    }

    // ---------------------------------------------------------------- verify の失敗

    @Test
    void 延長しないとleaseが切れてverifyが失敗する() throws Exception {
        BackupQuiesceLifecycleAnchor anchor = anchor(new BackupQuiesceProperties(), true);
        start(anchor);
        Request request = insertRequest("ONLINE", "CHECKPOINT", 10);
        Rows acquired = pollUntilDecided(request.id(), false, Duration.ofSeconds(20));
        assertThat(acquired.verdict()).as(acquired.toString()).isEqualTo("OK");

        awaitTrue(() -> "RESUMED".equals(ackState(request.id())), Duration.ofSeconds(25), "lease の期限切れで再開");
        Rows verified = verify(request.id(), acquired.nowEpochMicros(), Duration.ofSeconds(5));
        assertThat(verified.verdict()).as(verified.toString()).startsWith("FAIL_");
    }

    @Test
    void 唯一のinstanceが止まり必要なinstanceが0になるとverifyが失敗する() throws Exception {
        BackupQuiesceLifecycleAnchor anchor = anchor(new BackupQuiesceProperties(), true);
        BackupQuiesceResponder responder = start(anchor);
        Request request = insertRequest("ONLINE", "CHECKPOINT", LEASE);
        Rows acquired = pollUntilDecided(request.id(), false, Duration.ofSeconds(20));
        assertThat(acquired.verdict()).isEqualTo("OK");

        // 正常な停止（業務の pool を閉じた後に STOPPED を書く。§6.11）
        responder.destroy();
        assertThat(anchor.writeStopped()).isTrue();
        assertThat(scalarLong("SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE status = 'STOPPED'")).isEqualTo(1L);
        Rows verified = verify(request.id(), acquired.nowEpochMicros(), Duration.ofSeconds(5));
        assertThat(verified.verdict()).as("STOPPED の instance は数えず、必要な instance が 0").isEqualTo("FAIL_NO_INSTANCE");
    }

    /**
     * 2d review P3-6: 応答が STOPPED を書かずに止まった（落ちた）場合、verify の後の観測し直しが来ないため、verify-check は観測の
     * 待ちの間ずっと WAIT で、provider の時限（{@code QUIESCE_VERIFY_OBSERVE_SECONDS}）で失敗する（OK にはならない）。
     */
    @Test
    void 応答がSTOPPEDを書かずに止まるとverifyは観測の待ちの間WAITのままで時限で失敗する() throws Exception {
        BackupQuiesceLifecycleAnchor anchor = anchor(new BackupQuiesceProperties(), true);
        BackupQuiesceResponder responder = start(anchor);
        Request request = insertRequest("ONLINE", "CHECKPOINT", LEASE);
        Rows acquired = pollUntilDecided(request.id(), false, Duration.ofSeconds(20));
        assertThat(acquired.verdict()).as(acquired.toString()).isEqualTo("OK");

        // 落ちた: 状態機械と heartbeat の thread が止まり、接続も閉じるが、STOPPED は書かない（writeStopped を呼ばない）
        responder.destroy();
        anchor.destroy();
        assertThat(scalarLong("SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE status = 'STOPPED'"))
                .as("STOPPED を書いていない").isZero();

        Rows requested = sql("verify-request", List.of("int:request_id=" + request.id()));
        assertThat(requested.first("VERIFY_REQUESTED").get(1)).isEqualTo("1");
        List<String> params = withRequest(request.id(), new ArrayList<>(common(false)));
        params.add("int:margin_seconds=" + MARGIN);
        params.add("int:consistency_epoch_us=" + acquired.nowEpochMicros());
        List<String> verdicts = new ArrayList<>();
        long giveUp = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() - giveUp < 0) {
            verdicts.add(sql("verify-check", params).verdict());
            Thread.sleep(1_000);
        }
        assertThat(verdicts).as("観測し直しが来ない間の verify-check の判定").isNotEmpty().containsOnly("WAIT");
        log.info("[RB-QUIESCE BWQ-011] STOPPED を書かずに止まった応答: verify-check は {} 回とも WAIT（provider は時限で失敗する）",
                verdicts.size());
    }

    /**
     * 2d review P3-1: preflight の SQL（{@code preflight.sql}）を README の権限だけの user で実行し、{@code SHOW GRANTS FOR
     * CURRENT_USER()} の実際の行を {@code preflight.sh} と同じ規則（{@link #requestTableGrantOk}）で判定する。表単位の権限の
     * 無い user では判定が偽になることも確かめる。
     */
    @Test
    void preflightのSQLをREADMEの権限のuserで実行しSHOW_GRANTSの行をshellと同じ規則で判定できる() throws Exception {
        BackupQuiesceLifecycleAnchor anchor = anchor(new BackupQuiesceProperties(), true);
        start(anchor);
        Rows preflight = sql("preflight", common(false));
        assertThat(preflight.first("TABLES").get(1)).as("信号表の 3 表").isEqualTo("3");
        assertThat(preflight.verdict()).as(preflight.toString()).isEqualTo("OK");
        assertThat(grantLines(preflight)).as("SHOW GRANTS の行").isNotEmpty();
        assertThat(requestTableGrantOk(preflight, DATABASE))
                .as("要求の表への表単位の INSERT・UPDATE がある（" + grantLines(preflight) + "）").isTrue();
        log.info("[RB-QUIESCE BWQ-011] preflight.sql の SHOW GRANTS の行: {}", grantLines(preflight));

        String noGrant = "bq_backup_nogrant";
        try (Connection root = root(); Statement st = root.createStatement()) {
            st.execute("CREATE USER '" + noGrant + "'@'%' IDENTIFIED BY '" + BACKUP_PASSWORD + "'");
            st.execute("GRANT SELECT ON " + DATABASE + ".* TO '" + noGrant + "'@'%'");
        }
        try (Connection without = DriverManager.getConnection(MYSQL.getJdbcUrl(), noGrant, BACKUP_PASSWORD)) {
            Rows denied = sqlOn(without, "preflight", common(false));
            assertThat(requestTableGrantOk(denied, DATABASE)).as("表単位の権限の無い user（" + grantLines(denied) + "）")
                    .isFalse();
        } finally {
            try (Connection root = root(); Statement st = root.createStatement()) {
                st.execute("DROP USER '" + noGrant + "'@'%'");
            }
        }
    }

    /** preflight.sql の GRANTS_BEGIN〜GRANTS_END の間の行（SHOW GRANTS の 1 行 = 1 欄）。 */
    private static List<String> grantLines(Rows rows) {
        List<String> lines = new ArrayList<>();
        boolean inGrants = false;
        for (List<String> row : rows.rows()) {
            if ("GRANTS_BEGIN".equals(row.get(0))) {
                inGrants = true;
            } else if ("GRANTS_END".equals(row.get(0))) {
                inGrants = false;
            } else if (inGrants) {
                lines.add(row.get(0));
            }
        }
        return lines;
    }

    /** {@code preflight.sh} の {@code preflight::quiesce_grants_ok} と同じ規則。 */
    static boolean requestTableGrantOk(Rows rows, String database) {
        java.util.regex.Pattern grant = java.util.regex.Pattern.compile(
                "^GRANT (.+) ON `([^`]+)`\\.`sys_backup_quiesce_request` TO ");
        for (String line : grantLines(rows)) {
            java.util.regex.Matcher match = grant.matcher(line);
            if (match.find() && match.group(2).equals(database)) {
                String privileges = "," + match.group(1).replace(" ", "") + ",";
                if (privileges.contains(",ALLPRIVILEGES,")
                        || (privileges.contains(",INSERT,") && privileges.contains(",UPDATE,"))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void 要求の前からのinstanceが整合時刻の後にackするとverifyが失敗する() throws Exception {
        BackupQuiesceLifecycleAnchor anchor = anchor(new BackupQuiesceProperties(), true);
        start(anchor);
        String revived = UUID.randomUUID().toString();
        // heartbeat が停止済みの閾値（300 秒）より古い instance（acquire の時は「無視」）
        insertInstance(revived, "RUNNING", "OPEN", -1_000, -400);
        Request request = insertRequest("ONLINE", "CHECKPOINT", LEASE);
        Rows acquired = pollUntilDecided(request.id(), false, Duration.ofSeconds(20));
        assertThat(acquired.verdict()).as(acquired.toString()).isEqualTo("OK");
        assertThat(acquired.instances("IGNORED")).extracting(row -> row.get(1)).contains(revived);

        Thread.sleep(1_100);
        // 区間の中で生き返り、整合時刻の後に ack した（その前に permit の持ち主が書いた可能性がある。D-P2-4 (b)）
        try (Connection root = root(); Statement st = root.createStatement()) {
            st.executeUpdate("UPDATE sys_backup_quiesce_instance SET last_beat_at = UTC_TIMESTAMP(6) WHERE instance_id = '"
                    + revived + "'");
            st.executeUpdate("INSERT INTO sys_backup_quiesce_ack (request_id, instance_id, state, scheduler_state, "
                    + "drain_millis, hold_until, observed_app_hosts, observed_at, acked_at) SELECT id, '" + revived
                    + "', 'QUIESCED', 'PAUSED', 0, lease_expires_at, '', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) "
                    + "FROM sys_backup_quiesce_request WHERE id = " + request.id());
        }
        Rows verified = verify(request.id(), acquired.nowEpochMicros(), Duration.ofSeconds(3));
        assertThat(verified.verdict()).as(verified.toString()).isEqualTo("FAIL_LATE_ACK");
    }

    @Test
    void ackしない要求の前からのinstanceを足すとverifyが失敗する() throws Exception {
        BackupQuiesceLifecycleAnchor anchor = anchor(new BackupQuiesceProperties(), true);
        start(anchor);
        Request request = insertRequest("ONLINE", "CHECKPOINT", LEASE);
        Rows acquired = pollUntilDecided(request.id(), false, Duration.ofSeconds(20));
        assertThat(acquired.verdict()).isEqualTo("OK");
        // 要求の前に起動していた（started_at < requested_at）が heartbeat の無かった instance が見えるようになった
        String added = UUID.randomUUID().toString();
        insertInstance(added, "RUNNING", "OPEN", -100, 0);
        Rows verified = verify(request.id(), acquired.nowEpochMicros(), Duration.ofSeconds(3));
        assertThat(verified.verdict()).as(verified.toString()).isEqualTo("FAIL_LATE_ACK");
    }

    // ---------------------------------------------------------------- 登録の割込み

    @Test
    void 事前の確認とINSERTの間に登録したinstanceのackも待つ() throws Exception {
        BackupQuiesceLifecycleAnchor first = anchor(new BackupQuiesceProperties(), true);
        start(first);
        Rows precheck = sql("precheck", common(false));
        assertThat(precheck.verdict()).isEqualTo("OK");
        assertThat(precheck.instances("REQUIRED")).hasSize(1);

        BackupQuiesceLifecycleAnchor second = anchor(new BackupQuiesceProperties(), true);
        start(second);
        Request request = insertRequest("ONLINE", "CHECKPOINT", LEASE);
        Rows acquired = pollUntilDecided(request.id(), false, Duration.ofSeconds(20));
        assertThat(acquired.verdict()).as(acquired.toString()).isEqualTo("OK");
        assertThat(acquired.instances("REQUIRED")).as("増えた instance も必要").hasSize(2)
                .allSatisfy(row -> assertThat(row.get(5)).isEqualTo("QUIESCED"));
        Rows verified = verify(request.id(), acquired.nowEpochMicros(), Duration.ofSeconds(15));
        assertThat(verified.verdict()).as(verified.toString()).isEqualTo("OK");
    }

    @Test
    void 要求の後に登録したinstanceはcheckpointの最中に後からackしてもverifyが通る() throws Exception {
        BackupQuiesceLifecycleAnchor first = anchor(new BackupQuiesceProperties(), true);
        start(first);
        Request request = insertRequest("ONLINE", "CHECKPOINT", LEASE);
        Rows acquired = pollUntilDecided(request.id(), false, Duration.ofSeconds(20));
        assertThat(acquired.verdict()).isEqualTo("OK");

        BackupQuiesceLifecycleAnchor late = anchor(new BackupQuiesceProperties(), true);
        startInBackground(late);
        awaitTrue(() -> scalarLongQuietly("SELECT COUNT(*) FROM sys_backup_quiesce_ack WHERE request_id = " + request.id()
                + " AND instance_id = '" + late.instanceId() + "' AND state = 'QUIESCED'") == 1L, Duration.ofSeconds(20),
                "要求の後に登録した instance の ack");
        Rows verified = verify(request.id(), acquired.nowEpochMicros(), Duration.ofSeconds(15));
        assertThat(verified.verdict()).as(verified.toString()).isEqualTo("OK");
        assertThat(verified.instances("REQUIRED")).anySatisfy(row -> {
            assertThat(row.get(1)).isEqualTo(late.instanceId());
            assertThat(row.get(4)).as("要求の前からではない").isEqualTo("0");
        });
        sql("release", List.of("int:request_id=" + request.id(), "enum:status=RELEASED", "enum:reason=COMPLETED"));
    }

    // ---------------------------------------------------------------- U-13（ONLINE の full の最中の新しい instance）

    @Test
    void ONLINEのfullの最中に新しいinstanceを登録すると要求がNEW_INSTANCEで中止され応答のgateが開く() throws Exception {
        BackupQuiesceLifecycleAnchor first = anchor(new BackupQuiesceProperties(), true);
        start(first);
        Request request = insertRequest("ONLINE", "FULL", LEASE);
        Rows acquired = pollUntilDecided(request.id(), true, Duration.ofSeconds(20));
        assertThat(acquired.verdict()).isEqualTo("OK");
        assertThat(sql("full-new-instance", List.of("int:request_id=" + request.id())).first("ABORT").get(1))
                .as("新しい instance が無ければ中止しない").isEqualTo("0");

        BackupQuiesceLifecycleAnchor newcomer = anchor(new BackupQuiesceProperties(), true);
        startInBackground(newcomer);
        awaitTrue(() -> scalarLongQuietly("SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE instance_id = '"
                + newcomer.instanceId() + "'") == 1L, Duration.ofSeconds(10), "新しい instance の登録");
        Rows aborted = sql("full-new-instance", List.of("int:request_id=" + request.id()));
        assertThat(aborted.first("ABORT").get(1)).as(aborted.toString()).isEqualTo("1");
        assertThat(scalarString("SELECT CONCAT(status, '/', release_reason) FROM sys_backup_quiesce_request WHERE id = "
                + request.id())).isEqualTo("ABORTED/NEW_INSTANCE");
        awaitTrue(() -> first.gate().state() == BackupWriteGate.State.OPEN, Duration.ofSeconds(15), "静止が解かれて開く");
        awaitTrue(() -> newcomer.gate().state() == BackupWriteGate.State.OPEN, Duration.ofSeconds(15), "新しい instance も起動する");
        Rows verified = verify(request.id(), acquired.nowEpochMicros(), Duration.ofSeconds(3));
        assertThat(verified.verdict()).as("中止された full は VALID にしない").startsWith("FAIL_");
    }

    @Test
    void APP_STOPPEDのfullの最中に起動しようとした応答は起動を中止し保持はABORTEDにならない() throws Exception {
        Rows precheck = sql("stopped-precheck", stoppedParams(true));
        assertThat(precheck.verdict()).as("表が空なら通る").isEqualTo("OK");
        Request request = insertRequest("APP_STOPPED", "FULL", LEASE);
        Rows held = sql("stopped-check", withRequest(request.id(), stoppedParams(true)));
        assertThat(held.verdict()).isEqualTo("OK");

        BackupQuiesceLifecycleAnchor starting = anchor(new BackupQuiesceProperties(), true);
        Throwable failure = catchThrowable(() -> responder(starting).afterSingletonsInstantiated());
        assertThat(failure).as("保持中は起動を中止する").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_STOPPED");
        // 起動の失敗では context が閉じ、業務の pool を閉じた後に STOPPED を書く（試験は STOPPED の書込みを直接呼んで模す）
        assertThat(starting.writeStopped()).isTrue();
        assertThat(scalarString("SELECT status FROM sys_backup_quiesce_instance WHERE instance_id = '"
                + starting.instanceId() + "'")).isEqualTo("STOPPED");

        assertThat(sql("full-new-instance", List.of("int:request_id=" + request.id())).first("ABORT").get(1))
                .as("APP_STOPPED の full は新しい instance で中止しない（D3-P2-2）").isEqualTo("0");
        assertThat(scalarString("SELECT status FROM sys_backup_quiesce_request WHERE id = " + request.id()))
                .isEqualTo("REQUESTED");
        Rows verified = sql("stopped-check", withRequest(request.id(), stoppedParams(true)));
        assertThat(verified.verdict()).as("起動を中止して STOPPED を書いた instance は数えない").isEqualTo("OK");
    }

    @Test
    void APP_STOPPEDの保持の登録と重なって登録したinstanceがあれば停止確認は失敗する() throws Exception {
        Request request = insertRequest("APP_STOPPED", "CHECKPOINT", LEASE);
        String registered = UUID.randomUUID().toString();
        insertInstance(registered, "STARTING", "UNARMED", 0, 0);
        Rows held = sql("stopped-check", withRequest(request.id(), stoppedParams(false)));
        assertThat(held.verdict()).as(held.toString()).isEqualTo("FAIL_ALIVE");
        Rows precheck = sql("stopped-precheck", stoppedParams(false));
        assertThat(precheck.verdict()).as("有効な要求があれば事前の確認も失敗").isEqualTo("FAIL_ACTIVE_REQUEST");
    }

    // ---------------------------------------------------------------- 停止口（DISABLED）

    @Test
    void 停止口の応答ではONLINEも停止確認も失敗しheartbeatが古くても失敗し片付けのSQLの後は通る() throws Exception {
        BackupQuiesceProperties disabled = new BackupQuiesceProperties();
        disabled.setEnabled(false);
        disabled.setDisabledTicket("CHG-1234");
        BackupQuiesceLifecycleAnchor stopped = anchor(disabled, true);
        start(stopped);
        assertThat(sql("precheck", common(false)).verdict()).isEqualTo("FAIL_DISABLED");
        assertThat(sql("stopped-precheck", stoppedParams(false)).verdict()).isEqualTo("FAIL_DISABLED");

        // 停止口の app が異常に止まり（heartbeat が止まり STOPPED を書かない）、heartbeat が 300 秒より古くなった
        String crashed = UUID.randomUUID().toString();
        insertInstance(crashed, "RUNNING", "DISABLED", -1_000, -400);
        responders.get(responders.size() - 1).destroy();
        assertThat(stopped.writeStopped()).isTrue();
        assertThat(scalarString("SELECT CONCAT(status, '/', gate_state) FROM sys_backup_quiesce_instance WHERE instance_id = '"
                + stopped.instanceId() + "'")).as("正常に止まった停止口は STOPPED（gate_state は DISABLED のまま）")
                .isEqualTo("STOPPED/DISABLED");
        assertThat(sql("stopped-precheck", stoppedParams(false)).verdict())
                .as("heartbeat が古くても STOPPED でない停止口の行があれば失敗").isEqualTo("FAIL_DISABLED");
        assertThat(sql("precheck", common(false)).verdict()).isEqualTo("FAIL_DISABLED");

        // design.md §9.4 の片付けの SQL（DBA が管理用の account で行う）
        try (Connection root = root(); Statement st = root.createStatement()) {
            assertThat(st.executeUpdate("UPDATE sys_backup_quiesce_instance SET status = 'STOPPED', stopped_at = UTC_TIMESTAMP(6) "
                    + "WHERE instance_id = '" + crashed + "' AND gate_state = 'DISABLED' AND status <> 'STOPPED'")).isEqualTo(1);
        }
        assertThat(sql("stopped-precheck", stoppedParams(false)).verdict()).as("片付けの後は停止確認が通る").isEqualTo("OK");
        BackupQuiesceLifecycleAnchor online = anchor(new BackupQuiesceProperties(), true);
        start(online);
        assertThat(sql("precheck", common(false)).verdict()).as("片付けの後は ONLINE も通る").isEqualTo("OK");
    }

    // ---------------------------------------------------------------- 結果不明の ack

    @Test
    void QUIESCEDのcommitの後に応答の接続を切っても応答はhold_untilより前に開かずverifyが通る() throws Exception {
        BackupQuiesceProperties properties = new BackupQuiesceProperties();
        BackupQuiesceLifecycleAnchor real = anchor(properties, true);
        BackupQuiesceResponderMySqlTest.FailingRepository failing =
                new BackupQuiesceResponderMySqlTest.FailingRepository(real.repository());
        failing.afterQuiescedInsert = () -> {
            killAppConnections();
            throw new QueryTimeoutException("ack の commit の後に接続が切れた（結果不明）");
        };
        BackupWriteGate gate = new BackupWriteGate(BackupWriteGate.Mode.ARMED, true, properties.getFenceAfter(),
                properties.getDeferredWritesMax());
        BackupQuiesceLifecycleAnchor anchor = new BackupQuiesceLifecycleAnchor(properties, gate, failing, true);
        anchors.add(anchor);
        start(anchor);
        Request request = insertRequest("ONLINE", "CHECKPOINT", LEASE);
        Rows acquired = pollUntilDecided(request.id(), false, Duration.ofSeconds(20));
        assertThat(acquired.verdict()).as(acquired.toString()).isEqualTo("OK");
        Thread.sleep(3_000);
        assertThat(anchor.gate().state()).as("結果不明の後も hold 期限の前に開かない").isEqualTo(BackupWriteGate.State.CLOSED);
        Rows verified = verify(request.id(), acquired.nowEpochMicros(), Duration.ofSeconds(15));
        assertThat(verified.verdict()).as(verified.toString()).isEqualTo("OK");
        sql("release", List.of("int:request_id=" + request.id(), "enum:status=RELEASED", "enum:reason=COMPLETED"));
        awaitTrue(() -> anchor.gate().state() == BackupWriteGate.State.OPEN, Duration.ofSeconds(15), "解除で開く");
    }

    // ---------------------------------------------------------------- 延長と DDL lock

    @Test
    void 延長はleaseを延ばし上限で止まり解かれた要求は延ばさない() throws Exception {
        BackupQuiesceLifecycleAnchor anchor = anchor(new BackupQuiesceProperties(), true);
        start(anchor);
        Request request = insertRequest("ONLINE", "CHECKPOINT", LEASE);
        Rows renewed = sql("renew", List.of("int:request_id=" + request.id(), "int:lease_max_seconds=" + LEASE_MAX,
                "int:dead_seconds=" + DEAD));
        assertThat(renewed.first("RENEW").get(1)).isEqualTo("1");
        Rows capped = sql("renew", List.of("int:request_id=" + request.id(), "int:lease_max_seconds=1",
                "int:dead_seconds=" + DEAD));
        assertThat(capped.first("LEASE").get(4)).as("上限（秒）").isEqualTo("1");
        Thread.sleep(1_100);
        Rows afterCap = sql("renew", List.of("int:request_id=" + request.id(), "int:lease_max_seconds=1",
                "int:dead_seconds=" + DEAD));
        assertThat(afterCap.first("RENEW").get(1)).as("上限を超えて延ばさない").isEqualTo("0");

        sql("release", List.of("int:request_id=" + request.id(), "enum:status=ABORTED", "enum:reason=ABORTED"));
        Rows afterRelease = sql("renew", List.of("int:request_id=" + request.id(), "int:lease_max_seconds=" + LEASE_MAX,
                "int:dead_seconds=" + DEAD));
        assertThat(afterRelease.first("RENEW").get(1)).isEqualTo("0");
        assertThat(afterRelease.first("LEASE").get(1)).isEqualTo("ABORTED");
    }

    @Test
    void DDL凍結のlockはFlywayのcallbackと同じ名前で取れ他のsessionが持てば取れない() throws Exception {
        try (Connection holder = backup()) {
            Rows lock = sqlOn(holder, "ddl-lock", List.of("int:lock_wait_seconds=1"));
            assertThat(lock.first("DDL_LOCK").get(1)).isEqualTo("1");
            assertThat(scalarLong("SELECT IS_FREE_LOCK('ses_backup_ddl_freeze')")).isZero();
            try (Connection other = backup()) {
                Rows busy = sqlOn(other, "ddl-lock", List.of("int:lock_wait_seconds=1"));
                assertThat(busy.first("DDL_LOCK").get(1)).as("他の session（Flyway の job 等）が持てば 0").isEqualTo("0");
            }
        }
        assertThat(scalarLong("SELECT IS_FREE_LOCK('ses_backup_ddl_freeze')")).as("session が閉じると返る").isEqualTo(1L);
    }

    // ---------------------------------------------------------------- provider の SQL の実行（shell の provider と同じ形）

    /** SQL の結果（行ごとの欄。1 欄目が行の種類）。 */
    record Rows(String name, List<List<String>> rows) {
        List<String> first(String tag) {
            return rows.stream().filter(row -> tag.equals(row.get(0))).findFirst()
                    .orElseThrow(() -> new AssertionError(name + " に " + tag + " の行がありません: " + rows));
        }

        String verdict() {
            return first("VERDICT").get(1);
        }

        long nowEpochMicros() {
            return Long.parseLong(first("VERDICT").get(3));
        }

        List<List<String>> instances(String cls) {
            return rows.stream().filter(row -> "INSTANCE".equals(row.get(0)) && cls.equals(row.get(2))).toList();
        }

        @Override
        public String toString() {
            return name + rows;
        }
    }

    record Request(long id, long requestedEpochMicros) {
    }

    private Rows sql(String name, List<String> params) throws Exception {
        try (Connection backup = backup()) {
            return sqlOn(backup, name, params);
        }
    }

    private Rows sqlQuietly(String name, List<String> params) {
        try {
            return sql(name, params);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** {@code quiesce_db::run_sql} と同じ: 前置き（time_zone・lock 待ち・{@code SET @p_…}）の後に file の文を順に実行する。 */
    private static Rows sqlOn(Connection connection, String name, List<String> params) throws Exception {
        List<String> statements = new ArrayList<>();
        statements.add("SET SESSION time_zone = '+00:00'");
        statements.add("SET SESSION innodb_lock_wait_timeout = 5");
        for (String param : params) {
            statements.add(paramStatement(param));
        }
        statements.addAll(fileStatements(name));
        List<List<String>> rows = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            for (String sql : statements) {
                if (st.execute(sql)) {
                    try (ResultSet rs = st.getResultSet()) {
                        int columns = rs.getMetaData().getColumnCount();
                        while (rs.next()) {
                            List<String> row = new ArrayList<>();
                            for (int i = 1; i <= columns; i++) {
                                String value = rs.getString(i);
                                row.add(value == null ? "NULL" : value);
                            }
                            rows.add(row);
                        }
                    }
                }
            }
        }
        Rows result = new Rows(name, rows);
        String verdict = rows.stream().filter(row -> "VERDICT".equals(row.get(0))).map(row -> row.get(1) + "（" + row.get(5) + "）")
                .findFirst().orElse("-");
        log.info("[RB-QUIESCE BWQ-011] provider の SQL {}.sql を実行しました（引数 {}）: 判定={}、行={}", name, params, verdict,
                rows.size());
        return result;
    }

    /** {@code quiesce_db::param_line} と同じ型の検査（整数・hex・列挙）。 */
    private static String paramStatement(String spec) {
        String type = spec.substring(0, spec.indexOf(':'));
        String rest = spec.substring(spec.indexOf(':') + 1);
        String key = rest.substring(0, rest.indexOf('='));
        String value = rest.substring(rest.indexOf('=') + 1);
        assertThat(key).matches("^[a-z][a-z0-9_]{0,40}$");
        return switch (type) {
            case "int" -> {
                assertThat(value).matches("^[0-9]{1,19}$");
                yield "SET @p_" + key + " = " + value;
            }
            case "hex" -> {
                assertThat(value).matches("^([0-9a-f]{2}){1,128}$");
                yield "SET @p_" + key + " = '" + value + "'";
            }
            case "enum" -> {
                assertThat(key + ":" + value).matches("^(mode:(ONLINE|APP_STOPPED)|job_kind:(FULL|CHECKPOINT)"
                        + "|status:(RELEASED|ABORTED)|reason:(COMPLETED|ABORTED|VERIFY_FAILED))$");
                yield "SET @p_" + key + " = '" + value + "'";
            }
            default -> throw new IllegalArgumentException(type);
        };
    }

    /** file の文（行全体の注釈を除き、行末の ; で分ける。tests/script-contract-test.sh が同じ規則を固定する）。 */
    private static List<String> fileStatements(String name) throws IOException {
        List<String> lines = Files.readAllLines(SQL_DIR.resolve(name + ".sql"), StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("-- ses-backup:quiesce-db:" + name);
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.strip().startsWith("--") || line.isBlank()) {
                continue;
            }
            current.append(line).append('\n');
            if (line.strip().endsWith(";")) {
                String sql = current.toString().strip();
                statements.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        assertThat(current.toString().strip()).as(name + " の最後の文が ; で終わる").isEmpty();
        return statements;
    }

    private static List<String> common(boolean full) {
        return List.of("int:stale_seconds=" + STALE, "int:dead_seconds=" + DEAD, "int:protocol_version=1",
                "int:lease_seconds=" + LEASE, "int:renew_seconds=" + RENEW, "int:full=" + (full ? 1 : 0));
    }

    private static List<String> stoppedParams(boolean full) {
        return List.of("int:stale_seconds=" + STALE, "int:dead_seconds=" + DEAD, "int:full=" + (full ? 1 : 0));
    }

    private static List<String> withRequest(long id, List<String> params) {
        List<String> all = new ArrayList<>();
        all.add("int:request_id=" + id);
        all.addAll(params);
        return all;
    }

    private Request insertRequest(String mode, String jobKind, int leaseSeconds) throws Exception {
        String token = UUID.randomUUID().toString().replace("-", "");
        String requestedByHex = String.format("%x", new java.math.BigInteger(1,
                "contract-test:host".getBytes(StandardCharsets.UTF_8)));
        Rows inserted = sql("insert-request", List.of("hex:token=" + token, "enum:mode=" + mode, "enum:job_kind=" + jobKind,
                "int:lease_seconds=" + leaseSeconds, "hex:requested_by_hex=" + requestedByHex, "int:protocol_version=1"));
        List<String> row = inserted.first("REQUEST");
        assertThat(scalarString("SELECT requested_by FROM sys_backup_quiesce_request WHERE id = " + row.get(1)))
                .as("requested_by は hex から戻る").isEqualTo("contract-test:host");
        return new Request(Long.parseLong(row.get(1)), Long.parseLong(row.get(4)));
    }

    /** acquire の待ち（毎回新しい transaction で分類し直す）。OK・FAIL で返り、WAIT の間は 1 秒ごとに読み直す。 */
    private Rows pollUntilDecided(long id, boolean full, Duration max) throws Exception {
        long giveUp = System.nanoTime() + max.toNanos();
        List<String> params = withRequest(id, new ArrayList<>(common(full)));
        params.add("int:margin_seconds=" + MARGIN);
        while (true) {
            Rows rows = sql("poll-acks", params);
            log.info("[RB-QUIESCE BWQ-011] ack の状態（要求 {}）: {}", id, rows.rows().stream()
                    .filter(row -> "INSTANCE".equals(row.get(0))).map(row -> row.get(1).substring(0, 8) + "=" + row.get(2)
                            + "/" + row.get(5)).toList());
            if (!"WAIT".equals(rows.verdict()) || System.nanoTime() - giveUp > 0) {
                return rows;
            }
            Thread.sleep(1_000);
        }
    }

    /** verify（verify_requested_at を書き、観測し直しを最長 {@code max} 待って評価する）。 */
    private Rows verify(long id, long consistency, Duration max) throws Exception {
        Rows requested = sql("verify-request", List.of("int:request_id=" + id));
        if (!"1".equals(requested.first("VERIFY_REQUESTED").get(1))) {
            return new Rows("verify-request", List.of(List.of("VERDICT", "FAIL_REQUEST_GONE", "-", "0", "0", "-")));
        }
        long giveUp = System.nanoTime() + max.toNanos();
        List<String> params = withRequest(id, new ArrayList<>(common(false)));
        params.add("int:margin_seconds=" + MARGIN);
        params.add("int:consistency_epoch_us=" + consistency);
        while (true) {
            Rows rows = sql("verify-check", params);
            if (!"WAIT".equals(rows.verdict()) || System.nanoTime() - giveUp > 0) {
                return rows;
            }
            Thread.sleep(1_000);
        }
    }

    /** shell の {@code quiesce_db::foreign_hosts} と同じ: ack した必要な instance の接続元に無い観測した接続元。 */
    static Set<String> foreignHosts(Rows rows) {
        Set<String> allowed = new HashSet<>();
        Set<String> foreign = new HashSet<>();
        List<List<String>> acked = rows.rows().stream().filter(row -> "INSTANCE".equals(row.get(0))
                && "REQUIRED".equals(row.get(2)) && "QUIESCED".equals(row.get(5))).toList();
        acked.forEach(row -> allowed.add(row.get(8)));
        for (List<String> row : acked) {
            if ("-".equals(row.get(12))) {
                continue;
            }
            for (String entry : row.get(12).split(",")) {
                if (!entry.isEmpty()) {
                    String host = entry.substring(0, entry.lastIndexOf('='));
                    if (!allowed.contains(host)) {
                        foreign.add(host);
                    }
                }
            }
        }
        return foreign;
    }

    // ---------------------------------------------------------------- 静止応答（実の responder）

    private BackupQuiesceLifecycleAnchor anchor(BackupQuiesceProperties properties, boolean prod) {
        StandardEnvironment environment = new StandardEnvironment();
        if (prod) {
            environment.setActiveProfiles("prod");
        }
        BackupQuiesceLifecycleAnchor anchor = new BackupQuiesceLifecycleAnchor(appDataSource(), properties, environment);
        anchors.add(anchor);
        return anchor;
    }

    private BackupQuiesceResponder responder(BackupQuiesceLifecycleAnchor anchor) {
        Executor direct = Runnable::run;
        BackupQuiesceResponder responder = new BackupQuiesceResponder(anchor, new BackupQuiesceMetrics(anchor.gate()),
                () -> direct, true);
        responders.add(responder);
        return responder;
    }

    private BackupQuiesceResponder start(BackupQuiesceLifecycleAnchor anchor) {
        BackupQuiesceResponder responder = responder(anchor);
        responder.afterSingletonsInstantiated();
        responder.onApplicationReady(null);
        return responder;
    }

    /** 静止の最中に起動する（起動の待ちで main の thread が止まるため別の thread で動かす）。 */
    private void startInBackground(BackupQuiesceLifecycleAnchor anchor) {
        BackupQuiesceResponder responder = responder(anchor);
        Thread thread = new Thread(() -> {
            try {
                responder.afterSingletonsInstantiated();
                responder.onApplicationReady(null);
            } catch (RuntimeException failure) {
                log.info("[RB-QUIESCE BWQ-011] 静止の最中に起動した応答の起動が止まりました: {}", failure.getMessage());
            }
        }, "bq-contract-startup");
        thread.setDaemon(true);
        threads.add(thread);
        thread.start();
    }

    /** app 側の接続: PROCESS 権限の無い専用 account、session の time_zone は +09:00（provider と違える）。 */
    private static DataSourceProperties appDataSource() {
        DataSourceProperties properties = new DataSourceProperties();
        String url = MYSQL.getJdbcUrl();
        properties.setUrl(url + (url.contains("?") ? "&" : "?") + "sessionVariables=time_zone='%2B09:00'");
        properties.setUsername(APP_USER);
        properties.setPassword(APP_PASSWORD);
        return properties;
    }

    // ---------------------------------------------------------------- helper

    private static Connection root() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    private static Connection backup() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), BACKUP_USER, BACKUP_PASSWORD);
    }

    /** 試験の instance の行（root で入れる。時刻は今からの秒数）。 */
    private static void insertInstance(String id, String status, String gateState, int startedOffsetSeconds,
                                       int beatOffsetSeconds) throws SQLException {
        try (Connection root = root(); Statement st = root.createStatement()) {
            st.executeUpdate("INSERT INTO sys_backup_quiesce_instance (instance_id, host_name, db_client_host, db_connection_id, "
                    + "app_commit, protocol_version, poll_interval_ms, heartbeat_interval_ms, fence_after_ms, "
                    + "max_hold_checkpoint_ms, max_hold_full_ms, scheduling_enabled, status, gate_state, started_at, "
                    + "last_beat_at) VALUES ('" + id + "', 'contract-host', '10.99.0.1', 1, 'test', 1, 1000, 5000, 20000, "
                    + "300000, 3600000, 1, '" + status + "', '" + gateState + "', TIMESTAMPADD(SECOND, " + startedOffsetSeconds
                    + ", UTC_TIMESTAMP(6)), TIMESTAMPADD(SECOND, " + beatOffsetSeconds + ", UTC_TIMESTAMP(6)))");
        }
    }

    private static void killAppConnections() {
        try (Connection root = root(); Statement st = root.createStatement()) {
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT ID FROM information_schema.PROCESSLIST WHERE USER = '" + APP_USER + "'")) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            for (long id : ids) {
                try {
                    st.execute("KILL " + id);
                } catch (SQLException alreadyGone) {
                    // 既に切れた接続は無視する
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String ackState(long requestId) {
        try {
            return scalarString("SELECT GROUP_CONCAT(state ORDER BY instance_id) FROM sys_backup_quiesce_ack WHERE request_id = "
                    + requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection root = root(); Statement st = root.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection root = root(); Statement st = root.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static long scalarLongQuietly(String sql) {
        try {
            return scalarLong(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void awaitTrue(BooleanSupplier condition, Duration max, String what) throws InterruptedException {
        long giveUp = System.nanoTime() + max.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - giveUp).as("待っても満たされない: " + what).isNegative();
            Thread.sleep(200);
        }
    }
}
