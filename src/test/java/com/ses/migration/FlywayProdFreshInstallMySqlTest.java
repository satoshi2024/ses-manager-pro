package com.ses.migration;

import com.ses.config.ProductionSchemaVersionGuard;
import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本番初回導入（空DBへのfresh install）を、本番の専用Flyway jobと同じ設定で実MySQL上に再現する。
 *
 * <p>前提（2026-11-13 ユーザー決定）: 本番DBは未構築で、初回リリースは空DBへの新規導入である。
 * 配備は全停止方式で、migrationは専用のFlyway job（{@code mvn -Pprod-migrate}）が単独で実行し、
 * アプリinstanceは実行しない（{@code spring.flyway.enabled=false}）。本testはjobが読む
 * {@code ops/flyway/flyway-prod.conf} を同じparserで読み（{@link ProductionFlywayJobConfig}）、
 * jobと同じ設定key（環境変数が写されるkey）へ接続情報と初期管理者パスワードhashだけを足して空DBへ適用する。
 * callbackもjob設定のclass名から読み込む（{@code LegacyDatabaseFlywayCallback} と初期管理者hashの検査）。
 *
 * <p>既存testとの役割分担（重複させない）:
 * <ul>
 *   <li>{@link FlywayMigrationSmokeTest}: {@code classpath:db/migration} だけ・baseline設定なしで空DBへ
 *       通し適用し、個別のschema形状を検証する。本testはschema形状を再検証しない。</li>
 *   <li>{@link FlywayRepairRunbookTest}: 旧prod履歴（V10 checksum不一致）からのrepair経路。</li>
 *   <li>本test: job設定で、(1) 全migrationがsuccess=1・failed 0・pending 0、(2) 最新版 =
 *       {@link LatestMigrationVersion#LATEST}、(3) 初期adminが注入hashになり公開パスワードが残らず
 *       初回変更が要求されること（配備決定-3）、(4) V2 sampleと派生行が消えること（配備決定-1）、
 *       (5) 空DBではbaselineされずV1から適用されること、(6) 再migrateがno-opでvalidateが通り、
 *       secretを変えて再実行してもadminを書き換えないこと、(7) 起動時schema guardが通ること、
 *       (8) hashが未設定・空・BCrypt以外ならmigrationを1件も実行しないこと、を検証する。
 *       非空DBではbaselineが作動することも対照として示す。</li>
 * </ul>
 *
 * <p>本番secretは一切使わない。DB接続はTestcontainersの使い捨てrootだけを使い、初期管理者パスワードは
 * 実行ごとに乱数で生成してBCrypt化したhashを注入する。
 */
@Tag("mysql")
@Testcontainers
class FlywayProdFreshInstallMySqlTest {

    /**
     * 本番と同系列の固定tagを使う（{@code ops/backup/tests/docker-compose.integration.yml} の
     * {@code mysql:8.0.36} と揃える）。本番のMySQL版がDBAから確定したら
     * {@code -Dses.flyway.fresh-install.mysql-image=mysql:8.0.xx} で差し替えられる。
     */
    private static final String MYSQL_IMAGE =
            System.getProperty("ses.flyway.fresh-install.mysql-image", "mysql:8.0.36");

    private static final String PROD_LOCATION = "classpath:db/migration-prod";
    private static final String ADMIN_PASSWORD_SCRIPT = "R__update_admin_password_bcrypt.sql";
    private static final String SAMPLE_DATA_SCRIPT = "R__remove_v2_sample_data.sql";
    /** 旧版の {@code R__update_admin_password_bcrypt.sql} が書いていた admin123 の公開BCrypt hash。 */
    private static final String PUBLISHED_ADMIN123_HASH =
            "$2a$10$b4R.5YvbxFPJ5C39IEFE/ua.G9F82I11lOAyGGNBvWJuLSVFUl41y";
    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$1[0-9]\\$[./A-Za-z0-9]{53}$");
    private static final Pattern VERSIONED_SQL = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");
    private static final String NON_EMPTY_DATABASE = "ses_prod_fresh_nonempty";
    private static final List<String> SAMPLE_TABLES =
            List.of("m_customer", "t_project", "t_engineer", "t_proposal", "t_contract");
    private static final SecureRandom RANDOM = new SecureRandom();

    @Container
    @SuppressWarnings("resource") // ライフサイクルは Testcontainers Extension が管理する。
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("ses_prod_fresh_install")
            .withUsername("root")
            .withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withStartupAttempts(3);

    @Test
    void 本番jobの設定で空DBへV1からLATESTまで適用しsampleを消し初期adminを注入hashにする() throws Exception {
        Map<String, String> conf = ProductionFlywayJobConfig.load();
        String url = jdbcUrl(MYSQL.getDatabaseName());
        String initialPassword = randomPassword();
        String initialHash = new BCryptPasswordEncoder().encode(initialPassword);

        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE()"),
                    "fresh installの前提: 対象schemaに表が1件も無いこと");
        }

        Flyway flyway = job(url, initialHash).load();
        int pendingBefore = flyway.info().pending().length;
        MigrateResult first = flyway.migrate();

        assertTrue(first.success, "job設定のmigrateが成功すること");
        assertEquals(pendingBefore, first.migrationsExecuted,
                "空DBでは解決済みの全migration（versioned + repeatable）が1回ずつ実行されること");

        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            assertHistoryAppliedFromV1WithoutBaseline(st, conf);
            assertAllResolvedMigrationsSucceeded(flyway);
            assertLatestVersion(st, flyway);
            assertRepeatablesAppliedAfterVersioned(st);
            assertInitialAdmin(st, initialPassword, initialHash);
            assertSampleDataAndDerivedRowsRemoved(st);
            assertNoSampleReferenceRemains(st);
            assertInitialRepairAndLedgerTables(st);
            assertEquals(List.of(), rows(st, "SELECT routine_name FROM information_schema.routines "
                            + "WHERE routine_schema = DATABASE() ORDER BY routine_name"),
                    "migration用のhelper procedure（V102_1の残置分・admin scriptの一時procedureを含む）が残らないこと");
        }

        assertEquals(LatestMigrationVersion.LATEST,
                new ProductionSchemaVersionGuard(dataSource(url), ProductionFlywayJobConfig.locations(conf), false)
                        .verify(),
                "job適用後のDBに対して、prodの起動時schema guardが通ること（hashはアプリへ渡さない）");

        List<String> historyBefore;
        List<String> schemaBefore;
        String adminHashBefore;
        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            historyBefore = history(st);
            schemaBefore = schemaFingerprint(st);
            adminHashBefore = adminPassword(st);
        }

        // 次回のjob実行（同じsecret）を、新しいFlyway instanceで再現する。
        // LegacyDatabaseFlywayCallbackはt_bp_payment存在時に補償DDLを試みるため、schema全体の不変も確認する。
        Flyway rerun = job(url, initialHash).load();
        MigrateResult second = rerun.migrate();
        assertTrue(second.success, "2回目のmigrateも成功扱いであること");
        assertEquals(0, second.migrationsExecuted, "2回目のmigrateは1件も実行しない（no-op）");
        assertEquals(0, rerun.info().pending().length, "2回目のmigrate後もpendingが無いこと");

        ValidateResult validation = rerun.validateWithResult();
        assertTrue(validation.validationSuccessful,
                "job設定のvalidateが成功すること: " + validation.getAllErrorMessages());
        rerun.validate();

        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            assertEquals(historyBefore, history(st),
                    "2回目のmigrateでflyway_schema_historyが1行も変化しない（repeatableも再適用されない）");
            assertEquals(schemaBefore, schemaFingerprint(st),
                    "2回目のmigrate（BEFORE_MIGRATE callbackを含む）でschemaが変化しない");
            assertEquals(adminHashBefore, adminPassword(st),
                    "2回目のmigrateでadminのhashが書き換わらない");
        }

        // 初回ログイン前にsecretを入れ替えて再実行した場合。placeholderがchecksumへ入るため
        // admin scriptは再適用されるが、adminは既に注入済みhashなので書き換えない（一度だけ）。
        String rotatedHash = new BCryptPasswordEncoder().encode(randomPassword());
        Flyway rotated = job(url, rotatedHash).load();
        MigrateResult third = rotated.migrate();
        assertTrue(third.success, "secret入替後のmigrateも成功すること");
        assertEquals(1, third.migrationsExecuted, "checksumが変わったadmin scriptだけが再実行されること");
        ValidateResult rotatedValidation = rotated.validateWithResult();
        assertTrue(rotatedValidation.validationSuccessful,
                "secret入替後のvalidateが成功すること: " + rotatedValidation.getAllErrorMessages());

        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            assertEquals(initialHash, adminPassword(st),
                    "初回適用後は別のhashを注入してもadminのパスワードを書き換えない");
            assertEquals(1, scalar(st, "SELECT password_change_required FROM sys_user WHERE username = 'admin'"));
            assertEquals(schemaBefore, schemaFingerprint(st), "admin scriptの再実行でschemaが変化しない");
            assertEquals(historyBefore.size() + 1, history(st).size(), "admin scriptの履歴が1行だけ増える");
            assertEquals(2L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE script = '"
                    + ADMIN_PASSWORD_SCRIPT + "' AND success = 1"));
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE script = '"
                    + SAMPLE_DATA_SCRIPT + "'"), "sample削除は再実行されない");
            for (String table : SAMPLE_TABLES) {
                assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM " + table), table);
            }
        }
    }

    /**
     * 対照実験: baseline-on-migrate=true は「履歴表が無い非空schema」をV9としてbaselineする。
     * 空DB前提が崩れた（手作業で表が作られた等）状態でjobを流すと、V1〜V9を飛ばしてV10で失敗する。
     * 本番手順で「対象schemaが空であること」を事前確認する根拠であり、上のtestが
     * baseline設定を実際に有効化した状態で「baselineされない」ことを示している裏付けでもある。
     */
    @Test
    void 非空schemaではbaseline_on_migrateがV9へbaselineしV10で失敗する() throws Exception {
        Map<String, String> conf = ProductionFlywayJobConfig.load();
        String url = createDatabase(NON_EMPTY_DATABASE);
        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE w1b3_stray_object (id INT PRIMARY KEY)");
        }

        Flyway flyway = job(url, new BCryptPasswordEncoder().encode(randomPassword())).load();
        assertThrows(FlywayException.class, flyway::migrate,
                "V1〜V9を飛ばしたschemaではV10のt_bp_payment参照で失敗すること");

        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE type = 'BASELINE' AND version = '" + conf.get("flyway.baselineVersion") + "'"),
                    "非空schemaではbaseline-versionでBASELINE行が作られること");
            assertEquals(conf.get("flyway.baselineDescription"), queryString(st,
                    "SELECT description FROM flyway_schema_history WHERE type = 'BASELINE'"));
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1'"),
                    "baseline後はV1が適用されないこと");
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version = '10' AND success = 0"),
                    "最初のpending（V10）がfailed行として残ること");
        }
    }

    /**
     * 配備決定-3のfail-closed: 初期管理者hash（環境変数 FLYWAY_PLACEHOLDERS_ADMIN_INITIAL_PASSWORD_HASH）が
     * 未設定・空・BCrypt以外・公開済みhashなら、migrationを1件も実行せずにjobが失敗し、起動時guardも通らない。
     * 失敗messageへ注入値を出さないことも確認する（job logへhashや平文を残さない）。
     */
    @Test
    void 初期管理者hashが未設定_空_BCrypt以外ならmigrationを1件も実行せずに失敗する() throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("missing", null);
        cases.put("empty", "");
        cases.put("blank", "   ");
        cases.put("plaintext", "Plain-Initial-" + randomPassword());
        cases.put("lowcost", new BCryptPasswordEncoder(4).encode(randomPassword()));
        cases.put("published", PUBLISHED_ADMIN123_HASH);
        Map<String, String> conf = ProductionFlywayJobConfig.load();

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            String url = createDatabase("ses_prod_admin_hash_" + entry.getKey());
            String value = entry.getValue();

            FlywayException failure = assertThrows(FlywayException.class, () -> job(url, value).load().migrate(),
                    "初期管理者hashが不正ならjobが失敗すること: " + entry.getKey());
            if (value != null && !value.isBlank()) {
                assertFalse(causeChainMessages(failure).contains(value),
                        "失敗messageへ注入値を出さないこと: " + entry.getKey());
            }

            try (Connection conn = connect(url); Statement st = conn.createStatement()) {
                assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'"),
                        "migrationが1件も実行されていないこと: " + entry.getKey());
                if (scalar(st, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name = 'flyway_schema_history'") == 1L) {
                    assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history"),
                            "履歴行が1件も無いこと（repairなしで再実行できる）: " + entry.getKey());
                }
            }
            ProductionSchemaVersionGuard guard = new ProductionSchemaVersionGuard(
                    dataSource(url), ProductionFlywayJobConfig.locations(conf), false);
            assertThrows(IllegalStateException.class, guard::verify,
                    "job未完了のDBではアプリが起動しないこと: " + entry.getKey());
        }
    }

    // ---------------------------------------------------------------------
    // 検証
    // ---------------------------------------------------------------------

    private void assertHistoryAppliedFromV1WithoutBaseline(Statement st, Map<String, String> conf)
            throws Exception {
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0"),
                "failed migrationが0件であること");
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'BASELINE'"),
                "空DBではbaseline-on-migrateがBASELINE行を作らないこと");
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE description = '"
                        + conf.get("flyway.baselineDescription").replace("'", "''") + "'"),
                "baseline-descriptionの行が存在しないこと");
        assertEquals(List.of(), rows(st, "SELECT CONCAT(COALESCE(version, '<R>'), ':', type) "
                        + "FROM flyway_schema_history WHERE type NOT IN ('SQL', 'JDBC') ORDER BY installed_rank"),
                "履歴はSQL/JDBC migrationだけで構成されること");
        assertEquals("1", queryString(st,
                        "SELECT version FROM flyway_schema_history ORDER BY installed_rank LIMIT 1"),
                "最初の履歴行がV1であること（V9 baselineからの開始ではない）");

        // baseline-version以下（V1〜V9）がSQLとして実際に適用されていること
        MigrationVersion baseline = MigrationVersion.fromVersion(conf.get("flyway.baselineVersion"));
        Set<String> expectedUpToBaseline = new TreeSet<>();
        for (String version : versionedSqlVersionsOnClasspath()) {
            if (MigrationVersion.fromVersion(version).compareTo(baseline) <= 0) {
                expectedUpToBaseline.add(version);
            }
        }
        assertFalse(expectedUpToBaseline.isEmpty(), "baseline-version以下のmigrationが解決できること");
        Set<String> appliedUpToBaseline = new TreeSet<>();
        for (String row : rows(st, "SELECT version FROM flyway_schema_history "
                + "WHERE type = 'SQL' AND success = 1 AND version IS NOT NULL")) {
            if (MigrationVersion.fromVersion(row).compareTo(baseline) <= 0) {
                appliedUpToBaseline.add(row);
            }
        }
        assertEquals(expectedUpToBaseline, appliedUpToBaseline,
                "V1〜V" + baseline.getVersion() + " が飛ばされずに適用されていること");

        // classpathのversioned SQLが過不足なく1回ずつ適用されていること（Java migrationはJDBC行）
        Set<String> appliedSql = new TreeSet<>(rows(st, "SELECT version FROM flyway_schema_history "
                + "WHERE type = 'SQL' AND version IS NOT NULL"));
        assertEquals(versionedSqlVersionsOnClasspath(), appliedSql,
                "db/migration のversioned SQLが全件適用されていること（db/migration-prodはversionedを持たない）");
        assertEquals(List.of("74.3"), rows(st, "SELECT version FROM flyway_schema_history "
                        + "WHERE type = 'JDBC' ORDER BY installed_rank"),
                "Java migration（db.migration.V74_3）がclasspath locationから解決・適用されること");
        assertEquals(scalar(st, "SELECT COUNT(DISTINCT version) FROM flyway_schema_history WHERE version IS NOT NULL"),
                scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL"),
                "同一versionの履歴行が重複しないこと");
    }

    private void assertAllResolvedMigrationsSucceeded(Flyway flyway) {
        List<String> notSucceeded = new ArrayList<>();
        for (MigrationInfo info : flyway.info().all()) {
            if (info.getState() != MigrationState.SUCCESS) {
                notSucceeded.add(info.getScript() + "=" + info.getState());
            }
        }
        assertEquals(List.of(), notSucceeded,
                "解決済みmigrationは全てSUCCESS（PENDING/FAILED/IGNORED/OUT_OF_ORDER/FUTURE等が無い）");
        assertEquals(0, flyway.info().pending().length, "pending migrationが無いこと");
    }

    private void assertLatestVersion(Statement st, Flyway flyway) throws Exception {
        MigrationInfo current = flyway.info().current();
        assertTrue(current != null && current.getVersion() != null, "current versionが解決できること");
        assertEquals(LatestMigrationVersion.LATEST, current.getVersion().getVersion(),
                "Flywayのcurrent versionがLatestMigrationVersion.LATESTと一致すること");

        MigrationVersion max = null;
        for (String version : rows(st, "SELECT version FROM flyway_schema_history "
                + "WHERE version IS NOT NULL AND success = 1")) {
            MigrationVersion candidate = MigrationVersion.fromVersion(version);
            if (max == null || candidate.compareTo(max) > 0) {
                max = candidate;
            }
        }
        assertTrue(max != null, "成功済みversioned migrationが存在すること");
        assertEquals(LatestMigrationVersion.LATEST, max.getVersion(),
                "履歴の最大version（数値比較）がLatestMigrationVersion.LATESTと一致すること");
    }

    private void assertRepeatablesAppliedAfterVersioned(Statement st) throws Exception {
        Set<String> expectedRepeatables = new TreeSet<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (String location : List.of("classpath*:db/migration/R__*.sql", "classpath*:db/migration-prod/R__*.sql")) {
            for (Resource resource : resolver.getResources(location)) {
                expectedRepeatables.add(resource.getFilename());
            }
        }
        assertTrue(expectedRepeatables.contains(ADMIN_PASSWORD_SCRIPT),
                "db/migration-prod に " + ADMIN_PASSWORD_SCRIPT + " が存在すること");
        assertTrue(expectedRepeatables.contains(SAMPLE_DATA_SCRIPT),
                "db/migration-prod に " + SAMPLE_DATA_SCRIPT + " が存在すること");
        assertEquals(0, resolver.getResources("classpath*:" + PROD_LOCATION.substring("classpath:".length())
                        + "/**/V*").length,
                "db/migration-prod はversioned migrationを持たない（db/migrationとversion空間を共有しない）");

        assertEquals(expectedRepeatables, new TreeSet<>(rows(st, "SELECT script FROM flyway_schema_history "
                        + "WHERE version IS NULL AND success = 1")),
                "全repeatable migrationが1回ずつ成功していること");
        long maxVersionedRank = scalar(st, "SELECT MAX(installed_rank) FROM flyway_schema_history "
                + "WHERE version IS NOT NULL");
        for (String script : List.of(ADMIN_PASSWORD_SCRIPT, SAMPLE_DATA_SCRIPT)) {
            long rank = scalar(st, "SELECT installed_rank FROM flyway_schema_history WHERE script = '" + script + "'");
            assertTrue(rank > maxVersionedRank, script + " はversioned migrationの後に適用されること");
        }
    }

    /** 配備決定-3: 注入hash・公開パスワード無し・初回変更要求。 */
    private void assertInitialAdmin(Statement st, String initialPassword, String initialHash) throws Exception {
        assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM sys_user"),
                "fresh installのaccountはV2のadmin 1件だけであること");
        String hash = adminPassword(st);
        assertEquals(initialHash, hash, "adminのpasswordが配備時に注入したhashそのものであること");
        assertTrue(BCRYPT.matcher(hash).matches(), "adminのpasswordがBCrypt形式であること");
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertTrue(encoder.matches(initialPassword, hash), "注入hashは配備時に生成した初期パスワードと照合できること");
        assertFalse(encoder.matches("admin123", hash), "V2の公開初期パスワード admin123 では認証できないこと");
        assertNotEquals("admin123", hash, "平文の初期passwordが残っていないこと");
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM sys_user WHERE password = '" + PUBLISHED_ADMIN123_HASH + "'"),
                "旧scriptが書いていた admin123 の公開hashがどのaccountにも無いこと");
        try (ResultSet rs = st.executeQuery("SELECT password_change_required, failed_count, locked_until, "
                + "role, status FROM sys_user WHERE username = 'admin'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("password_change_required"), "初回ログイン時のパスワード変更が要求されること");
            assertEquals(0, rs.getInt("failed_count"));
            assertNull(rs.getTimestamp("locked_until"));
            assertEquals("管理者", rs.getString("role"));
            assertEquals(1, rs.getInt("status"));
        }
    }

    /**
     * 配備決定-1: V2 sample（顧客3・案件3・要員3・提案2・契約1）と、後続migrationが派生させた行が残らない。
     * 派生元は {@code R__remove_v2_sample_data.sql} の冒頭に列挙している。
     */
    private void assertSampleDataAndDerivedRowsRemoved(Statement st) throws Exception {
        for (String table : SAMPLE_TABLES) {
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM " + table), "V2 sampleが残っていないこと: " + table);
        }
        for (String table : List.of(
                "t_customer_contact",              // V73 / V74_1 / R__crm（顧客担当者名の複製）
                "t_engineer_accounting_history",   // V61
                "nf02_nf03_ownership_repair_queue", // V169 / V177 / V178（従来は10行）
                "t_legal_entity_backfill_audit")) { // V158
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM " + table),
                    "V2 sampleから派生した行が残っていないこと: " + table);
        }
        assertEquals(List.of("0"), rows(st, "SELECT CAST(contract_id AS CHAR) FROM t_contract_acceptance_backfill "
                        + "ORDER BY contract_id"),
                "V80の完了marker（sentinel 0）だけが残り、sample契約の行は消えていること");
        // V60の移行組織はV2 sampleの有無と無関係に作られるため残す（要員の所属は要員と一緒に消える）
        assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM m_organization_unit "
                + "WHERE code = 'LEGACY' AND deleted_flag = 0"));
        assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE script = '"
                + SAMPLE_DATA_SCRIPT + "' AND success = 1"));
    }

    /**
     * 将来のmigrationが新しい派生先を作っても気付けるよう、削除後にsampleを指す行が無いことを列名から機械的に確かめる。
     * sample 5表は空なので、これらの列に値を持つ行は全て削除漏れである（V80のsentinel 0だけを除く）。
     */
    private void assertNoSampleReferenceRemains(Statement st) throws Exception {
        List<String> leftovers = new ArrayList<>();
        for (String column : rows(st, "SELECT CONCAT(c.table_name, '.', c.column_name) "
                + "FROM information_schema.columns c JOIN information_schema.tables t "
                + "ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE' "
                + "WHERE c.table_schema = DATABASE() "
                + "AND c.column_name IN ('customer_id', 'engineer_id', 'project_id', 'contract_id', 'proposal_id') "
                + "ORDER BY c.table_name, c.column_name")) {
            String table = column.substring(0, column.indexOf('.'));
            String name = column.substring(column.indexOf('.') + 1);
            String sentinel = "t_contract_acceptance_backfill".equals(table) ? " AND `" + name + "` <> 0" : "";
            long count = scalar(st, "SELECT COUNT(*) FROM `" + table + "` WHERE `" + name + "` IS NOT NULL" + sentinel);
            if (count > 0) {
                leftovers.add(column + "=" + count);
            }
        }
        for (String table : rows(st, "SELECT c1.table_name FROM information_schema.columns c1 "
                + "JOIN information_schema.columns c2 ON c2.table_schema = c1.table_schema "
                + "AND c2.table_name = c1.table_name AND c2.column_name = 'entity_id' "
                + "JOIN information_schema.tables t ON t.table_schema = c1.table_schema "
                + "AND t.table_name = c1.table_name AND t.table_type = 'BASE TABLE' "
                + "WHERE c1.table_schema = DATABASE() AND c1.column_name = 'entity_type' ORDER BY c1.table_name")) {
            long count = scalar(st, "SELECT COUNT(*) FROM `" + table + "` WHERE entity_type IN "
                    + "('CUSTOMER', 'ENGINEER', 'PROJECT', 'CONTRACT', 'PROPOSAL')");
            if (count > 0) {
                leftovers.add(table + ".entity_type=" + count);
            }
        }
        assertEquals(List.of(), leftovers,
                "削除したsampleを指す行が残っています。派生先をR__remove_v2_sample_data.sqlへ追加してください");
    }

    /**
     * 修復queue・台帳類の初期内容。V2 sampleの削除後は、空DBへのfresh installで作られる修復queue・台帳は全て空である
     * （従来は {@code nf02_nf03_ownership_repair_queue} にV2 sample由来の10行があった）。
     */
    private void assertInitialRepairAndLedgerTables(Statement st) throws Exception {
        for (String table : List.of(
                "t_nf09_scope_repair_queue",
                "nf02_nf03_ownership_repair_queue",
                "t_monthly_closing",
                "t_oidc_binding_review_inventory",
                "t_lifecycle_case_sequence",
                "m_report_schedule",
                "t_digital_invoice",
                "t_digital_invoice_event",
                "t_peppol_participant",
                "t_external_account_reference",
                "t_import_source_system",
                "t_import_mapping",
                "t_import_job",
                "t_import_validation_run",
                "t_import_validation_row",
                "t_import_identity_registry")) {
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM " + table),
                    "空DBへのfresh installでは0件であること: " + table);
        }
        assertEquals("[]", queryString(st,
                        "SELECT config_value FROM m_system_config WHERE config_key = 'closing.confirmed-months'"),
                "V35の旧月次締めJSONは空配列のままであること");
        assertTrue(queryString(st, "SELECT description FROM m_system_config "
                        + "WHERE config_key = 'closing.confirmed-months'").startsWith("【移行済・参照禁止】"),
                "V181:50-52で旧keyが参照禁止として注記されること");
    }

    // ---------------------------------------------------------------------
    // 本番jobと同じFlyway設定
    // ---------------------------------------------------------------------

    /** job設定（ops/flyway/flyway-prod.conf）に、環境変数で渡す値（接続情報・初期管理者hash）だけを足す。 */
    private static FluentConfiguration job(String url, String adminHash) {
        return ProductionFlywayJobConfig.configure(url, MYSQL.getUsername(), MYSQL.getPassword(), adminHash);
    }

    /** 本testで使う乱数の初期パスワード（PasswordPolicyValidatorの英字・数字を含む）。 */
    private static String randomPassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return "Init1" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---------------------------------------------------------------------
    // JDBC helper
    // ---------------------------------------------------------------------

    /**
     * containerのDBへ、application.yml の既定DB_URLと同じ接続parameterを付けたURL。
     * 本番のjobでは FLYWAY_URL に同じparameterを付ける（deploy-runbook.md）。
     */
    private static String jdbcUrl(String database) {
        String base = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        String query = datasourceUrlQuery();
        if (query.isEmpty()) {
            return base;
        }
        return base + (base.contains("?") ? "&" : "?") + query;
    }

    private static String datasourceUrlQuery() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties properties = factory.getObject();
        assertTrue(properties != null, "application.yml を読み込めること");
        String datasourceUrl = String.valueOf(properties.get("spring.datasource.url"));
        int query = datasourceUrl.indexOf('?');
        return query < 0 ? "" : datasourceUrl.substring(query + 1).replaceAll("}$", "");
    }

    /** 同じcontainerへ空のDBを作り、そのURLを返す。 */
    private static String createDatabase(String database) throws SQLException {
        try (Connection root = connect(jdbcUrl(MYSQL.getDatabaseName())); Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + database + "`");
            st.execute("CREATE DATABASE `" + database + "`");
        }
        return jdbcUrl(database);
    }

    private static DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static String causeChainMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
            if (current.getCause() == current) {
                break;
            }
        }
        return messages.toString();
    }

    private static Set<String> versionedSqlVersionsOnClasspath() throws Exception {
        Set<String> versions = new TreeSet<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql")) {
            Matcher matcher = VERSIONED_SQL.matcher(String.valueOf(resource.getFilename()));
            if (matcher.matches()) {
                versions.add(MigrationVersion.fromVersion(matcher.group(1).replace('_', '.')).getVersion());
            }
        }
        return versions;
    }

    private static List<String> history(Statement st) throws Exception {
        return rows(st, "SELECT CONCAT_WS('|', installed_rank, COALESCE(version, '<R>'), description, type, "
                + "script, COALESCE(checksum, '<null>'), success) FROM flyway_schema_history "
                + "ORDER BY installed_rank");
    }

    /** 列・索引・制約・CHECK・routine・triggerの定義を順序付きで列挙する（統計値は含めない）。 */
    private static List<String> schemaFingerprint(Statement st) throws Exception {
        List<String> fingerprint = new ArrayList<>();
        fingerprint.addAll(prefixed("table", rows(st, "SELECT CONCAT_WS('|', table_name, table_type) "
                + "FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name")));
        fingerprint.addAll(prefixed("column", rows(st, "SELECT CONCAT_WS('|', table_name, column_name, "
                + "ordinal_position, column_type, is_nullable, COALESCE(column_default, '<null>'), extra, "
                + "COALESCE(character_set_name, ''), COALESCE(collation_name, ''), "
                + "COALESCE(generation_expression, '')) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() ORDER BY table_name, ordinal_position")));
        fingerprint.addAll(prefixed("index", rows(st, "SELECT CONCAT_WS('|', table_name, index_name, "
                + "seq_in_index, COALESCE(column_name, ''), non_unique, COALESCE(sub_part, -1), "
                + "COALESCE(expression, '')) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() ORDER BY table_name, index_name, seq_in_index")));
        fingerprint.addAll(prefixed("constraint", rows(st, "SELECT CONCAT_WS('|', table_name, constraint_name, "
                + "constraint_type) FROM information_schema.table_constraints "
                + "WHERE table_schema = DATABASE() ORDER BY table_name, constraint_name, constraint_type")));
        fingerprint.addAll(prefixed("check", rows(st, "SELECT CONCAT_WS('|', constraint_name, check_clause) "
                + "FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() "
                + "ORDER BY constraint_name")));
        fingerprint.addAll(prefixed("routine", rows(st, "SELECT CONCAT_WS('|', routine_name, routine_type) "
                + "FROM information_schema.routines WHERE routine_schema = DATABASE() ORDER BY routine_name")));
        fingerprint.addAll(prefixed("trigger", rows(st, "SELECT CONCAT_WS('|', trigger_name, "
                + "event_object_table, action_timing, event_manipulation) FROM information_schema.triggers "
                + "WHERE trigger_schema = DATABASE() ORDER BY trigger_name")));
        assertFalse(fingerprint.isEmpty(), "schema fingerprintを取得できること");
        return fingerprint;
    }

    private static List<String> prefixed(String kind, List<String> values) {
        return values.stream().map(value -> kind + ":" + value).toList();
    }

    private static String adminPassword(Statement st) throws Exception {
        return queryString(st, "SELECT password FROM sys_user WHERE username = 'admin'");
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "結果行が必要: " + sql);
            return rs.getLong(1);
        }
    }

    private static String queryString(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "結果行が必要: " + sql);
            return rs.getString(1);
        }
    }

    private static List<String> rows(Statement st, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            assertEquals(1, meta.getColumnCount(), "単一列のqueryであること: " + sql);
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
