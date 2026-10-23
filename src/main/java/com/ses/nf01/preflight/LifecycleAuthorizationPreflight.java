package com.ses.nf01.preflight;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * NF01 migration前のschema/tenant状態を読み取る専用CLI。
 * 固定SELECTとJDBC metadataだけを実行し、業務データを変更しない。
 */
public final class LifecycleAuthorizationPreflight {
    private static final List<TableSpec> TABLES = List.of(
            new TableSpec("sys_user", Map.of("id", "bigint", "tenant_id", "varchar")),
            new TableSpec("t_lifecycle_case", Map.of("id", "bigint", "tenant_id", "varchar", "engineer_id", "bigint")),
            new TableSpec("t_lifecycle_task", Map.of("id", "bigint", "case_id", "bigint")),
            new TableSpec("t_engineer", Map.of("id", "bigint", "tenant_id", "varchar")),
            new TableSpec("m_customer", Map.of("id", "bigint", "tenant_id", "varchar")),
            new TableSpec("t_contract", Map.of("id", "bigint", "tenant_id", "varchar", "customer_id", "bigint")),
            new TableSpec("t_engineer_account_link", Map.of("id", "bigint", "tenant_id", "varchar", "engineer_id", "bigint", "sys_user_id", "bigint")),
            new TableSpec("t_user_organization", Map.of("id", "bigint", "tenant_id", "varchar", "user_id", "bigint", "organization_id", "bigint")),
            new TableSpec("m_organization_unit", Map.of("id", "bigint", "tenant_id", "bigint")),
            new TableSpec("t_document", Map.of("id", "bigint", "tenant_id", "varchar")),
            new TableSpec("t_document_version", Map.of("id", "bigint", "tenant_id", "varchar", "document_id", "bigint")),
            new TableSpec("t_document_link", Map.of("id", "bigint", "tenant_id", "varchar", "document_id", "bigint")),
            new TableSpec("t_document_disposal_request", Map.of("id", "bigint", "document_id", "bigint")),
            new TableSpec("t_proposal", Map.of("id", "bigint", "engineer_id", "bigint", "project_id", "bigint", "proposed_by", "bigint")),
            new TableSpec("t_project", Map.of("id", "bigint", "customer_id", "bigint")),
            new TableSpec("t_sales_activity", Map.of("id", "bigint", "customer_id", "bigint", "created_by", "bigint")),
            new TableSpec("t_engineer_sales", Map.of("id", "bigint", "engineer_id", "bigint", "sales_user_id", "bigint")),
            new TableSpec("m_asset", Map.of("id", "bigint")),
            new TableSpec("t_asset_assignment", Map.of("id", "bigint", "asset_id", "bigint", "assignee_type", "varchar", "assignee_id", "bigint")),
            new TableSpec("t_asset_lost_incident", Map.of("id", "bigint", "asset_id", "bigint"))
    );

    private static final List<TenantSource> TENANT_SOURCES = List.of(
            new TenantSource("sys_user", "tenant_id"), new TenantSource("t_lifecycle_case", "tenant_id"),
            new TenantSource("t_engineer", "tenant_id"), new TenantSource("m_customer", "tenant_id"),
            new TenantSource("t_contract", "tenant_id"), new TenantSource("t_engineer_account_link", "tenant_id"),
            new TenantSource("t_user_organization", "tenant_id"), new TenantSource("t_document", "tenant_id"),
            new TenantSource("t_document_version", "tenant_id"), new TenantSource("t_document_link", "tenant_id")
    );

