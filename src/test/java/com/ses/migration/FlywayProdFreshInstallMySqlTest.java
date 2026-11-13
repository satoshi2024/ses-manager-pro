package com.ses.migration;

import com.ses.config.LegacyDatabaseFlywayCallback;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本番初回導入（空DBへのfresh install）を、prodプロファイルと同じFlyway設定で実MySQL上に再現する。
 *
 * <p>前提（2026-11-13 ユーザー決定）: 本番DBは未構築で、初回リリースは空DBへの新規導入である。
 * 配備は全停止方式で、migrationは専用のFlyway jobが単独で実行し、アプリinstanceは自動実行しない。
 * 本testはそのjobが満たすべき「prod相当のFlyway設定」を {@code application.yml} と
 * {@code application-prod.yml} から読み取り、Spring Bootの自動構成が登録するcallback
 * （{@link LegacyDatabaseFlywayCallback}）も含めて空DBへ適用する。
 *
 * <p>既存testとの役割分担（重複させない）:
 * <ul>
 *   <li>{@link FlywayMigrationSmokeTest}: {@code classpath:db/migration} だけ・baseline設定なしで空DBへ
 *       通し適用し、個別のschema形状を検証する。本testはschema形状を再検証しない。</li>
 *   <li>{@link FlywayRepairRunbookTest}: 旧prod履歴（V10 checksum不一致）からのrepair経路。</li>
 *   <li>本test: prod相当の locations / out-of-order / baseline-on-migrate / callback で、
 *       (1) 全migrationがsuccess=1・failed 0・pending 0、(2) 最新版 = {@link LatestMigrationVersion#LATEST}、
 *       (3) {@code R__update_admin_password_bcrypt} 適用によるadminのBCrypt化、
 *       (4) 空DBではbaselineされずV1から適用されること、(5) 再migrateがno-opでvalidateが通ること、
 *       (6) 修復queue等の初期内容、を検証する。非空DBではbaselineが作動することも対照として示す。</li>
 * </ul>
 *
 * <p>本番secretは一切使わない。DB接続はTestcontainersの使い捨てrootだけを使い、adminの照合に使う
 * {@code admin123} は {@code V2__init_master_data.sql} が投入する公開済みの初期値である。
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

    /** 本testが解釈できる {@code spring.flyway.*} のkey。ymlへ新しいkeyが増えたら本testを更新させる。 */
    private static final Set<String> KNOWN_FLYWAY_KEYS = Set.of(
            "spring.flyway.enabled",
            "spring.flyway.locations",
            "spring.flyway.out-of-order",
            "spring.flyway.baseline-on-migrate",
            "spring.flyway.baseline-version",
            "spring.flyway.baseline-description");

    private static final String PROD_LOCATION = "classpath:db/migration-prod";
    private static final String ADMIN_PASSWORD_SCRIPT = "R__update_admin_password_bcrypt.sql";
    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$10\\$[./A-Za-z0-9]{53}$");
    private static final Pattern VERSIONED_SQL = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");
    private static final String NON_EMPTY_DATABASE = "ses_prod_fresh_nonempty";

    @Container
    @SuppressWarnings("resource") // ライフサイクルは Testcontainers Extension が管理する。
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("ses_prod_fresh_install")
            .withUsername("root")
            .withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withStartupAttempts(3);

    @Test
    void prod相当設定で空DBへV1からLATESTまで適用しbaselineせず再実行はno_opでvalidateが通る() throws Exception {
        ProdFlywaySettings settings = ProdFlywaySettings.load();
        String url = jdbcUrl(MYSQL.getDatabaseName(), settings);

        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE()"),
                    "fresh installの前提: 対象schemaに表が1件も無いこと");
        }

        Flyway flyway = prodEquivalent(url, settings).load();
        int pendingBefore = flyway.info().pending().length;
        MigrateResult first = flyway.migrate();

        assertTrue(first.success, "prod相当設定のmigrateが成功すること");
        assertEquals(pendingBefore, first.migrationsExecuted,
                "空DBでは解決済みの全migration（versioned + repeatable）が1回ずつ実行されること");

        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            assertHistoryAppliedFromV1WithoutBaseline(st, settings);
            assertAllResolvedMigrationsSucceeded(flyway);
            assertLatestVersion(st, flyway);
            assertRepeatablesAppliedAfterVersioned(st);
            assertAdminPasswordIsBcrypt(st);
            assertInitialRepairAndLedgerTables(st);
        }

        List<String> historyBefore;
        List<String> schemaBefore;
        String adminHashBefore;
        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            historyBefore = history(st);
            schemaBefore = schemaFingerprint(st);
            adminHashBefore = adminPassword(st);
        }

        // 次回のjob実行（または誤ってFlyway有効で起動したinstance）を、新しいFlyway instanceで再現する。
        // LegacyDatabaseFlywayCallbackはt_bp_payment存在時に補償DDLを試みるため、schema全体の不変も確認する。
        Flyway rerun = prodEquivalent(url, settings).load();
        MigrateResult second = rerun.migrate();
        assertTrue(second.success, "2回目のmigrateも成功扱いであること");
        assertEquals(0, second.migrationsExecuted, "2回目のmigrateは1件も実行しない（no-op）");
        assertEquals(0, rerun.info().pending().length, "2回目のmigrate後もpendingが無いこと");

        ValidateResult validation = rerun.validateWithResult();
        assertTrue(validation.validationSuccessful,
                "prod相当設定のvalidateが成功すること: " + validation.getAllErrorMessages());
        rerun.validate();

        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            assertEquals(historyBefore, history(st),
                    "2回目のmigrateでflyway_schema_historyが1行も変化しない（repeatableも再適用されない）");
            assertEquals(schemaBefore, schemaFingerprint(st),
                    "2回目のmigrate（BEFORE_MIGRATE callbackを含む）でschemaが変化しない");
            assertEquals(adminHashBefore, adminPassword(st),
                    "2回目のmigrateでadminのBCrypt hashが書き換わらない");
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
        ProdFlywaySettings settings = ProdFlywaySettings.load();
        try (Connection root = connect(jdbcUrl(MYSQL.getDatabaseName(), settings));
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + NON_EMPTY_DATABASE + "`");
            st.execute("CREATE DATABASE `" + NON_EMPTY_DATABASE + "`");
        }
        String url = jdbcUrl(NON_EMPTY_DATABASE, settings);
        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE w1b3_stray_object (id INT PRIMARY KEY)");
        }

        Flyway flyway = prodEquivalent(url, settings).load();
        assertThrows(FlywayException.class, flyway::migrate,
                "V1〜V9を飛ばしたschemaではV10のt_bp_payment参照で失敗すること");

        try (Connection conn = connect(url); Statement st = conn.createStatement()) {
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE type = 'BASELINE' AND version = '" + settings.baselineVersion() + "'"),
                    "非空schemaではbaseline-versionでBASELINE行が作られること");
            assertEquals(settings.baselineDescription(), queryString(st,
                    "SELECT description FROM flyway_schema_history WHERE type = 'BASELINE'"));
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1'"),
                    "baseline後はV1が適用されないこと");
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version = '10' AND success = 0"),
                    "最初のpending（V10）がfailed行として残ること");
        }
    }

    // ---------------------------------------------------------------------
    // 検証
    // ---------------------------------------------------------------------

    private void assertHistoryAppliedFromV1WithoutBaseline(Statement st, ProdFlywaySettings settings)
            throws Exception {
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0"),
                "failed migrationが0件であること");
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'BASELINE'"),
                "空DBではbaseline-on-migrateがBASELINE行を作らないこと");
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE description = '"
                        + settings.baselineDescription().replace("'", "''") + "'"),
                "baseline-descriptionの行が存在しないこと");
        assertEquals(List.of(), rows(st, "SELECT CONCAT(COALESCE(version, '<R>'), ':', type) "
                        + "FROM flyway_schema_history WHERE type NOT IN ('SQL', 'JDBC') ORDER BY installed_rank"),
                "履歴はSQL/JDBC migrationだけで構成されること");
        assertEquals("1", queryString(st,
                        "SELECT version FROM flyway_schema_history ORDER BY installed_rank LIMIT 1"),
                "最初の履歴行がV1であること（V9 baselineからの開始ではない）");

        // baseline-version以下（V1〜V9）がSQLとして実際に適用されていること
        MigrationVersion baseline = MigrationVersion.fromVersion(settings.baselineVersion());
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
                "V1〜V" + settings.baselineVersion() + " が飛ばされずに適用されていること");

        // classpathのversioned SQLが過不足なく1回ずつ適用されていること（Java migrationはJDBC行）
        Set<String> appliedSql = new TreeSet<>(rows(st, "SELECT version FROM flyway_schema_history "
                + "WHERE type = 'SQL' AND version IS NOT NULL"));
        assertEquals(versionedSqlVersionsOnClasspath(), appliedSql,
                "db/migration のversioned SQLが全件適用されていること");
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
        for (String location : List.of("classpath:db/migration/R__*.sql", PROD_LOCATION + "/R__*.sql")) {
            for (Resource resource : resolver.getResources(location)) {
                expectedRepeatables.add(resource.getFilename());
            }
        }
        assertTrue(expectedRepeatables.contains(ADMIN_PASSWORD_SCRIPT),
                "db/migration-prod に " + ADMIN_PASSWORD_SCRIPT + " が存在すること");
        assertEquals(0, resolver.getResources(PROD_LOCATION + "/V*.sql").length,
                "db/migration-prod はversioned migrationを持たない（version衝突を作らない）");

        assertEquals(expectedRepeatables, new TreeSet<>(rows(st, "SELECT script FROM flyway_schema_history "
                        + "WHERE version IS NULL AND success = 1")),
                "全repeatable migrationが1回ずつ成功していること");
        long maxVersionedRank = scalar(st, "SELECT MAX(installed_rank) FROM flyway_schema_history "
                + "WHERE version IS NOT NULL");
        long adminScriptRank = scalar(st, "SELECT installed_rank FROM flyway_schema_history "
                + "WHERE script = '" + ADMIN_PASSWORD_SCRIPT + "'");
        assertTrue(adminScriptRank > maxVersionedRank,
                ADMIN_PASSWORD_SCRIPT + " はversioned migrationの後に適用されること");
    }

    private void assertAdminPasswordIsBcrypt(Statement st) throws Exception {
        assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM sys_user"),
                "fresh installのaccountはV2のadmin 1件だけであること");
        String hash = adminPassword(st);
        assertTrue(BCRYPT.matcher(hash).matches(), "adminのpasswordがBCrypt形式（$2a$10$...）であること");
        assertFalse("admin123".equals(hash), "平文の初期passwordが残っていないこと");
        assertTrue(new BCryptPasswordEncoder().matches("admin123", hash),
                "BCrypt hashがV2の公開初期値と照合できること（本番導入直後に変更が必要な値）");
        try (ResultSet rs = st.executeQuery(
                "SELECT failed_count, locked_until FROM sys_user WHERE username = 'admin'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("failed_count"));
            assertNull(rs.getTimestamp("locked_until"));
        }
    }

    /**
     * 修復queue・台帳類の初期内容。
     *
     * <p>{@code t_nf09_scope_repair_queue}（V182:20-36）は、回填対象の電子インボイス・Peppol参加者・
     * 外部アカウント参照が空DBには無いため0件である。一方 {@code nf02_nf03_ownership_repair_queue} は
     * 空ではない: {@code V2__init_master_data.sql}（195-232行）がsample顧客3件・案件3件・要員3件・
     * 契約1件を投入し、それらはtenant証拠を持たないため次の規則でqueueへ入る。
     * <ul>
     *   <li>顧客: V169:121-124（TENANT_UNRESOLVED）、V178:63-83でreasonは同値のまま更新</li>
     *   <li>要員: V169:125-128（TENANT_UNRESOLVED）、V178:71-83で TENANT_REFERENCE_MISMATCH へ更新</li>
     *   <li>案件: V178:85-101（顧客tenantがNULL → TENANT_REFERENCE_MISMATCH）</li>
     *   <li>契約: V177:20-24（TENANT_UNRESOLVED）、V178:11-60で TENANT_REFERENCE_MISMATCH へ更新</li>
     * </ul>
     * sys_userはV163:52-53でtenant 'default' を持ち、t_user_organizationはV60:158-169が管理者を
     * 対象外にするため、SYS_USER/USER_ORGANIZATION行は生じない。V176:88-104の取込・候補者行も
     * 空DBには存在しない。
     */
    private void assertInitialRepairAndLedgerTables(Statement st) throws Exception {
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM t_nf09_scope_repair_queue"),
                "V182の修復queueは空DBでは0件であること");

        Map<String, Long> sampleCounts = new LinkedHashMap<>();
        sampleCounts.put("m_customer", 3L);
        sampleCounts.put("t_project", 3L);
        sampleCounts.put("t_engineer", 3L);
        sampleCounts.put("t_contract", 1L);
        sampleCounts.put("t_proposal", 2L);
        for (Map.Entry<String, Long> entry : sampleCounts.entrySet()) {
            assertEquals(entry.getValue().longValue(), scalar(st, "SELECT COUNT(*) FROM " + entry.getKey()),
                    "V2のsample行だけが存在すること: " + entry.getKey());
        }
        for (String table : List.of("m_customer", "t_engineer", "t_contract")) {
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM " + table + " WHERE tenant_id IS NOT NULL"),
                    "V2のsample行はtenant証拠が無くNULLのままであること: " + table);
        }

        Set<String> expectedQueue = new TreeSet<>();
        addQueueExpectations(st, expectedQueue, "m_customer", "CUSTOMER", "TENANT_UNRESOLVED");
        addQueueExpectations(st, expectedQueue, "t_engineer", "ENGINEER", "TENANT_REFERENCE_MISMATCH");
        addQueueExpectations(st, expectedQueue, "t_project", "PROJECT", "TENANT_REFERENCE_MISMATCH");
        addQueueExpectations(st, expectedQueue, "t_contract", "CONTRACT", "TENANT_REFERENCE_MISMATCH");
        assertEquals(10, expectedQueue.size());
        assertEquals(expectedQueue, new TreeSet<>(rows(st,
                        "SELECT CONCAT(entity_type, ':', entity_id, ':', reason, ':', status) "
                                + "FROM nf02_nf03_ownership_repair_queue")),
                "NF02/NF03修復queueはV2 sample行の隔離だけを含むこと");

        for (String table : List.of(
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

    private void addQueueExpectations(Statement st, Set<String> expected, String table,
                                      String entityType, String reason) throws Exception {
        for (String id : rows(st, "SELECT id FROM " + table + " ORDER BY id")) {
            expected.add(entityType + ":" + id + ":" + reason + ":PENDING");
        }
    }

    // ---------------------------------------------------------------------
    // prod相当のFlyway設定
    // ---------------------------------------------------------------------

    private FluentConfiguration prodEquivalent(String url, ProdFlywaySettings settings) {
        // Spring Boot の FlywayAutoConfiguration は Callback bean を全て登録する。
        // LegacyDatabaseFlywayCallback は @Component で profile条件を持たないため、prodでも登録される。
        return Flyway.configure()
                .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations(settings.locations().toArray(String[]::new))
                .outOfOrder(settings.outOfOrder())
                .baselineOnMigrate(settings.baselineOnMigrate())
                .baselineVersion(settings.baselineVersion())
                .baselineDescription(settings.baselineDescription())
                .callbacks(new LegacyDatabaseFlywayCallback());
    }

    /** {@code application.yml} に {@code application-prod.yml} を重ねた {@code spring.flyway.*}。 */
    private record ProdFlywaySettings(List<String> locations, boolean outOfOrder, boolean baselineOnMigrate,
                                      String baselineVersion, String baselineDescription,
                                      String datasourceUrlQuery) {

        static ProdFlywaySettings load() {
            Properties merged = new Properties();
            merged.putAll(yaml("application.yml"));
            merged.putAll(yaml("application-prod.yml"));

            Set<String> unknown = new TreeSet<>();
            for (Object key : merged.keySet()) {
                String name = String.valueOf(key);
                if (name.startsWith("spring.flyway.") && !KNOWN_FLYWAY_KEYS.contains(name)) {
                    unknown.add(name);
                }
            }
            assertEquals(Set.of(), unknown,
                    "本testが再現していないspring.flyway設定がymlにあります。prod相当設定へ反映してください");

            List<String> locations = Arrays.stream(required(merged, "spring.flyway.locations").split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
            assertEquals(List.of("classpath:db/migration", PROD_LOCATION), locations,
                    "prodのFlyway locationsはdb/migrationとdb/migration-prodであること");

            String datasourceUrl = required(merged, "spring.datasource.url");
            int query = datasourceUrl.indexOf('?');
            String datasourceUrlQuery = query < 0 ? "" : datasourceUrl.substring(query + 1).replaceAll("}$", "");

            return new ProdFlywaySettings(
                    locations,
                    Boolean.parseBoolean(required(merged, "spring.flyway.out-of-order")),
                    Boolean.parseBoolean(required(merged, "spring.flyway.baseline-on-migrate")),
                    required(merged, "spring.flyway.baseline-version"),
                    required(merged, "spring.flyway.baseline-description"),
                    datasourceUrlQuery);
        }

        private static Properties yaml(String name) {
            YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
            factory.setResources(new ClassPathResource(name));
            Properties properties = factory.getObject();
            assertTrue(properties != null && !properties.isEmpty(), name + " を読み込めること");
            return properties;
        }

        private static String required(Properties properties, String key) {
            Object value = properties.get(key);
            assertTrue(value != null && !String.valueOf(value).isBlank(), key + " がymlに設定されていること");
            String text = String.valueOf(value).trim();
            if (key.startsWith("spring.flyway.")) {
                assertFalse(text.contains("${"),
                        key + " は環境変数placeholderを持たない前提（持つ場合は本testの再現範囲を見直す）");
            }
            return text;
        }
    }

    // ---------------------------------------------------------------------
    // JDBC helper
    // ---------------------------------------------------------------------

    /** containerのDBへ、application.yml の既定DB_URLと同じ接続parameterを付けたURL。 */
    private static String jdbcUrl(String database, ProdFlywaySettings settings) {
        String base = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        if (settings.datasourceUrlQuery().isEmpty()) {
            return base;
        }
        return base + (base.contains("?") ? "&" : "?") + settings.datasourceUrlQuery();
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static Set<String> versionedSqlVersionsOnClasspath() throws Exception {
        Set<String> versions = new TreeSet<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql")) {
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
