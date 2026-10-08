package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO t_import_source_system (tenant_id, source_code, display_name, status, "
                            + "lookup_key_version, created_by, updated_by) VALUES "
                            + "('tenant-a','invalid-state','invalid','PAUSED','key-v1',1,1)"),
                    "source status CHECKをMySQLが実際に拒否する");
            assertThrows(SQLException.class, () -> insertMapping(statement, "tenant-b", sourceId,
                    "CUSTOMER", 1, new byte[]{1}), "tenant複合source FKがtenant越境を拒否する");
            assertThrows(SQLException.class, () -> insertMapping(statement, "tenant-a", sourceId,
                    "UNKNOWN", 1, new byte[]{1}), "entity CHECKをMySQLが実際に拒否する");
            assertThrows(SQLException.class, () -> insertMapping(statement, "tenant-a", sourceId,
                    "CUSTOMER", 0, new byte[]{1}), "mapping version CHECKをMySQLが実際に拒否する");
            assertThrows(SQLException.class, () -> insertMapping(statement, "tenant-a", sourceId,
                    "CUSTOMER", 1, new byte[32769]), "canonical bytes上限CHECKをMySQLが実際に拒否する");

            insertMapping(statement, "tenant-a", sourceId, "CUSTOMER", 1, new byte[]{1});
            assertThrows(SQLException.class, () -> insertMapping(statement, "tenant-a", sourceId,
                    "CUSTOMER", 1, new byte[]{2}), "同一namespaceのmapping version uniqueを確認する");
        }
    }

    @Test
    void B0_V182からupgradeし既存業務行と履歴を保持してV184checksumを確認する() throws Exception {
        Flyway atB0 = flyway(UPGRADE).target("182").load();
        atB0.migrate();
        long before;
        long permissionActionsBefore;
        try (Connection connection = UPGRADE.createConnection(""); Statement statement = connection.createStatement()) {
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
        try (Connection connection = UPGRADE.createConnection(""); Statement statement = connection.createStatement()) {
            assertV184Applied(statement, latest);
            assertMetadataSchema(statement);
            assertEquals(before, scalar(statement, "SELECT COUNT(*) FROM sys_user"),
                    "upgrade前の既存ユーザー行が保持される");
            assertEquals(permissionActionsBefore, scalar(statement, "SELECT COUNT(*) FROM t_permission_group_action"),
                    "V184 upgradeは既存permission actionを変更しない");
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

        // repairを呼ばず再migrateし、V184のchecksum履歴が一致したままno-opになることを確認する。
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
        MigrationInfo migration = null;
        for (MigrationInfo info : flyway.info().all()) {
            if ("184".equals(info.getVersion().getVersion())) migration = info;
        }
        assertNotNull(migration, "V184のresolved migration情報が存在する");
        assertEquals(migration.getChecksum().intValue(), scalar(statement,
                "SELECT checksum FROM flyway_schema_history WHERE version='184' AND success=1"),
                "Flyway履歴checksumが現在のV184 migrationと一致する");
    }

    private void assertMetadataSchema(Statement statement) throws Exception {
        for (String table : new String[]{"t_import_source_system", "t_import_mapping", "t_import_job",
                "t_import_validation_run", "t_import_validation_row", "t_import_identity_registry"}) {
            assertEquals(1L, scalar(statement, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='" + table + "'"), table + " exists");
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
                    constraint[1] + " CHECK exists");
        }
        for (String[] index : new String[][]{
                {"t_import_source_system", "uk_import_source_tenant_code"},
                {"t_import_mapping", "uk_import_mapping_namespace"},
                {"t_import_job", "uk_import_job_identity"},
                {"t_import_validation_run", "uk_import_run_active_slot"},
                {"t_import_validation_run", "uk_import_run_published_slot"},
                {"t_import_validation_row", "uk_import_row_run_number"},
                {"t_import_identity_registry", "uk_import_identity_namespace"}}) {
            assertEquals(1L, scalar(statement, "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name='" + index[0] + "' AND index_name='" + index[1] + "'"),
                    index[1] + " unique/index exists");
        }
        assertEquals(1L, scalar(statement, "SELECT COUNT(*) FROM information_schema.key_column_usage "
                + "WHERE constraint_schema=DATABASE() AND table_name='t_import_job' "
                + "AND constraint_name='fk_import_job_document' AND column_name='tenant_id' "
                + "AND referenced_table_name='t_document'"), "typed IMPORT_JOBのtenant付きdocument FK exists");
        assertEquals(1L, scalar(statement, "SELECT COUNT(*) FROM information_schema.key_column_usage "
                + "WHERE constraint_schema=DATABASE() AND table_name='t_import_job' "
                + "AND constraint_name='fk_import_job_document_version' AND column_name='tenant_id' "
                + "AND referenced_table_name='t_document_version'"), "exact document-version tenant FK exists");
        for (String[] foreignKey : new String[][]{
                {"t_import_mapping", "fk_import_mapping_source", "t_import_source_system"},
                {"t_import_job", "fk_import_job_source", "t_import_source_system"},
                {"t_import_job", "fk_import_job_mapping_snapshot", "t_import_mapping"},
                {"t_import_validation_run", "fk_import_run_job_snapshot", "t_import_job"},
                {"t_import_validation_run", "fk_import_run_mapping_snapshot", "t_import_job"},
                {"t_import_job", "fk_import_job_active_run", "t_import_validation_run"},
                {"t_import_job", "fk_import_job_current_run", "t_import_validation_run"},
                {"t_import_validation_row", "fk_import_row_run", "t_import_validation_run"},
                {"t_import_identity_registry", "fk_import_identity_source", "t_import_source_system"},
                {"t_import_identity_registry", "fk_import_identity_provenance", "t_import_validation_run"}}) {
            assertEquals(1L, scalar(statement, "SELECT COUNT(DISTINCT constraint_name) "
                    + "FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() "
                    + "AND table_name='" + foreignKey[0] + "' AND constraint_name='" + foreignKey[1]
                    + "' AND referenced_table_name='" + foreignKey[2] + "'"),
                    foreignKey[1] + " tenant/provenance FK exists");
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
            assertTrue(result.next(), "scalar query returned no row");
            return result.getLong(1);
        }
    }
}