    private static final List<ParentQuery> PARENT_QUERIES = List.of(
            new ParentQuery("proposal", "SELECT p.id row_id, e.tenant_id candidate FROM t_proposal p LEFT JOIN t_engineer e ON e.id=p.engineer_id UNION ALL SELECT p.id row_id, c.tenant_id candidate FROM t_proposal p LEFT JOIN t_project pr ON pr.id=p.project_id LEFT JOIN m_customer c ON c.id=pr.customer_id UNION ALL SELECT p.id row_id, u.tenant_id candidate FROM t_proposal p LEFT JOIN sys_user u ON u.id=p.proposed_by"),
            new ParentQuery("project", "SELECT p.id row_id, c.tenant_id candidate FROM t_project p LEFT JOIN m_customer c ON c.id=p.customer_id"),
            new ParentQuery("sales_activity", "SELECT a.id row_id, c.tenant_id candidate FROM t_sales_activity a LEFT JOIN m_customer c ON c.id=a.customer_id UNION ALL SELECT a.id row_id, u.tenant_id candidate FROM t_sales_activity a LEFT JOIN sys_user u ON u.id=a.created_by"),
            new ParentQuery("engineer_sales", "SELECT x.id row_id, e.tenant_id candidate FROM t_engineer_sales x LEFT JOIN t_engineer e ON e.id=x.engineer_id UNION ALL SELECT x.id row_id, u.tenant_id candidate FROM t_engineer_sales x LEFT JOIN sys_user u ON u.id=x.sales_user_id"),
            new ParentQuery("asset", "SELECT a.id row_id, CASE WHEN x.assignee_type='ENGINEER' THEN e.tenant_id WHEN x.assignee_type='USER' THEN u.tenant_id ELSE NULL END candidate FROM m_asset a LEFT JOIN t_asset_assignment x ON x.asset_id=a.id AND x.actual_return_date IS NULL LEFT JOIN t_engineer e ON x.assignee_type='ENGINEER' AND e.id=x.assignee_id LEFT JOIN sys_user u ON x.assignee_type='USER' AND u.id=x.assignee_id"),
            new ParentQuery("asset_assignment", "SELECT x.id row_id, CASE WHEN x.assignee_type='ENGINEER' THEN e.tenant_id WHEN x.assignee_type='USER' THEN u.tenant_id ELSE NULL END candidate FROM t_asset_assignment x LEFT JOIN t_engineer e ON x.assignee_type='ENGINEER' AND e.id=x.assignee_id LEFT JOIN sys_user u ON x.assignee_type='USER' AND u.id=x.assignee_id"),
            new ParentQuery("asset_lost_incident", "SELECT i.id row_id, e.tenant_id candidate FROM t_asset_lost_incident i LEFT JOIN t_asset_assignment a ON a.asset_id=i.asset_id AND a.actual_return_date IS NULL LEFT JOIN t_engineer e ON a.assignee_type='ENGINEER' AND e.id=a.assignee_id UNION ALL SELECT i.id row_id, u.tenant_id candidate FROM t_asset_lost_incident i LEFT JOIN t_asset_assignment a ON a.asset_id=i.asset_id AND a.actual_return_date IS NULL LEFT JOIN sys_user u ON a.assignee_type='USER' AND u.id=a.assignee_id")
    );

    private LifecycleAuthorizationPreflight() { }

    public static void main(String[] args) {
        String schema = System.getenv("NF01_PREFLIGHT_SCHEMA");
        String baseline = System.getenv("NF01_PREFLIGHT_CODE_BASELINE");
        String outputValue = System.getenv("NF01_PREFLIGHT_OUTPUT_DIR");
        String url = System.getenv("NF01_PREFLIGHT_JDBC_URL");
        String user = System.getenv("NF01_PREFLIGHT_DB_USER");
        String password = System.getenv("NF01_PREFLIGHT_DB_PASSWORD");
        Path output = outputValue == null || outputValue.isBlank() ? null : Path.of(outputValue);
        if (url == null || !url.startsWith("jdbc:mysql:")) {
            PreflightReport failed = PreflightReport.failed(baseline == null ? "unknown" : baseline,
                    schema == null ? "unknown" : schema, "PREFLIGHT_DATABASE_UNSUPPORTED");
            try { if (output != null) write(failed, output); } catch (IOException ignored) { }
            System.exit(2);
            return;
        }
        try (Connection connection = java.sql.DriverManager.getConnection(url, user, password)) {
            PreflightReport report = run(connection, schema, baseline);
            write(report, output);
            if (!report.status().equals("PASS")) {
                System.exit(2);
            }
        } catch (Exception ignored) {
            PreflightReport failed = PreflightReport.failed(baseline == null ? "unknown" : baseline,
                    schema == null ? "unknown" : schema, "PREFLIGHT_EXECUTION_FAILED");
            try {
                if (output != null) write(failed, output);
            } catch (IOException ignoredWrite) {
                // DB exceptionやoutput pathをstdout/stderrへ出さない。
            }
            System.exit(2);
        }
    }

