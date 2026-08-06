package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V79.1専用の実MySQL migration gate。
 *
 * <p>既存のV73 partial/repair smokeをV79.1の証拠へ読み替えないため、
 * 旧V79.1（organization FKがCASCADE/SET NULL）を履歴checksumとschemaの両方で再現し、
 * validate失敗、repair単独の危険性、forward DDL後のallowlist repairを個別に検証する。
 * 同じcontainer内のcleanは本番rollbackではなく、破棄可能な検証DBのrollback再適用rehearsalである。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayV79_1RepairSmokeTest {

    private static final String V79_1_VERSION = "79.1";
    private static final String V79_1_SCRIPT = "V79_1__approval_route_decision_sources.sql";

    /** 74329e9に存在したCASCADE/SET NULL版V79.1のFlyway CRC32 checksum。 */
    private static final int OLD_V79_1_CHECKSUM = (int) 2_211_080_825L;

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v79_1_repair")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void V79_1専用にlegacy_checksum_validate失敗とforward_repair_partial_rollbackを実測する()
            throws Exception {
        // V79までのlegacy upgrade後に、V79.1の全assertを実MySQLで確認する。
        resetDatabase();
        applyLegacyV79ThenCurrent();

        // 旧checksum + 旧FK actionを再現し、repair単独ではschemaを直せないことを明示する。
        resetDatabase();
        installOldV79_1SchemaAndHistory();
        demonstrateRepairAloneIsUnsafe();

        // 同じ旧状態を再現し、forward DDL -> allowlist repair -> validateを実測する。
        resetDatabase();
        installOldV79_1SchemaAndHistory();
        Flyway forwardRepair = flyway();
        FlywayValidateException oldChecksumFailure = assertThrows(
                FlywayValidateException.class, forwardRepair::validate);
        assertValidationFailureMentionsV79_1(oldChecksumFailure);
        executeForwardRepairRunbook();
        assertReferentialAction("fk_approval_responsibility_org", "RESTRICT", "RESTRICT",
                "m_organization_unit", "id");
        repairOnlyAllowlistedV79_1Checksum(forwardRepair);
        forwardRepair.validate();
        assertV79_1Schema(forwardRepair);

        // V79.1の先行ALTERだけ適用された途中状態から、failed historyをrepairして再適用する。
        resetDatabase();
        applyPartialV79_1Fixture();

        // 適用済みV79.1を破棄可能DBでV79へ戻し、再forward適用できることを確認する。
        resetDatabase();
        Flyway latest = flyway();
        latest.migrate();
        latest.validate();
        assertV79_1Schema(latest);
        latest.clean();
        assertV79_1AbsentAfterClean();

        Flyway v79 = flywayTarget("79");
        v79.migrate();
        assertV79_1AbsentAfterV79();
        latest = flyway();
        latest.migrate();
        latest.validate();
        assertV79_1Schema(latest);
    }


    /** V79.1 runbookの各partial状態（DROP後 / FK追加後 / CHECK追加後）からの再実行を実MySQLで検証する。 */
    @Test
    void V79_1runbookは各partial状態から再実行でき最終schemaとV79_1限定repair_validateに収束する()
            throws Exception {
        for (String partial : List.of("AFTER_DROP", "AFTER_FK_ADD", "AFTER_CHECK_ADD")) {
            resetDatabase();
            installOldV79_1SchemaAndHistory();
            applyRunbookPartialState(partial);
            assertRunbookPartialState(partial);

            // 未適用のDDLだけをinformation_schema判定で実行し、どの状態からでも最終schemaへ収束する。
            executeForwardRepairRunbook();
            // 冪等性: 最終状態（中断点C）から再実行しても安全であること。
            executeForwardRepairRunbook();
            try (Connection connection = MYSQL.createConnection("");
                 Statement statement = connection.createStatement()) {
                assertReferentialAction(statement, "fk_approval_responsibility_org", "RESTRICT", "RESTRICT",
                        "m_organization_unit", "id");
                assertCheckConstraintExists(statement, "chk_approval_responsibility_organization");
            }

            // V79.1限定allowlist repair -> validate -> 最終schema。
            Flyway repaired = flyway();
            FlywayValidateException oldChecksumFailure = assertThrows(
                    FlywayValidateException.class, repaired::validate);
            assertValidationFailureMentionsV79_1(oldChecksumFailure);
            repairOnlyAllowlistedV79_1Checksum(repaired);
            repaired.validate();
            assertV79_1Schema(repaired);
        }
    }

    /** 中断点（A）DROP後 /（B）FK追加後 /（C）CHECK追加後のpartial状態を再現する。 */
    private void applyRunbookPartialState(String partial) throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            switch (partial) {
                case "AFTER_DROP" -> statement.execute(
                        "ALTER TABLE t_approval_responsibility DROP FOREIGN KEY fk_approval_responsibility_org");
                case "AFTER_FK_ADD" -> {
                    statement.execute(
                            "ALTER TABLE t_approval_responsibility DROP FOREIGN KEY fk_approval_responsibility_org");
                    statement.execute("ALTER TABLE t_approval_responsibility ADD CONSTRAINT "
                            + "fk_approval_responsibility_org FOREIGN KEY (organization_id) "
                            + "REFERENCES m_organization_unit(id) ON UPDATE RESTRICT ON DELETE RESTRICT");
                }
                case "AFTER_CHECK_ADD" -> {
                    statement.execute(
                            "ALTER TABLE t_approval_responsibility DROP FOREIGN KEY fk_approval_responsibility_org");
                    statement.execute("ALTER TABLE t_approval_responsibility ADD CONSTRAINT "
                            + "fk_approval_responsibility_org FOREIGN KEY (organization_id) "
                            + "REFERENCES m_organization_unit(id) ON UPDATE RESTRICT ON DELETE RESTRICT");
                    statement.execute("ALTER TABLE t_approval_responsibility ADD CONSTRAINT "
                            + "chk_approval_responsibility_organization CHECK "
                            + "(responsibility_type = 'FINANCE_MANAGER' OR organization_id IS NOT NULL)");
                }
                default -> throw new IllegalArgumentException("unknown partial state: " + partial);
            }
        }
    }

    /** 各partial状態の前提（FK/CHECKの存在とaction）を確認する。 */
    private void assertRunbookPartialState(String partial) throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            boolean fkExists = hasReferentialConstraint(statement, "fk_approval_responsibility_org");
            boolean checkExists = hasCheckConstraint(statement, "chk_approval_responsibility_organization");
            switch (partial) {
                case "AFTER_DROP" -> {
                    assertFalse(fkExists, "AFTER_DROPはFKが存在しない状態であるはず");
                    assertFalse(checkExists, "AFTER_DROPはCHECKが存在しない状態であるはず");
                }
                case "AFTER_FK_ADD" -> {
                    assertTrue(fkExists, "AFTER_FK_ADDはFKが存在する状態であるはず");
                    assertReferentialAction(statement, "fk_approval_responsibility_org", "RESTRICT", "RESTRICT",
                            "m_organization_unit", "id");
                    assertFalse(checkExists, "AFTER_FK_ADDはCHECKが存在しない状態であるはず");
                }
                case "AFTER_CHECK_ADD" -> {
                    assertTrue(fkExists, "AFTER_CHECK_ADDはFKが存在する状態であるはず");
                    assertReferentialAction(statement, "fk_approval_responsibility_org", "RESTRICT", "RESTRICT",
                            "m_organization_unit", "id");
                    assertTrue(checkExists, "AFTER_CHECK_ADDはCHECKが存在する状態であるはず");
                }
                default -> throw new IllegalArgumentException("unknown partial state: " + partial);
            }
        }
    }

    private boolean hasReferentialConstraint(Statement statement, String constraint) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.REFERENTIAL_CONSTRAINTS rc "
                        + "WHERE rc.CONSTRAINT_SCHEMA=DATABASE() AND rc.TABLE_NAME='t_approval_responsibility' "
                        + "AND rc.CONSTRAINT_NAME='" + constraint + "'")) {
            return resultSet.next();
        }
    }

    private boolean hasCheckConstraint(Statement statement, String constraint) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() "
                        + "AND table_name='t_approval_responsibility' AND constraint_name='" + constraint
                        + "' AND constraint_type='CHECK'")) {
            return resultSet.next();
        }
    }

    private void applyLegacyV79ThenCurrent() throws Exception {
        Flyway v79 = flywayTarget("79");
        v79.migrate();
        assertV79_1AbsentAfterV79();

        Flyway latest = flyway();
        latest.migrate();
        latest.validate();
        assertV79_1Schema(latest);
    }

    /** 旧版のschema/actionと、旧版scriptのsuccess履歴を同時に作る。 */
    private void installOldV79_1SchemaAndHistory() throws Exception {
        Flyway v79 = flywayTarget("79");
        v79.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE m_approval_route ADD COLUMN applicant_role_condition VARCHAR(30) NULL "
                    + "COMMENT '申請者role条件(NULL=全role)' AFTER request_type");
            statement.execute("""
                    CREATE TABLE t_approval_responsibility (
                        id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
                        tenant_id           BIGINT NOT NULL DEFAULT 1,
                        responsibility_type VARCHAR(30) NOT NULL,
                        organization_id     BIGINT NULL,
                        user_id             BIGINT NOT NULL,
                        valid_from          DATE NOT NULL,
                        valid_to            DATE NULL,
                        active_flag         TINYINT NOT NULL DEFAULT 1,
                        created_by          BIGINT NULL,
                        created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        deleted_flag        TINYINT NOT NULL DEFAULT 0,
                        INDEX idx_approval_responsibility_lookup
                            (tenant_id, responsibility_type, organization_id, valid_from, valid_to),
                        INDEX idx_approval_responsibility_user (tenant_id, user_id),
                        CONSTRAINT fk_approval_responsibility_org FOREIGN KEY (organization_id)
                            REFERENCES m_organization_unit(id) ON UPDATE CASCADE ON DELETE SET NULL,
                        CONSTRAINT fk_approval_responsibility_user FOREIGN KEY (user_id)
                            REFERENCES sys_user(id) ON UPDATE CASCADE ON DELETE RESTRICT,
                        CONSTRAINT fk_approval_responsibility_created_by FOREIGN KEY (created_by)
                            REFERENCES sys_user(id) ON UPDATE CASCADE ON DELETE SET NULL,
                        CONSTRAINT chk_approval_responsibility_type
                            CHECK (responsibility_type IN ('ORGANIZATION_MANAGER', 'FINANCE_MANAGER')),
                        CONSTRAINT chk_approval_responsibility_period
                            CHECK (valid_to IS NULL OR valid_from <= valid_to)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    COMMENT='承認責任者assignment'
                    """);
            insertHistory(statement, V79_1_VERSION, "approval route decision sources", V79_1_SCRIPT,
                    OLD_V79_1_CHECKSUM, true);
            assertEquals(OLD_V79_1_CHECKSUM, historyChecksum(statement, V79_1_VERSION));
        }
        assertReferentialAction("fk_approval_responsibility_org", "CASCADE", "SET NULL",
                "m_organization_unit", "id");
    }

    private void demonstrateRepairAloneIsUnsafe() throws Exception {
        Flyway unsafeRepair = flyway();
        FlywayValidateException validationFailure = assertThrows(
                FlywayValidateException.class, unsafeRepair::validate);
        assertValidationFailureMentionsV79_1(validationFailure);

        // Flyway repairはchecksumだけを書き換え、既存FK actionは変更しない。
        unsafeRepair.repair();
        unsafeRepair.validate();
        assertReferentialAction("fk_approval_responsibility_org", "CASCADE", "SET NULL",
                "m_organization_unit", "id");
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertNotEquals(OLD_V79_1_CHECKSUM, historyChecksum(statement, V79_1_VERSION));
        }
    }

    private void repairOnlyAllowlistedV79_1Checksum(Flyway flyway) throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertOnlyFailedOrMismatchedEntryIsV79_1(statement, flyway);
            assertHistoryRow(statement, V79_1_VERSION, V79_1_SCRIPT, 1);
        }

        assertReferentialAction("fk_approval_responsibility_org", "RESTRICT", "RESTRICT",
                "m_organization_unit", "id");
        Map<String, Integer> checksumsBefore = historyChecksums();
        flyway.repair();
        Map<String, Integer> checksumsAfter = historyChecksums();
        checksumsBefore.forEach((version, checksum) -> {
            if (!V79_1_VERSION.equals(version)) {
                assertEquals(checksum, checksumsAfter.get(version),
                        "allowlist外のFlyway checksumをrepairで変更してはいけない: " + version);
            }
        });
    }

    private void applyPartialV79_1Fixture() throws Exception {
        Flyway v79 = flywayTarget("79");
        v79.migrate();
        assertV79_1AbsentAfterV79();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            // V79.1の最初のALTERだけがMySQLのDDL自動commitで残った状態を再現する。
            statement.execute("ALTER TABLE m_approval_route ADD COLUMN applicant_role_condition VARCHAR(30) NULL "
                    + "COMMENT '申請者role条件(NULL=全role)' AFTER request_type");
            insertHistory(statement, V79_1_VERSION, "approval route decision sources", V79_1_SCRIPT, null, false);
            assertHistoryRow(statement, V79_1_VERSION, V79_1_SCRIPT, 0);
        }

        Flyway partial = flyway();
        Exception blocked = assertThrows(Exception.class, partial::migrate);
        String messages = allMessages(blocked);
        assertTrue(messages.contains(V79_1_VERSION), "partial fixtureの停止理由にV79.1が含まれるはず: " + messages);

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertFailedHistoryIsOnlyV79_1(statement);
        }
        partial.repair();
        partial.migrate();
        partial.validate();
        assertV79_1Schema(partial);
    }

    private void executeForwardRepairRunbook() throws Exception {
        String runbook = Files.readString(
                Path.of("sql", "runbook", "v79_1-fk-actions-forward-repair.sql"), StandardCharsets.UTF_8);
        String executable = Arrays.stream(runbook.split("\\R"))
                .filter(line -> !line.trim().isEmpty())
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining(" "));
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String sql : executable.split(";")) {
                if (!sql.trim().isEmpty()) {
                    statement.execute(sql.trim());
                }
            }
        }
    }

    private void assertV79_1Schema(Flyway flyway) throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertTableExists(statement, "t_approval_responsibility");
            assertColumnExists(statement, "m_approval_route", "applicant_role_condition");
            assertIndexColumns(statement, "t_approval_responsibility", "idx_approval_responsibility_lookup",
                    List.of("tenant_id", "responsibility_type", "organization_id", "valid_from", "valid_to"));
            assertIndexColumns(statement, "t_approval_responsibility", "idx_approval_responsibility_user",
                    List.of("tenant_id", "user_id"));
            assertReferentialAction(statement, "fk_approval_responsibility_org", "RESTRICT", "RESTRICT",
                    "m_organization_unit", "id");
            assertReferentialAction(statement, "fk_approval_responsibility_user", "CASCADE", "RESTRICT",
                    "sys_user", "id");
            assertReferentialAction(statement, "fk_approval_responsibility_created_by", "CASCADE", "SET NULL",
                    "sys_user", "id");
            assertCheckConstraintExists(statement, "chk_approval_responsibility_type");
            assertCheckConstraintExists(statement, "chk_approval_responsibility_period");
            assertCheckConstraintExists(statement, "chk_approval_responsibility_organization");
            assertHistoryRow(statement, V79_1_VERSION, V79_1_SCRIPT, 1);
            Integer expectedChecksum = currentChecksums(flyway).get(V79_1_VERSION);
            assertNotNull(expectedChecksum, "current V79.1 checksumがFlyway infoに存在するはず");
            assertEquals(expectedChecksum, historyChecksum(statement, V79_1_VERSION));
            assertCheckConstraintsAreEnforced(statement);
        }
    }

    private void assertCheckConstraintsAreEnforced(Statement statement) throws Exception {
        long adminId = queryLong(statement, "SELECT id FROM sys_user WHERE username='admin' LIMIT 1");
        long organizationId = queryLong(statement,
                "SELECT id FROM m_organization_unit WHERE code='LEGACY' LIMIT 1");
        statement.executeUpdate("INSERT INTO t_approval_responsibility "
                + "(responsibility_type, organization_id, user_id, valid_from, valid_to, created_by) VALUES "
                + "('ORGANIZATION_MANAGER', " + organizationId + ", " + adminId
                + ", '2026-01-01', '2026-12-31', " + adminId + ")");

        assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO t_approval_responsibility "
                + "(responsibility_type, organization_id, user_id, valid_from, created_by) VALUES "
                + "('INVALID', " + organizationId + ", " + adminId + ", '2026-01-01', " + adminId + ")"),
                "responsibility_typeのCHECK違反をMySQLが拒否するはず");
        assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO t_approval_responsibility "
                + "(responsibility_type, organization_id, user_id, valid_from, valid_to, created_by) VALUES "
                + "('ORGANIZATION_MANAGER', " + organizationId + ", " + adminId
                + ", '2026-12-31', '2026-01-01', " + adminId + ")"),
                "valid_from > valid_toのCHECK違反をMySQLが拒否するはず");
        assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO t_approval_responsibility "
                + "(responsibility_type, user_id, valid_from, created_by) VALUES "
                + "('ORGANIZATION_MANAGER', " + adminId + ", '2026-01-01', " + adminId + ")"),
                "ORGANIZATION_MANAGERのorganization_id NULLをMySQLが拒否するはず");
    }

    private void assertOnlyFailedOrMismatchedEntryIsV79_1(Statement statement, Flyway flyway) throws Exception {
        Map<String, Integer> expected = currentChecksums(flyway);
        Map<String, Integer> actual = historyChecksums(statement);
        assertTrue(actual.containsKey(V79_1_VERSION), "V79.1のhistory checksumが存在するはず");
        for (Map.Entry<String, Integer> entry : actual.entrySet()) {
            if (V79_1_VERSION.equals(entry.getKey())) {
                continue;
            }
            assertNotNull(expected.get(entry.getKey()), "未知のhistory versionはallowlist外: " + entry.getKey());
            assertEquals(expected.get(entry.getKey()), entry.getValue(),
                    "V79.1以外のchecksum不一致をrepairしてはいけない: " + entry.getKey());
        }
    }

    private void assertFailedHistoryIsOnlyV79_1(Statement statement) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0 AND version IS NOT NULL")) {
            assertTrue(resultSet.next() && resultSet.getInt(1) == 1,
                    "failed historyはV79.1の1件だけであるはず");
        }
        assertHistoryRow(statement, V79_1_VERSION, V79_1_SCRIPT, 0);
    }

    private void assertValidationFailureMentionsV79_1(FlywayValidateException failure) {
        String messages = allMessages(failure);
        assertTrue(messages.contains(V79_1_VERSION), "validate失敗にV79.1が含まれるはず: " + messages);
        assertTrue(messages.toLowerCase(Locale.ROOT).contains("checksum"),
                "validate失敗にchecksumが含まれるはず: " + messages);
    }

    private void assertV79_1AbsentAfterV79() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertFalse(hasTable(statement, "t_approval_responsibility"));
            assertFalse(hasColumn(statement, "m_approval_route", "applicant_role_condition"));
            assertFalse(hasHistoryVersion(statement, V79_1_VERSION));
        }
    }

    private void assertV79_1AbsentAfterClean() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertFalse(hasTable(statement, "flyway_schema_history"), "clean後にFlyway historyが残ってはいけない");
            assertFalse(hasTable(statement, "t_approval_responsibility"));
            assertFalse(hasColumn(statement, "m_approval_route", "applicant_role_condition"));
        }
    }

    private Flyway flyway() {
        // 後続spec(S09 order-acceptance-workflow)がV80を実在化したため、
        // validate/repair/migrateはV79.1を上限に固定する（未適用のV80をpending扱いで失敗させない）。
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("79.1")
                .cleanDisabled(false)
                .load();
    }

    private Flyway flywayTarget(String target) {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            if (!hasTable(statement, "flyway_schema_history")) {
                return;
            }
        }
        flyway().clean();
    }

    private void insertHistory(Statement statement, String version, String description, String script,
                               Integer checksum, boolean success) throws Exception {
        long nextRank = queryLong(statement,
                "SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history");
        try (PreparedStatement prepared = statement.getConnection().prepareStatement(
                "INSERT INTO flyway_schema_history "
                        + "(installed_rank, version, description, type, script, checksum, installed_by, "
                        + "installed_on, execution_time, success) VALUES (?, ?, ?, 'SQL', ?, ?, 'ses', "
                        + "CURRENT_TIMESTAMP, 1, ?)")) {
            prepared.setLong(1, nextRank);
            prepared.setString(2, version);
            prepared.setString(3, description);
            prepared.setString(4, script);
            if (checksum == null) {
                prepared.setNull(5, Types.INTEGER);
            } else {
                prepared.setInt(5, checksum);
            }
            prepared.setInt(6, success ? 1 : 0);
            prepared.executeUpdate();
        }
    }

    private Map<String, Integer> currentChecksums(Flyway flyway) {
        return Arrays.stream(flyway.info().all())
                .filter(info -> info.getVersion() != null && info.getChecksum() != null)
                .collect(Collectors.toMap(
                        info -> info.getVersion().getVersion(),
                        MigrationInfo::getChecksum,
                        (left, right) -> right,
                        LinkedHashMap::new));
    }

    private Map<String, Integer> historyChecksums() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            return historyChecksums(statement);
        }
    }

    private Map<String, Integer> historyChecksums(Statement statement) throws Exception {
        Map<String, Integer> result = new LinkedHashMap<>();
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT version, checksum FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL AND success=1 AND checksum IS NOT NULL ORDER BY installed_rank")) {
            while (resultSet.next()) {
                result.put(resultSet.getString("version"), resultSet.getInt("checksum"));
            }
        }
        return result;
    }

    private Integer historyChecksum(Statement statement, String version) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT checksum FROM flyway_schema_history WHERE version='" + version
                        + "' ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(resultSet.next(), version + "のFlyway historyが存在するはず");
            Object value = resultSet.getObject(1);
            return value == null ? null : ((Number) value).intValue();
        }
    }

    private void assertHistoryRow(Statement statement, String version, String script, int success)
            throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT script, success FROM flyway_schema_history WHERE version='" + version
                        + "' ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(resultSet.next(), version + "のFlyway historyが存在するはず");
            assertEquals(script, resultSet.getString("script"));
            assertEquals(success, resultSet.getInt("success"));
        }
    }

    private boolean hasHistoryVersion(Statement statement, String version) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM flyway_schema_history WHERE version='" + version + "'")) {
            return resultSet.next();
        }
    }

    private void assertReferentialAction(String constraint, String updateRule, String deleteRule,
                                         String referencedTable, String referencedColumn) throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertReferentialAction(statement, constraint, updateRule, deleteRule,
                    referencedTable, referencedColumn);
        }
    }

    private void assertReferentialAction(Statement statement, String constraint,
                                         String updateRule, String deleteRule,
                                         String referencedTable, String referencedColumn) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT rc.UPDATE_RULE, rc.DELETE_RULE, kcu.REFERENCED_TABLE_NAME, kcu.REFERENCED_COLUMN_NAME "
                        + "FROM information_schema.REFERENTIAL_CONSTRAINTS rc "
                        + "JOIN information_schema.KEY_COLUMN_USAGE kcu "
                        + "ON kcu.CONSTRAINT_SCHEMA=rc.CONSTRAINT_SCHEMA "
                        + "AND kcu.TABLE_NAME=rc.TABLE_NAME "
                        + "AND kcu.CONSTRAINT_NAME=rc.CONSTRAINT_NAME "
                        + "WHERE rc.CONSTRAINT_SCHEMA=DATABASE() "
                        + "AND rc.TABLE_NAME='t_approval_responsibility' "
                        + "AND rc.CONSTRAINT_NAME='" + constraint + "'")) {
            assertTrue(resultSet.next(), constraint + "のreferential actionが存在するはず");
            assertEquals(updateRule, resultSet.getString("UPDATE_RULE"), constraint + " ON UPDATE");
            assertEquals(deleteRule, resultSet.getString("DELETE_RULE"), constraint + " ON DELETE");
            assertEquals(referencedTable, resultSet.getString("REFERENCED_TABLE_NAME"),
                    constraint + "の参照先table");
            assertEquals(referencedColumn, resultSet.getString("REFERENCED_COLUMN_NAME"),
                    constraint + "の参照先column");
        }
    }

    private void assertIndexColumns(Statement statement, String table, String index,
                                    List<String> expected) throws Exception {
        List<String> actual = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND index_name='" + index
                        + "' ORDER BY seq_in_index")) {
            while (resultSet.next()) {
                actual.add(resultSet.getString(1));
            }
        }
        assertEquals(expected, actual, table + "." + index + "の列順");
    }

    private void assertCheckConstraintExists(Statement statement, String constraint) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()"
                        + " AND table_name='t_approval_responsibility' AND constraint_name='" + constraint
                        + "' AND constraint_type='CHECK'")) {
            assertTrue(resultSet.next(), constraint + "がCHECK制約として存在するはず");
        }
    }

    private void assertTableExists(Statement statement, String table) throws Exception {
        assertTrue(hasTable(statement, table), table + "が存在するはず");
    }

    private void assertColumnExists(Statement statement, String table, String column) throws Exception {
        assertTrue(hasColumn(statement, table, column), table + "." + column + "が存在するはず");
    }

    private boolean hasTable(Statement statement, String table) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='"
                        + table + "'")) {
            return resultSet.next();
        }
    }

    private boolean hasColumn(Statement statement, String table, String column) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"
                        + table + "' AND column_name='" + column + "'")) {
            return resultSet.next();
        }
    }

    private long queryLong(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "fixture query returned no row: " + sql);
            return resultSet.getLong(1);
        }
    }

    private String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }
}
