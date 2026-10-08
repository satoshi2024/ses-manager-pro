package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V184の実MySQL fresh installとV182 B0からのupgradeを検証する。 */
@Tag("mysql")
@Testcontainers
class FlywayNf06V184MetadataMigrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> FRESH = mysql("ses_nf06_v184_fresh");

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> UPGRADE = mysql("ses_nf06_v184_upgrade");

    private static MySQLContainer<?> mysql(String database) {
        return new MySQLContainer<>("mysql:8.0")
                .withDatabaseName(database)
                .withUsername("root")
                .withPassword("ses");
    }

    @Test
    void 空DBからV184まで適用し六表の制約と権限非seedを確認する() throws Exception {
        Flyway flyway = flyway(FRESH).load();
        flyway.migrate();

        try (Connection connection = FRESH.createConnection(""); Statement statement = connection.createStatement()) {
            assertV184Applied(statement, flyway);
            assertMetadataSchema(statement);
            assertEquals(0L, scalar(statement,
                    "SELECT COUNT(*) FROM t_permission_group_action WHERE action_key LIKE 'import.%'"),
                    "V184はimport権限を自動付与しない");

            long sourceId = insertSource(statement, "tenant-a", "legacy-a");
            assertMysqlFailure(() -> statement.executeUpdate(
                    "INSERT INTO t_import_source_system (tenant_id, source_code, display_name, status, "
                            + "lookup_key_version, created_by, updated_by) VALUES "
                            + "('tenant-a','invalid-state','invalid','PAUSED','key-v1',1,1)"),
                    3819, "HY000", "source status列のCHECK制約をMySQLが拒否する");
            assertMysqlFailure(() -> insertMapping(statement, "tenant-b", sourceId,
                    "CUSTOMER", 1, new byte[]{1}), 1452, "23000", "tenant複合source FKがtenant越境を拒否する");
            assertMysqlFailure(() -> insertMapping(statement, "tenant-a", sourceId,
                    "UNKNOWN", 1, new byte[]{1}), 3819, "HY000", "entity_typeのCHECK制約をMySQLが拒否する");
            assertMysqlFailure(() -> insertMapping(statement, "tenant-a", sourceId,
                    "CUSTOMER", 0, new byte[]{1}), 3819, "HY000", "mapping_versionのCHECK制約をMySQLが拒否する");
            assertMysqlFailure(() -> insertMapping(statement, "tenant-a", sourceId,
                    "CUSTOMER", 1, new byte[32769]), 3819, "HY000", "canonical_bytesの上限CHECKをMySQLが拒否する");

            insertMapping(statement, "tenant-a", sourceId, "CUSTOMER", 1, new byte[]{1});
            assertMysqlFailure(() -> insertMapping(statement, "tenant-a", sourceId,
                    "CUSTOMER", 1, new byte[]{2}), 1062, "23000", "同一namespaceのmapping版一意制約を確認する");
        }
    }

    @Test
    void B0_V182から更新し既存業務行と履歴を保持してFlywayの検証に成功する() throws Exception {
        Flyway atB0 = flyway(UPGRADE).target("182").load();
        atB0.migrate();
        long before;
        long permissionActionsBefore;
        try (Connection connection = UPGRADE.createConnection(""); Statement statement = connection.createStatement()) {
            insertUpgradeSentinel(statement);
            before = scalar(statement, "SELECT COUNT(*) FROM sys_user");
            permissionActionsBefore = scalar(statement, "SELECT COUNT(*) FROM t_permission_group_action");
            assertTrue(before > 0, "B0には既存業務ユーザーが存在する");
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='182' AND success=1"));
            assertEquals(0L, scalar(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='184'"));
        }

        Flyway latest = flyway(UPGRADE).load();
        latest.migrate();
        latest.validate();
        try (Connection connection = UPGRADE.createConnection(""); Statement statement = connection.createStatement()) {
            assertV184Applied(statement, latest);
            assertMetadataSchema(statement);
            assertEquals(before, scalar(statement, "SELECT COUNT(*) FROM sys_user"),
                    "upgrade前の既存ユーザー行が保持される");
            assertEquals(permissionActionsBefore, scalar(statement, "SELECT COUNT(*) FROM t_permission_group_action"),
                    "V184の適用は既存permission actionを変更しない");
            assertUpgradeSentinelPreserved(statement);
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM sys_user WHERE username='admin'"),
                    "B0からの既存管理者が保持される");
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='182' AND success=1"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='184' AND success=1"));
            assertEquals(0L, scalar(statement,
                    "SELECT COUNT(*) FROM t_permission_group_action WHERE action_key LIKE 'import.%'"),
                    "upgradeでもimport権限を自動付与しない");
        }

        // repairを呼ばず再migrateし、Flyway validateを通過した状態でno-opになることを確認する。
        latest.migrate();
        try (Connection connection = UPGRADE.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='184' AND success=1"));
            assertEquals(6L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                            + "AND table_name LIKE 't_import_%'"));
        }
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(MySQLContainer<?> container) {
        return Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration");
    }

    private void assertV184Applied(Statement statement, Flyway flyway) throws Exception {
        assertEquals(1L, scalar(statement,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='184' AND success=1"));
        flyway.validate();
        assertTrue(java.util.Arrays.stream(flyway.info().all())
                        .anyMatch(info -> info.getVersion() != null
                                && "184".equals(info.getVersion().getVersion())),
                "V184のmigration情報を取得できる");
        boolean hasRepeatableMigration = false;
        for (MigrationInfo info : flyway.info().all()) {
            if (info.getVersion() == null) {
                hasRepeatableMigration = true;
            }
        }
        assertTrue(hasRepeatableMigration, "Flywayの一覧にはversionを持たない反復migrationも含まれる");
    }

    private void assertMetadataSchema(Statement statement) throws Exception {
        for (String table : new String[]{"t_import_source_system", "t_import_mapping", "t_import_job",
                "t_import_validation_run", "t_import_validation_row", "t_import_identity_registry"}) {
            assertEquals(1L, scalar(statement, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='" + table + "'"), table + "が存在する");
        }
        for (String[] constraint : new String[][]{
                {"t_import_source_system", "chk_import_source_status"},
                {"t_import_mapping", "chk_import_mapping_entity"},
                {"t_import_mapping", "chk_import_mapping_version"},
                {"t_import_mapping", "chk_import_mapping_size"},
                {"t_import_job", "chk_import_job_mapping_nulls"},
                {"t_import_job", "chk_import_job_lease_pair"},
                {"t_import_validation_run", "chk_import_run_slots"},
                {"t_import_validation_run", "chk_import_run_completed_summary"},
                {"t_import_validation_row", "chk_import_row_disposition"},
                {"t_import_identity_registry", "chk_import_identity_candidate"}}) {
            assertEquals(1L, scalar(statement, "SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE constraint_schema=DATABASE() AND table_name='" + constraint[0]
                    + "' AND constraint_name='" + constraint[1] + "' AND constraint_type='CHECK'"),
                    constraint[1] + "のCHECK制約が存在する");
        }
        for (String[] index : new String[][]{
                {"t_import_source_system", "uk_import_source_tenant_code", "tenant_id", "source_code"},
                {"t_import_source_system", "uk_import_source_tenant_id", "tenant_id", "id"},
                {"t_import_mapping", "uk_import_mapping_tenant_id", "tenant_id", "id"},
                {"t_import_mapping", "uk_import_mapping_namespace", "tenant_id", "source_system_id", "entity_type", "schema_version", "mapping_version"},
                {"t_import_mapping", "uk_import_mapping_snapshot", "tenant_id", "id", "source_system_id", "entity_type", "schema_version", "mapping_version", "mapping_sha256"},
                {"t_import_job", "uk_import_job_tenant_id", "tenant_id", "id"},
                {"t_import_job", "uk_import_job_identity", "tenant_id", "source_system_id", "entity_type", "schema_version", "source_sha256"},
                {"t_import_job", "uk_import_job_source_snapshot", "tenant_id", "id", "source_system_id", "source_version", "entity_type"},
                {"t_import_job", "uk_import_job_mapping_snapshot", "tenant_id", "id", "mapping_id", "source_system_id", "entity_type", "schema_version", "mapping_version", "mapping_sha256"},
                {"t_import_validation_run", "uk_import_run_tenant_id", "tenant_id", "id"},
                {"t_import_validation_run", "uk_import_run_job_id", "tenant_id", "job_id", "id"},
                {"t_import_validation_run", "uk_import_run_number", "tenant_id", "job_id", "run_no"},
                {"t_import_validation_run", "uk_import_run_active_slot", "tenant_id", "job_id", "active_slot"},
                {"t_import_validation_run", "uk_import_run_published_slot", "tenant_id", "job_id", "published_slot"},
                {"t_import_validation_run", "uk_import_run_provenance", "tenant_id", "job_id", "id", "source_system_id", "entity_type"},
                {"t_import_validation_row", "uk_import_row_run_number", "tenant_id", "validation_run_id", "source_row_number"},
                {"t_import_identity_registry", "uk_import_identity_namespace", "tenant_id", "source_system_id", "entity_type", "lookup_digest"},
                {"t_import_identity_registry", "uk_import_identity_tenant_id", "tenant_id", "id"}}) {
            assertUniqueIndex(statement, index[0], index[1], java.util.Arrays.copyOfRange(index, 2, index.length));
        }
        for (String[] foreignKey : new String[][]{
                {"t_import_mapping", "fk_import_mapping_source", "t_import_source_system", "tenant_id,source_system_id", "tenant_id,id"},
                {"t_import_job", "fk_import_job_source", "t_import_source_system", "tenant_id,source_system_id", "tenant_id,id"},
                {"t_import_job", "fk_import_job_document", "t_document", "tenant_id,document_id", "tenant_id,id"},
                {"t_import_job", "fk_import_job_document_version", "t_document_version", "tenant_id,document_version_id", "tenant_id,id"},
                {"t_import_job", "fk_import_job_mapping_snapshot", "t_import_mapping", "tenant_id,mapping_id,source_system_id,entity_type,schema_version,mapping_version,mapping_sha256", "tenant_id,id,source_system_id,entity_type,schema_version,mapping_version,mapping_sha256"},
                {"t_import_validation_run", "fk_import_run_job_snapshot", "t_import_job", "tenant_id,job_id,source_system_id,source_version,entity_type", "tenant_id,id,source_system_id,source_version,entity_type"},
                {"t_import_validation_run", "fk_import_run_mapping_snapshot", "t_import_job", "tenant_id,job_id,mapping_id,source_system_id,entity_type,schema_version,mapping_version,mapping_sha256", "tenant_id,id,mapping_id,source_system_id,entity_type,schema_version,mapping_version,mapping_sha256"},
                {"t_import_job", "fk_import_job_active_run", "t_import_validation_run", "tenant_id,id,active_validation_run_id", "tenant_id,job_id,id"},
                {"t_import_job", "fk_import_job_current_run", "t_import_validation_run", "tenant_id,id,current_validation_run_id", "tenant_id,job_id,id"},
                {"t_import_validation_row", "fk_import_row_run", "t_import_validation_run", "tenant_id,job_id,validation_run_id", "tenant_id,job_id,id"},
                {"t_import_identity_registry", "fk_import_identity_source", "t_import_source_system", "tenant_id,source_system_id", "tenant_id,id"},
                {"t_import_identity_registry", "fk_import_identity_provenance", "t_import_validation_run", "tenant_id,first_job_id,first_validation_run_id,source_system_id,entity_type", "tenant_id,job_id,id,source_system_id,entity_type"}}) {
            assertForeignKey(statement, foreignKey[0], foreignKey[1], foreignKey[2],
                    foreignKey[3].split(","), foreignKey[4].split(","));
        }
        assertEquals(0L, scalar(statement, "SELECT COUNT(*) FROM t_document_link "
                + "WHERE target_type='IMPORT_JOB'"), "V184はtyped linkを勝手に作成しない");
    }

    private long insertSource(Statement statement, String tenant, String code) throws SQLException {
        try (PreparedStatement insert = statement.getConnection().prepareStatement(
                "INSERT INTO t_import_source_system (tenant_id,source_code,display_name,lookup_key_version,created_by,updated_by) "
                        + "VALUES (?,?,?,'key-v1',1,1)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, tenant);
            insert.setString(2, code);
            insert.setString(3, code);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertUpgradeSentinel(Statement statement) throws SQLException {
        try (PreparedStatement insert = statement.getConnection().prepareStatement(
                "INSERT INTO sys_user (username,password,real_name,role,email,status,failed_count,locked_until,"
                        + "created_at,updated_at,deleted_flag) VALUES "
                        + "('nf06-v184-sentinel','synthetic-password-hash','NF06更新確認用','営業',"
                        + "'nf06-v184-sentinel@example.local',1,3,'2031-02-03 04:05:06',"
                        + "'2026-01-02 03:04:05','2026-01-02 03:04:05',0)")) {
            insert.executeUpdate();
        }
    }

    private void assertUpgradeSentinelPreserved(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery(
                "SELECT username,password,real_name,role,email,status,failed_count,locked_until,created_at,updated_at,deleted_flag "
                        + "FROM sys_user WHERE username='nf06-v184-sentinel'")) {
            assertTrue(result.next(), "V182後に挿入した確認用行が残る");
            assertEquals("nf06-v184-sentinel", result.getString("username"));
            assertEquals("synthetic-password-hash", result.getString("password"));
            assertEquals("NF06更新確認用", result.getString("real_name"));
            assertEquals("営業", result.getString("role"));
            assertEquals("nf06-v184-sentinel@example.local", result.getString("email"));
            assertEquals(1, result.getInt("status"));
            assertEquals(3, result.getInt("failed_count"));
            assertEquals(Timestamp.valueOf("2031-02-03 04:05:06"), result.getTimestamp("locked_until"));
            assertEquals(Timestamp.valueOf("2026-01-02 03:04:05"), result.getTimestamp("created_at"));
            assertEquals(Timestamp.valueOf("2026-01-02 03:04:05"), result.getTimestamp("updated_at"));
            assertEquals(0, result.getInt("deleted_flag"));
            assertTrue(!result.next(), "確認用usernameは一意で1行だけ残る");
        }
    }

    private void assertUniqueIndex(Statement statement, String table, String index, String[] expectedColumns)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement query = statement.getConnection().prepareStatement(
                "SELECT column_name,non_unique FROM information_schema.statistics "
                        + "WHERE table_schema=DATABASE() AND table_name=? AND index_name=? ORDER BY seq_in_index")) {
            query.setString(1, table);
            query.setString(2, index);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    assertEquals(0, rows.getInt("non_unique"), index + "が一意索引である");
                    columns.add(rows.getString("column_name"));
                }
            }
        }
        assertEquals(List.of(expectedColumns), columns, index + "の一意索引列と順序が一致する");
    }

    private void assertForeignKey(Statement statement, String table, String constraint, String referencedTable,
                                  String[] expectedColumns, String[] expectedReferencedColumns) throws SQLException {
        List<String> columns = new ArrayList<>();
        List<String> referencedColumns = new ArrayList<>();
        try (PreparedStatement query = statement.getConnection().prepareStatement(
                "SELECT column_name,referenced_column_name FROM information_schema.key_column_usage "
                        + "WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_name=? "
                        + "AND referenced_table_name=? ORDER BY ordinal_position")) {
            query.setString(1, table);
            query.setString(2, constraint);
            query.setString(3, referencedTable);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString("column_name"));
                    referencedColumns.add(rows.getString("referenced_column_name"));
                }
            }
        }
        assertEquals(List.of(expectedColumns), columns, constraint + "のFK列順が一致する");
        assertEquals(List.of(expectedReferencedColumns), referencedColumns, constraint + "の参照先列順が一致する");
    }

    private void assertMysqlFailure(Executable operation, int expectedVendorCode, String expectedSqlState,
                                   String message) {
        SQLException failure = assertThrows(SQLException.class, operation, message);
        assertEquals(expectedVendorCode, failure.getErrorCode(), message + "（MySQLエラー番号）");
        assertEquals(expectedSqlState, failure.getSQLState(), message + "（SQLSTATE）");
    }

    private void insertMapping(Statement statement, String tenant, long sourceId, String entity,
                               int version, byte[] canonicalBytes) throws SQLException {
        try (PreparedStatement insert = statement.getConnection().prepareStatement(
                "INSERT INTO t_import_mapping (tenant_id,source_system_id,entity_type,schema_version,"
                        + "mapping_version,canonical_bytes,mapping_sha256,created_by) VALUES (?,?,?,'v1',?,?,?,1)")) {
            insert.setString(1, tenant);
            insert.setLong(2, sourceId);
            insert.setString(3, entity);
            insert.setInt(4, version);
            insert.setBytes(5, canonicalBytes);
            insert.setBytes(6, new byte[32]);
            insert.executeUpdate();
        }
    }

    private long scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "集計queryが結果行を返す");
            return result.getLong(1);
        }
    }
}