    public static PreflightReport run(Connection connection, String schema, String codeBaseline) {
        PreflightReport report = new PreflightReport(codeBaseline, schema);
        try {
            if (schema == null || schema.isBlank() || codeBaseline == null || codeBaseline.isBlank()) {
                return report.fail("INPUT_INVALID", "input", "schema/code baseline is required");
            }
            if (!connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql")) {
                return report.fail("DATABASE_UNSUPPORTED", "database", "MySQL is required");
            }
            if (!schema.equals(connection.getCatalog())) {
                return report.fail("SCHEMA_MISMATCH", "schema", "requested schema differs from JDBC catalog");
            }
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement transaction = connection.createStatement()) {
                transaction.execute("START TRANSACTION READ ONLY");
            }
            inventory(connection, schema, report);
            if (!report.hasFailures()) {
                tenantSources(connection, report);
                relationshipChecks(connection, report);
                parentCandidates(connection, report);
                organizationUnitCandidates(connection, report);
            }
            connection.commit();
            return report;
        } catch (Exception ignored) {
            rollbackQuietly(connection);
            return report.fail("PREFLIGHT_INCOMPLETE", "execution", "read-only collection did not complete");
        }
    }

    private static void inventory(Connection connection, String schema, PreflightReport report) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, Map<String, ColumnInfo>> columns = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (ResultSet result = metadata.getColumns(schema, null, "%", "%")) {
            while (result.next()) {
                String table = result.getString("TABLE_NAME");
                String column = result.getString("COLUMN_NAME");
                columns.computeIfAbsent(table, ignored -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                        .put(column, new ColumnInfo(result.getString("TYPE_NAME"), result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                result.getString("CHAR_OCTET_LENGTH"), result.getString("IS_AUTOINCREMENT"), null));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH,COLLATION_NAME,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=?")) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, ColumnInfo> table = columns.get(result.getString("TABLE_NAME"));
                    if (table == null) continue;
                    String column = result.getString("COLUMN_NAME");
                    if (table.containsKey(column)) table.put(column, new ColumnInfo(result.getString("COLUMN_TYPE"),
                            "YES".equalsIgnoreCase(result.getString("IS_NULLABLE")),
                            result.getString("CHARACTER_MAXIMUM_LENGTH"),
                            result.getString("EXTRA").contains("auto_increment") ? "YES" : "NO",
                            result.getString("COLLATION_NAME")));
                }
            }
        }
        for (TableSpec spec : TABLES) {
            Map<String, ColumnInfo> present = columns.get(spec.name());
            if (present == null) {
                report.fail("SCHEMA_TABLE_MISSING", spec.name(), "required current table is missing");
                continue;
            }
            for (Map.Entry<String, String> expected : spec.columns().entrySet()) {
                ColumnInfo actual = present.get(expected.getKey());
                if (actual == null) {
                    report.fail("SCHEMA_COLUMN_MISSING", spec.name() + "." + expected.getKey(), "required current column is missing");
                } else if (!actual.type().toLowerCase().startsWith(expected.getValue())) {
                    report.fail("SCHEMA_COLUMN_TYPE", spec.name() + "." + expected.getKey(), "current column type differs from expected family");
                } else {
                    report.column(spec.name(), expected.getKey(), actual);
                }
            }
            if (spec.name().equals("t_document_disposal_request")) {
                boolean hasTenant = present.containsKey("tenant_id");
                report.legacyDisposalTenantColumnAbsent(!hasTenant);
                if (hasTenant && !present.get("tenant_id").type().toLowerCase().startsWith("varchar")) {
                    report.fail("SCHEMA_COLUMN_TYPE", spec.name() + ".tenant_id", "optional future column has unexpected type");
                }
                if (hasTenant) report.column(spec.name(), "tenant_id", present.get("tenant_id"));
            }
        }
        compareTenantColumnDefinitions(columns, report);
        Map<String, ColumnInfo> flywayColumns = columns.get("flyway_schema_history");
        if (flywayColumns == null) {
            report.migrationHistory(false, List.of());
        } else if (flywayColumns.containsKey("version") && flywayColumns.containsKey("success")
                && flywayColumns.containsKey("installed_rank")) {
            List<String> applied = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                    "SELECT version,installed_rank FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank")) {
                while (result.next()) applied.add(result.getString("installed_rank") + ":" + safeToken(result.getString("version")));
            }
            report.migrationHistory(true, applied);
        } else {
            report.fail("SCHEMA_FLYWAY_HISTORY_INCOMPLETE", "flyway_schema_history", "history table lacks required inventory columns");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT TABLE_NAME,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE'")) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) report.tableCollation(result.getString("TABLE_NAME"), result.getString("TABLE_COLLATION"));
            }
        }
        for (TableSpec spec : TABLES) {
            if (!columns.containsKey(spec.name())) continue;
            try (ResultSet result = metadata.getIndexInfo(schema, null, spec.name(), false, false)) {
                Map<String, Map<Short, String>> indexes = new TreeMap<>();
                while (result.next()) {
                    String name = result.getString("INDEX_NAME");
                    String column = result.getString("COLUMN_NAME");
                    if (name != null && column != null) {
                        String uniqueName = name + (result.getBoolean("NON_UNIQUE") ? "" : " [UNIQUE]");
                        indexes.computeIfAbsent(uniqueName, ignored -> new TreeMap<>())
                                .put(result.getShort("ORDINAL_POSITION"), column);
                    }
                }
                report.indexes(spec.name(), indexes);
            }
            try (ResultSet result = metadata.getImportedKeys(schema, null, spec.name())) {
                Map<String, Map<Short, String>> foreignKeys = new TreeMap<>();
                while (result.next()) {
                    String name = result.getString("FK_NAME");
                    if (name == null) name = "unnamed";
                    String link = result.getString("FKCOLUMN_NAME") + " -> " + result.getString("PKTABLE_NAME")
                            + "(" + result.getString("PKCOLUMN_NAME") + ")";
                    foreignKeys.computeIfAbsent(name, ignored -> new TreeMap<>()).put(result.getShort("KEY_SEQ"), link);
                }
                report.foreignKeys(spec.name(), foreignKeys);
            }
        }
    }

    private static void compareTenantColumnDefinitions(Map<String, Map<String, ColumnInfo>> columns,
                                                       PreflightReport report) {
        List<String> tenantTables = List.of("sys_user", "t_lifecycle_case", "t_engineer", "m_customer", "t_contract",
                "t_engineer_account_link", "t_user_organization", "t_document", "t_document_version", "t_document_link");
        Map<String, ColumnInfo> documentColumns = columns.get("t_document");
        if (documentColumns == null || documentColumns.get("tenant_id") == null) {
            report.fail("TENANT_COLUMN_COLLATION", "t_document.tenant_id", "reference definition is unavailable");
            return;
        }
        ColumnInfo reference = documentColumns.get("tenant_id");
        List<String> mismatches = new ArrayList<>();
        for (String tableName : tenantTables) {
            Map<String, ColumnInfo> table = columns.get(tableName);
            ColumnInfo actual = table == null ? null : table.get("tenant_id");
            if (actual == null) continue;
            if (!reference.charLength().equals(actual.charLength())
                    || !java.util.Objects.equals(reference.collation(), actual.collation())) {
                mismatches.add(tableName + ".tenant_id");
            }
        }
        report.finding("TENANT_COLUMN_COLLATION", "tenant_id columns", mismatches.isEmpty() ? "PASS" : "FAIL",
                mismatches.size(), mismatches, mismatches.isEmpty()
                        ? "string tenant columns share length and collation"
                        : "string tenant column length/collation differs from t_document.tenant_id");
    }

    private static void tenantSources(Connection connection, PreflightReport report) throws SQLException {
        Set<String> union = new TreeSet<>();
        for (TenantSource source : TENANT_SOURCES) {
            long totalRows = 0;
            String sql = "SELECT CONVERT(MIN(BINARY " + source.column() + ") USING utf8mb4) tenant_id, COUNT(*) row_count FROM "
                    + source.table() + " GROUP BY BINARY " + source.column();
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    String tenant = rows.getString("tenant_id");
                    long count = rows.getLong("row_count");
                    totalRows += count;
                    report.tenantBucket(source.table(), tenant, count);
                    if (tenant != null && !tenant.isBlank()) union.add(tenant);
                }
            }
            report.finding("TENANT_SOURCE_TOTAL", source.table(), "PASS", totalRows, List.of(), "all rows included in tenant bucket scan");
            String nullSql = "SELECT COUNT(*) FROM " + source.table() + " WHERE " + source.column() + " IS NULL OR TRIM(CAST(" + source.column() + " AS CHAR)) = '' OR CAST(" + source.column() + " AS CHAR) REGEXP '^[[:space:]]*$'";
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(nullSql)) {
                rows.next();
                long missing = rows.getLong(1);
                if (missing > 0) {
                    List<String> samples = new ArrayList<>();
                    try (Statement sampleStatement = connection.createStatement(); ResultSet sampleRows = sampleStatement.executeQuery(
                            "SELECT id FROM " + source.table() + " WHERE " + source.column() + " IS NULL OR TRIM(CAST(" + source.column() + " AS CHAR)) = '' OR CAST(" + source.column() + " AS CHAR) REGEXP '^[[:space:]]*$' ORDER BY id LIMIT 20")) {
                        while (sampleRows.next()) samples.add(redact(source.table(), sampleRows.getLong(1)));
                    }
                    report.finding("TENANT_NULL_OR_BLANK", source.table() + "." + source.column(), "FAIL", missing,
                            samples, "rows with null/blank tenant");
                }
            }
        }
        report.tenantCandidates(union);
    }

    private static void relationshipChecks(Connection connection, PreflightReport report) throws SQLException {
        checkCount(connection, report, "DOCUMENT_VERSION_PARENT_MISMATCH",
                "SELECT COUNT(*) FROM t_document_version v LEFT JOIN t_document d ON d.id=v.document_id WHERE d.id IS NULL OR d.tenant_id<>v.tenant_id",
                "SELECT v.id FROM t_document_version v LEFT JOIN t_document d ON d.id=v.document_id WHERE d.id IS NULL OR d.tenant_id<>v.tenant_id ORDER BY v.id LIMIT 20",
                "t_document_version.document_id", "version parent missing or tenant differs");
        checkCount(connection, report, "DOCUMENT_LINK_PARENT_MISMATCH",
                "SELECT COUNT(*) FROM t_document_link l LEFT JOIN t_document d ON d.id=l.document_id WHERE d.id IS NULL OR d.tenant_id<>l.tenant_id",
                "SELECT l.id FROM t_document_link l LEFT JOIN t_document d ON d.id=l.document_id WHERE d.id IS NULL OR d.tenant_id<>l.tenant_id ORDER BY l.id LIMIT 20",
                "t_document_link.document_id", "link parent missing or tenant differs");
        checkCount(connection, report, "DISPOSAL_PARENT_MISSING",
                "SELECT COUNT(*) FROM t_document_disposal_request r LEFT JOIN t_document d ON d.id=r.document_id WHERE d.id IS NULL",
                "SELECT r.id FROM t_document_disposal_request r LEFT JOIN t_document d ON d.id=r.document_id WHERE d.id IS NULL ORDER BY r.id LIMIT 20",
                "t_document_disposal_request.document_id", "disposal request parent missing");
        checkCount(connection, report, "LIFECYCLE_TASK_CASE_MISSING",
                "SELECT COUNT(*) FROM t_lifecycle_task t LEFT JOIN t_lifecycle_case c ON c.id=t.case_id WHERE c.id IS NULL",
                "SELECT t.id FROM t_lifecycle_task t LEFT JOIN t_lifecycle_case c ON c.id=t.case_id WHERE c.id IS NULL ORDER BY t.id LIMIT 20",
                "t_lifecycle_task.case_id", "task case parent missing");
        checkCount(connection, report, "CASE_ENGINEER_TENANT_MISMATCH",
                "SELECT COUNT(*) FROM t_lifecycle_case c LEFT JOIN t_engineer e ON e.id=c.engineer_id WHERE e.id IS NULL OR e.tenant_id<>c.tenant_id",
                "SELECT c.id FROM t_lifecycle_case c LEFT JOIN t_engineer e ON e.id=c.engineer_id WHERE e.id IS NULL OR e.tenant_id<>c.tenant_id ORDER BY c.id LIMIT 20",
                "t_lifecycle_case.engineer_id", "case engineer parent missing or tenant differs");
        checkCount(connection, report, "CONTRACT_CUSTOMER_TENANT_MISMATCH",
                "SELECT COUNT(*) FROM t_contract c LEFT JOIN m_customer m ON m.id=c.customer_id WHERE m.id IS NULL OR (c.tenant_id IS NOT NULL AND m.tenant_id IS NOT NULL AND c.tenant_id<>m.tenant_id)",
                "SELECT c.id FROM t_contract c LEFT JOIN m_customer m ON m.id=c.customer_id WHERE m.id IS NULL OR (c.tenant_id IS NOT NULL AND m.tenant_id IS NOT NULL AND c.tenant_id<>m.tenant_id) ORDER BY c.id LIMIT 20",
                "t_contract.customer_id", "contract customer parent missing or tenant differs");
        checkCount(connection, report, "ACCOUNT_LINK_PARENT_MISMATCH",
                "SELECT COUNT(*) FROM t_engineer_account_link l LEFT JOIN t_engineer e ON e.id=l.engineer_id LEFT JOIN sys_user u ON u.id=l.sys_user_id WHERE e.id IS NULL OR u.id IS NULL OR e.tenant_id<>l.tenant_id OR u.tenant_id<>l.tenant_id",
                "SELECT l.id FROM t_engineer_account_link l LEFT JOIN t_engineer e ON e.id=l.engineer_id LEFT JOIN sys_user u ON u.id=l.sys_user_id WHERE e.id IS NULL OR u.id IS NULL OR e.tenant_id<>l.tenant_id OR u.tenant_id<>l.tenant_id ORDER BY l.id LIMIT 20",
                "t_engineer_account_link", "engineer/user parent missing or tenant differs");
        checkCount(connection, report, "USER_ORGANIZATION_PARENT_MISSING",
                "SELECT COUNT(*) FROM t_user_organization x LEFT JOIN sys_user u ON u.id=x.user_id LEFT JOIN m_organization_unit o ON o.id=x.organization_id WHERE u.id IS NULL OR o.id IS NULL OR u.tenant_id<>x.tenant_id",
                "SELECT x.id FROM t_user_organization x LEFT JOIN sys_user u ON u.id=x.user_id LEFT JOIN m_organization_unit o ON o.id=x.organization_id WHERE u.id IS NULL OR o.id IS NULL OR u.tenant_id<>x.tenant_id ORDER BY x.id LIMIT 20",
                "t_user_organization", "user/organization parent missing or user tenant differs");
        checkCount(connection, report, "ASSET_ASSIGNMENT_ASSET_MISSING",
                "SELECT COUNT(*) FROM t_asset_assignment x LEFT JOIN m_asset a ON a.id=x.asset_id WHERE a.id IS NULL",
                "SELECT x.id FROM t_asset_assignment x LEFT JOIN m_asset a ON a.id=x.asset_id WHERE a.id IS NULL ORDER BY x.id LIMIT 20",
                "t_asset_assignment.asset_id", "asset parent missing");
        checkCount(connection, report, "ASSET_INCIDENT_ASSET_MISSING",
                "SELECT COUNT(*) FROM t_asset_lost_incident i LEFT JOIN m_asset a ON a.id=i.asset_id WHERE a.id IS NULL",
                "SELECT i.id FROM t_asset_lost_incident i LEFT JOIN m_asset a ON a.id=i.asset_id WHERE a.id IS NULL ORDER BY i.id LIMIT 20",
                "t_asset_lost_incident.asset_id", "asset parent missing");
        checkCount(connection, report, "PROPOSAL_PARENT_MISSING",
                "SELECT COUNT(*) FROM t_proposal p LEFT JOIN t_engineer e ON e.id=p.engineer_id LEFT JOIN t_project pr ON pr.id=p.project_id WHERE e.id IS NULL OR pr.id IS NULL",
                "SELECT p.id FROM t_proposal p LEFT JOIN t_engineer e ON e.id=p.engineer_id LEFT JOIN t_project pr ON pr.id=p.project_id WHERE e.id IS NULL OR pr.id IS NULL ORDER BY p.id LIMIT 20",
                "t_proposal", "required engineer/project parent missing");
        checkCount(connection, report, "ENGINEER_SALES_PARENT_MISSING",
                "SELECT COUNT(*) FROM t_engineer_sales x LEFT JOIN t_engineer e ON e.id=x.engineer_id LEFT JOIN sys_user u ON u.id=x.sales_user_id WHERE e.id IS NULL OR u.id IS NULL",
                "SELECT x.id FROM t_engineer_sales x LEFT JOIN t_engineer e ON e.id=x.engineer_id LEFT JOIN sys_user u ON u.id=x.sales_user_id WHERE e.id IS NULL OR u.id IS NULL ORDER BY x.id LIMIT 20",
                "t_engineer_sales", "engineer or sales user parent missing");
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT d.tenant_id, COUNT(*) row_count FROM t_document_disposal_request r JOIN t_document d ON d.id=r.document_id GROUP BY d.tenant_id")) {
            while (rows.next()) report.tenantBucket("t_document_disposal_request(parent)", rows.getString("tenant_id"), rows.getLong("row_count"));
        }
    }

    private static void checkCount(Connection connection, PreflightReport report, String id, String sql, String sampleSql,
                                   String location, String reason) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("empty aggregate");
            long count = rows.getLong(1);
            if (count > 0) {
                List<String> samples = new ArrayList<>();
                try (Statement sampleStatement = connection.createStatement(); ResultSet sampleRows = sampleStatement.executeQuery(sampleSql)) {
                    while (sampleRows.next()) samples.add(redact(id, sampleRows.getLong(1)));
                }
                report.finding(id, location, "FAIL", count, samples, reason);
            }
            else report.finding(id, location, "PASS", 0, List.of(), "no mismatch found");
        }
    }

    private static void parentCandidates(Connection connection, PreflightReport report) throws SQLException {
        for (ParentQuery query : PARENT_QUERIES) {
            Map<Long, Set<String>> candidates = new TreeMap<>();
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query.sql())) {
                while (rows.next()) {
                    long rowId = rows.getLong("row_id");
                    candidates.computeIfAbsent(rowId, ignored -> new TreeSet<>());
                    String tenant = rows.getString("candidate");
                    if (tenant != null && !tenant.isBlank()) candidates.get(rowId).add(tenant);
                }
            }
            List<String> unresolved = new ArrayList<>();
            long ambiguous = 0;
            for (Map.Entry<Long, Set<String>> entry : candidates.entrySet()) {
                if (entry.getValue().size() != 1) {
                    unresolved.add(redact(query.name(), entry.getKey()));
                    if (entry.getValue().size() > 1) ambiguous++;
                } else {
                    report.tenantCandidate(entry.getValue().iterator().next());
                }
            }
            report.finding("TENANT_PARENT_CANDIDATES", query.name(), unresolved.isEmpty() ? "PASS" : "BLOCKED_MAPPING",
                    candidates.size(), unresolved.subList(0, Math.min(20, unresolved.size())),
                    "source rows=" + candidates.size() + "; unresolved=" + unresolved.size() + "; ambiguous=" + ambiguous);
        }
    }

    private static void organizationUnitCandidates(Connection connection, PreflightReport report) throws SQLException {
        Set<String> numericIds = new TreeSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT tenant_id, COUNT(*) row_count FROM m_organization_unit GROUP BY tenant_id")) {
            while (rows.next()) {
                Long id = rows.getObject("tenant_id", Long.class);
                if (id == null) {
                    List<String> samples = new ArrayList<>();
                    try (Statement sampleStatement = connection.createStatement(); ResultSet sampleRows = sampleStatement.executeQuery(
                            "SELECT id FROM m_organization_unit WHERE tenant_id IS NULL ORDER BY id LIMIT 20")) {
                        while (sampleRows.next()) samples.add(redact("m_organization_unit", sampleRows.getLong(1)));
                    }
                    report.finding("ORG_UNIT_TENANT_NULL", "m_organization_unit.tenant_id", "BLOCKED_MAPPING",
                            rows.getLong("row_count"), samples, "legacy numeric tenant is null");
                }
                else numericIds.add(Long.toString(id));
            }
        }
        List<String> candidates = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT u.organization_id, CONVERT(MIN(BINARY u.tenant_id) USING utf8mb4) tenant_id, o.tenant_id legacy_numeric_tenant_id FROM t_user_organization u JOIN m_organization_unit o ON o.id=u.organization_id GROUP BY u.organization_id,BINARY u.tenant_id,o.tenant_id")) {
            while (rows.next()) {
                candidates.add("organization=" + redact("organization", rows.getLong("organization_id"))
                        + ", membershipTenant=" + safeToken(rows.getString("tenant_id"))
                        + ", legacyNumericTenantId=" + rows.getObject("legacy_numeric_tenant_id", Long.class));
            }
        }
        if (!numericIds.isEmpty() || !candidates.isEmpty()) {
            report.finding("ORG_UNIT_TENANT_MAPPING", "m_organization_unit.tenant_id", "BLOCKED_MAPPING",
                    numericIds.size(), candidates.subList(0, Math.min(20, candidates.size())),
                    "numeric legacy IDs require an explicitly reviewed mapping; no numeric-to-string conversion is applied");
        } else {
            report.finding("ORG_UNIT_TENANT_MAPPING", "m_organization_unit.tenant_id", "PASS", 0, List.of(),
                    "no non-null numeric legacy tenant IDs were found");
        }
    }

    private static String redact(String table, long id) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((table + ":" + id).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 6; index++) hex.append(String.format("%02x", hash[index]));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "redacted";
        }
    }

    private static String safeToken(String value) {
        if (value == null) return "<null>";
        return value.length() <= 100 ? value : value.substring(0, 100);
    }

    private static void rollbackQuietly(Connection connection) {
        try { connection.rollback(); } catch (SQLException ignored) { }
    }

    public static void write(PreflightReport report, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve("nf01-preflight.json"), report.toJson(), StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("nf01-preflight.md"), report.toMarkdown(), StandardCharsets.UTF_8);
    }

    private record TableSpec(String name, Map<String, String> columns) { }
    private record TenantSource(String table, String column) { }
    private record ParentQuery(String name, String sql) { }
    private record ColumnInfo(String type, boolean nullable, String charLength, String autoIncrement, String collation) { }

    public static final class PreflightReport {
        private final String codeBaseline;
        private final String schema;
        private final String generatedAt = Instant.now().toString();
        private final List<Finding> findings = new ArrayList<>();
        private final List<String> columns = new ArrayList<>();
        private final Map<String, List<String>> indexes = new TreeMap<>();
        private final Map<String, List<String>> foreignKeys = new TreeMap<>();
        private final Map<String, String> tableCollations = new TreeMap<>();
        private final Map<String, Long> tenantBuckets = new TreeMap<>();
        private final Set<String> tenantCandidates = new TreeSet<>();
        private boolean migrationHistoryPresent;
        private List<String> appliedMigrations = List.of();
        private boolean legacyDisposalTenantColumnAbsent;

        PreflightReport(String codeBaseline, String schema) {
            this.codeBaseline = safeToken(codeBaseline);
            this.schema = safeToken(schema);
        }

        static PreflightReport failed(String baseline, String schema, String id) {
            PreflightReport report = new PreflightReport(baseline, schema);
            report.fail(id, "execution", "read-only collection did not complete");
            return report;
        }

        public String status() {
            if (findings.stream().anyMatch(item -> item.status().equals("FAIL"))) return "FAIL";
            if (findings.stream().anyMatch(item -> item.status().equals("BLOCKED_MAPPING"))) return "BLOCKED_MAPPING";
            return "PASS";
        }

        private boolean hasFailures() { return findings.stream().anyMatch(item -> item.status().equals("FAIL")); }
        private PreflightReport fail(String id, String location, String reason) {
            finding(id, location, "FAIL", 1, List.of(), reason);
            return this;
        }
        void finding(String id, String location, String status, long count, List<String> samples, String reason) {
            findings.add(new Finding(id, location, status, count, List.copyOf(samples), reason));
        }
        private void column(String table, String column, ColumnInfo info) {
            columns.add(table + "." + column + " type=" + info.type() + " nullable=" + info.nullable()
                    + " charLength=" + info.charLength() + " collation=" + info.collation() + " autoIncrement=" + info.autoIncrement());
        }
        private void indexes(String table, Map<String, Map<Short, String>> values) {
            values.forEach((name, cols) -> indexes.put(table + "." + name, List.copyOf(cols.values())));
        }
        private void foreignKeys(String table, Map<String, Map<Short, String>> values) {
            values.forEach((name, cols) -> foreignKeys.put(table + "." + name, List.copyOf(cols.values())));
        }
        private void tableCollation(String table, String collation) { tableCollations.put(table, collation); }
        private void tenantBucket(String table, String tenant, long count) {
            tenantBuckets.put(table + "." + (tenant == null ? "<null>" : safeToken(tenant)), count);
        }
        private void tenantCandidates(Set<String> values) { tenantCandidates.addAll(values); }
        private void tenantCandidate(String value) { tenantCandidates.add(value); }
        private void legacyDisposalTenantColumnAbsent(boolean absent) { legacyDisposalTenantColumnAbsent = absent; }
        private void migrationHistory(boolean present, List<String> versions) {
            migrationHistoryPresent = present;
            appliedMigrations = List.copyOf(versions);
        }
        boolean legacyDisposalTenantColumnAbsentForTest() { return legacyDisposalTenantColumnAbsent; }

        public String toJson() {
            StringBuilder json = new StringBuilder("{");
            field(json, "status", status()).append(',');
            field(json, "codeBaseline", codeBaseline).append(',');
            field(json, "schema", schema).append(',');
            field(json, "generatedAt", generatedAt).append(',');
            json.append("\"legacyDisposalTenantColumnAbsent\":").append(legacyDisposalTenantColumnAbsent).append(',');
            json.append("\"migrationHistoryPresent\":").append(migrationHistoryPresent).append(',');
            json.append("\"appliedMigrations\":"); stringArray(json, appliedMigrations).append(',');
            json.append("\"tenantCandidates\":"); stringArray(json, tenantCandidates).append(',');
            json.append("\"schemaColumns\":"); stringArray(json, columns).append(',');
            json.append("\"tenantBuckets\":{");
            boolean first = true;
            for (Map.Entry<String, Long> entry : tenantBuckets.entrySet()) {
                if (!first) json.append(','); first = false;
                quote(json, entry.getKey()).append(':').append(entry.getValue());
            }
            json.append("},\"tableCollations\":{");
            first = true;
            for (Map.Entry<String, String> entry : tableCollations.entrySet()) {
                if (!first) json.append(','); first = false;
                quote(json, entry.getKey()).append(':'); quote(json, entry.getValue());
            }
            json.append("},\"indexes\":{");
            first = true;
            for (Map.Entry<String, List<String>> entry : indexes.entrySet()) {
                if (!first) json.append(','); first = false;
                quote(json, entry.getKey()).append(':'); stringArray(json, entry.getValue());
            }
            json.append("},\"foreignKeys\":{");
            first = true;
            for (Map.Entry<String, List<String>> entry : foreignKeys.entrySet()) {
                if (!first) json.append(','); first = false;
                quote(json, entry.getKey()).append(':'); stringArray(json, entry.getValue());
            }
            json.append("},\"findings\":[");
            for (int i = 0; i < findings.size(); i++) {
                if (i > 0) json.append(',');
                Finding finding = findings.get(i);
                json.append('{'); field(json, "checkId", finding.checkId()).append(',');
                field(json, "location", finding.location()).append(','); field(json, "status", finding.status()).append(',');
                json.append("\"rowCount\":").append(finding.rowCount()).append(',');
                json.append("\"samples\":"); stringArray(json, finding.samples()).append(','); field(json, "reason", finding.reason());
                json.append('}');
            }
            return json.append("]}").toString();
        }

        public String toMarkdown() {
            StringBuilder out = new StringBuilder("# NF01 read-only preflight\n\n")
                    .append("- Status: `").append(status()).append("`\n- Code baseline: `").append(md(codeBaseline))
                    .append("`\n- Schema: `").append(md(schema)).append("`\n- Generated: `").append(generatedAt)
                    .append("`\n- Disposal tenant column absent in current schema: `").append(legacyDisposalTenantColumnAbsent).append("`\n\n")
                    .append("## Migration inventory\n\n");
            out.append("- Flyway history present: `").append(migrationHistoryPresent).append("`\n");
            out.append("- Successful migration entries: `").append(appliedMigrations.size()).append("`\n\n");
            out.append("## Tenant candidates\n\n");
            for (String tenant : tenantCandidates) out.append("- `").append(md(tenant)).append("`\n");
            out.append("\n## Findings\n\n| Check | Location | Status | Rows | Samples | Reason |\n|---|---|---:|---:|---|---|\n");
            for (Finding finding : findings) {
                out.append('|').append(md(finding.checkId())).append('|').append(md(finding.location())).append('|')
                        .append(finding.status()).append('|').append(finding.rowCount()).append('|')
                        .append(md(String.join(", ", finding.samples()))).append('|').append(md(finding.reason())).append("|\n");
            }
            out.append("\n## Schema inventory\n\n");
            for (Map.Entry<String, String> entry : tableCollations.entrySet()) {
                out.append("- table `").append(md(entry.getKey())).append("` collation=`")
                        .append(md(entry.getValue())).append("`\n");
            }
            for (String column : columns) out.append("- ").append(md(column)).append('\n');
            out.append("\n## Index inventory\n\n");
            indexes.forEach((name, cols) -> out.append("- `").append(md(name)).append("`: ")
                    .append(md(String.join(", ", cols))).append('\n'));
            out.append("\n## Foreign-key inventory\n\n");
            foreignKeys.forEach((name, links) -> out.append("- `").append(md(name)).append("`: ")
                    .append(md(String.join(", ", links))).append('\n'));
            return out.toString();
        }

        private static StringBuilder field(StringBuilder out, String name, String value) {
            quote(out, name).append(':'); return quote(out, value);
        }
        private static StringBuilder stringArray(StringBuilder out, Iterable<String> values) {
            out.append('['); boolean first = true;
            for (String value : values) { if (!first) out.append(','); first = false; quote(out, value); }
            return out.append(']');
        }
        private static StringBuilder quote(StringBuilder out, String value) {
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                else out.append(c);
            }
            return out.append('"');
        }
        private static String md(String value) { return value.replace("|", "\\|").replace("\n", " ").replace("\r", " "); }
    }

    private record Finding(String checkId, String location, String status, long rowCount, List<String> samples, String reason) { }
}
