package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * RB-QUIESCE BWQ-001（BWQ-AC-001-01・02）: backup の書込み静止の信号表（V202）と、H2 の試験 schema の2つの定義
 * （{@code schema-backup-quiesce-h2.sql}・{@code engineer-schema-h2.sql} の V202 の節）が、列名・型・NULL 可否・生成式・主キー・
 * 一意索引・索引・CHECK・FK で一致することを Docker なしで固定する。H2 の上での負例（有効な要求の2件目・生成列への書込み・
 * CHECK の外の値）も確かめる。実 MySQL 8.4 での負例は {@code FlywayProdFreshInstallMySqlTest}（mysql shard 3）が確かめる。
 */
class BackupQuiesceSchemaContractTest {

    private static final Path V202 = Path.of("src/main/resources/db/migration/V202__backup_write_quiesce_signal.sql");
    private static final Path H2_VARIANT = Path.of("src/test/resources/sql/schema-backup-quiesce-h2.sql");
    private static final Path ENGINEER_SCHEMA = Path.of("src/test/resources/sql/engineer-schema-h2.sql");
    private static final Path TEST_CONFIG = Path.of("src/test/resources/application-test.yml");
    private static final List<String> TABLES =
            List.of("sys_backup_quiesce_request", "sys_backup_quiesce_instance", "sys_backup_quiesce_ack");
    private static final String V202_SECTION_MARKER = "-- V202（backup の書込み静止の信号表";

    // ---------------------------------------------------------------- 定義の一致

    @Test
    void V202とH2の2つの定義は列_型_NULL可否_生成式_索引_CHECK_FKが一致する() throws Exception {
        Map<String, TableShape> mysql = parse(read(V202));
        Map<String, TableShape> h2 = parse(read(H2_VARIANT));
        Map<String, TableShape> engineer = parse(engineerSchemaSection());

        assertThat(mysql.keySet()).as("V202 の表").containsExactlyElementsOf(TABLES);
        assertThat(h2).as("schema-backup-quiesce-h2.sql").isEqualTo(mysql);
        assertThat(engineer).as("engineer-schema-h2.sql の V202 の節").isEqualTo(mysql);
        for (String table : TABLES) {
            List<String> order = new ArrayList<>(mysql.get(table).columns().keySet());
            assertThat(new ArrayList<>(h2.get(table).columns().keySet())).as("列の順: " + table).isEqualTo(order);
            assertThat(new ArrayList<>(engineer.get(table).columns().keySet())).as("列の順: " + table).isEqualTo(order);
        }

        TableShape request = mysql.get("sys_backup_quiesce_request");
        assertThat(request.columns().get("active_slot"))
                .isEqualTo(new Column("TINYINT", true, "CASEWHENSTATUS='REQUESTED'THEN1ELSENULLEND", false));
        assertThat(request.uniqueKeys()).containsEntry("uk_bq_request_active", List.of("active_slot"))
                .containsEntry("uk_bq_request_token", List.of("request_token"));
        assertThat(mysql.get("sys_backup_quiesce_ack").foreignKeys()).containsEntry("fk_bq_ack_request",
                "(REQUEST_ID)REFERENCESSYS_BACKUP_QUIESCE_REQUEST(ID)ONDELETECASCADE");
        for (TableShape table : mysql.values()) {
            for (Map.Entry<String, Column> column : table.columns().entrySet()) {
                if (column.getKey().endsWith("_at") || column.getKey().equals("hold_until")) {
                    assertThat(column.getValue().type()).as("時刻の列は UTC の DATETIME(6): " + column.getKey())
                            .isEqualTo("DATETIME(6)");
                }
            }
            assertThat(table.columns()).as("tenant 列を持たない").doesNotContainKey("tenant_id");
        }
    }

    @Test
    void V202は時刻をDEFAULTで書かずtriggerとroutineを作らずSTOREDの生成列を持つ() throws Exception {
        // 表の既定の文字集合（ENGINE=InnoDB DEFAULT CHARSET=…）は列の DEFAULT ではないため除いて調べる
        String sql = withoutComments(read(V202)).toUpperCase(Locale.ROOT).replace(" DEFAULT CHARSET=", " CHARSET=");

        assertThat(sql).doesNotContain("NOW(").doesNotContain("CURRENT_TIMESTAMP").doesNotContain("LOCALTIMESTAMP")
                .doesNotContain("SYSDATE").doesNotContain("DEFAULT").doesNotContain("TRIGGER")
                .doesNotContain("PROCEDURE").doesNotContain("FUNCTION");
        assertThat(sql).contains("THEN 1 ELSE NULL END) STORED");
        assertThat(read(V202)).contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT=");
    }

    @Test
    void H2のreplayはV201の変種の後にV202の変種を流す() throws Exception {
        String replay = read(TEST_CONFIG);
        int v201 = replay.indexOf("- classpath:sql/schema-privacy-status-check-h2.sql");
        int v202 = replay.indexOf("- classpath:sql/schema-backup-quiesce-h2.sql");

        assertThat(v201).isPositive();
        assertThat(v202).as("V201 の変種の後").isGreaterThan(v201);
        assertThat(replay).doesNotContain("classpath:db/migration/V202__");
        String engineer = engineerSchemaSection();
        assertThat(engineer.indexOf("DROP TABLE IF EXISTS sys_backup_quiesce_ack"))
                .as("ack → instance → request の順に消す")
                .isLessThan(engineer.indexOf("DROP TABLE IF EXISTS sys_backup_quiesce_instance"));
        assertThat(engineer.indexOf("DROP TABLE IF EXISTS sys_backup_quiesce_instance"))
                .isLessThan(engineer.indexOf("DROP TABLE IF EXISTS sys_backup_quiesce_request"));
    }

    // ---------------------------------------------------------------- H2 の上での負例

    @Test
    void H2では有効な要求は1件だけで生成列へ書けずRELEASEDにすると次を登録できる() throws Exception {
        try (Connection connection = h2(); Statement st = connection.createStatement()) {
            st.executeUpdate(request("00000000000000000000000000000001", "ONLINE", "CHECKPOINT", "REQUESTED"));
            assertThat(scalar(st, "SELECT active_slot FROM sys_backup_quiesce_request WHERE id = 1")).isEqualTo(1L);

            SQLException duplicate = catchThrowableOfType(() -> st.executeUpdate(
                    request("00000000000000000000000000000002", "ONLINE", "FULL", "REQUESTED")), SQLException.class);
            assertThat((Throwable) duplicate).as("有効な要求の2件目").isNotNull();
            assertThat(duplicate.getSQLState()).isEqualTo("23505");
            assertThat(duplicate.getMessage()).containsIgnoringCase("uk_bq_request_active");

            for (String writeSlot : List.of(
                    "INSERT INTO sys_backup_quiesce_request (request_token, mode, job_kind, status, active_slot, "
                            + "lease_seconds, requested_by, protocol_version, requested_at, lease_expires_at) VALUES "
                            + "('00000000000000000000000000000003', 'ONLINE', 'FULL', 'RELEASED', 1, 60, 'h2', 1, "
                            + "TIMESTAMP '2026-11-21 00:00:00', TIMESTAMP '2026-11-21 00:01:00')",
                    "INSERT INTO sys_backup_quiesce_request (request_token, mode, job_kind, status, active_slot, "
                            + "lease_seconds, requested_by, protocol_version, requested_at, lease_expires_at) VALUES "
                            + "('00000000000000000000000000000004', 'ONLINE', 'FULL', 'REQUESTED', NULL, 60, 'h2', 1, "
                            + "TIMESTAMP '2026-11-21 00:00:00', TIMESTAMP '2026-11-21 00:01:00')",
                    "UPDATE sys_backup_quiesce_request SET active_slot = NULL")) {
                SQLException generated = catchThrowableOfType(() -> st.executeUpdate(writeSlot), SQLException.class);
                assertThat((Throwable) generated).as("active_slot へ値を書く文は拒否される: " + writeSlot).isNotNull();
                assertThat(generated.getErrorCode()).as(generated.getMessage()).isEqualTo(90154);
            }
            assertThat(scalar(st, "SELECT COUNT(*) FROM sys_backup_quiesce_request "
                    + "WHERE status = 'REQUESTED' AND active_slot IS NULL")).as("REQUESTED で枠が NULL の行は無い").isZero();

            st.executeUpdate("UPDATE sys_backup_quiesce_request SET status = 'RELEASED', release_reason = 'COMPLETED', "
                    + "released_at = TIMESTAMP '2026-11-21 00:02:00' WHERE id = 1");
            assertThat(scalar(st, "SELECT COUNT(*) FROM sys_backup_quiesce_request WHERE active_slot IS NULL"))
                    .isEqualTo(1L);
            assertThat(st.executeUpdate(request("00000000000000000000000000000005", "APP_STOPPED", "FULL", "REQUESTED")))
                    .as("RELEASED にすると次の要求を登録できる").isEqualTo(1);
        }
    }

    @Test
    void H2ではCHECKの外の状態と理由の値が拒否される() throws Exception {
        try (Connection connection = h2(); Statement st = connection.createStatement()) {
            st.executeUpdate(request("00000000000000000000000000000001", "ONLINE", "CHECKPOINT", "RELEASED"));
            st.executeUpdate(instance("00000000-0000-4000-8000-000000000001", "RUNNING", "OPEN"));
            st.executeUpdate("INSERT INTO sys_backup_quiesce_ack (request_id, instance_id, state, scheduler_state, "
                    + "drain_millis, hold_until, observed_app_hosts, observed_at, acked_at) VALUES (1, "
                    + "'00000000-0000-4000-8000-000000000001', 'QUIESCED', 'PAUSED', 0, TIMESTAMP '2026-11-21 00:01:00', "
                    + "'10.0.0.1=1', TIMESTAMP '2026-11-21 00:00:00', TIMESTAMP '2026-11-21 00:00:00')");

            Map<String, String> violations = new LinkedHashMap<>();
            violations.put("ck_bq_request_status",
                    request("00000000000000000000000000000002", "ONLINE", "FULL", "PENDING"));
            violations.put("ck_bq_request_mode",
                    request("00000000000000000000000000000003", "OFFLINE", "FULL", "RELEASED"));
            violations.put("ck_bq_request_kind",
                    request("00000000000000000000000000000004", "ONLINE", "DIFF", "RELEASED"));
            violations.put("ck_bq_request_reason",
                    "UPDATE sys_backup_quiesce_request SET release_reason = 'MANUAL' WHERE id = 1");
            violations.put("ck_bq_instance_status", instance("00000000-0000-4000-8000-000000000002", "LOST", "OPEN"));
            violations.put("ck_bq_instance_gate", instance("00000000-0000-4000-8000-000000000003", "RUNNING", "PAUSED"));
            violations.put("ck_bq_ack_state", "UPDATE sys_backup_quiesce_ack SET state = 'OPENED'");
            violations.put("ck_bq_ack_scheduler", "UPDATE sys_backup_quiesce_ack SET scheduler_state = 'RUNNING'");
            violations.put("ck_bq_ack_refuse", "UPDATE sys_backup_quiesce_ack SET refuse_reason = 'BUSY'");
            violations.put("ck_bq_ack_resume", "UPDATE sys_backup_quiesce_ack SET resume_reason = 'MANUAL'");
            for (Map.Entry<String, String> violation : violations.entrySet()) {
                SQLException rejected = catchThrowableOfType(() -> st.executeUpdate(violation.getValue()),
                        SQLException.class);
                assertThat((Throwable) rejected).as(violation.getKey()).isNotNull();
                assertThat(rejected.getSQLState()).as(rejected.getMessage()).isEqualTo("23513");
                assertThat(rejected.getMessage()).containsIgnoringCase(violation.getKey());
            }

            st.executeUpdate("DELETE FROM sys_backup_quiesce_request WHERE id = 1");
            assertThat(scalar(st, "SELECT COUNT(*) FROM sys_backup_quiesce_ack")).as("CASCADE").isZero();
        }
    }

    // ---------------------------------------------------------------- 構文の読取り

    record Column(String type, boolean nullable, String generated, boolean autoIncrement) {
    }

    record TableShape(Map<String, Column> columns, List<String> primaryKey, Map<String, List<String>> uniqueKeys,
                      Map<String, List<String>> indexes, Map<String, String> checks, Map<String, String> foreignKeys) {
    }

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "CREATE\\s+INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s+ON\\s+(\\w+)\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("\\s+COMMENT\\s+'[^']*'", Pattern.CASE_INSENSITIVE);

    static Map<String, TableShape> parse(String script) {
        String sql = withoutComments(script);
        Map<String, TableShape> tables = new LinkedHashMap<>();
        Matcher create = CREATE_TABLE.matcher(sql);
        while (create.find()) {
            String name = create.group(1).toLowerCase(Locale.ROOT);
            int close = matchingParen(sql, create.end() - 1);
            Map<String, Column> columns = new LinkedHashMap<>();
            List<String> primaryKey = new ArrayList<>();
            Map<String, List<String>> uniqueKeys = new TreeMap<>();
            Map<String, List<String>> indexes = new TreeMap<>();
            Map<String, String> checks = new TreeMap<>();
            Map<String, String> foreignKeys = new TreeMap<>();
            for (String element : splitTopLevel(sql.substring(create.end(), close))) {
                String item = COMMENT.matcher(element.trim()).replaceAll("").trim();
                String upper = item.toUpperCase(Locale.ROOT);
                Matcher constraint = Pattern.compile("^CONSTRAINT\\s+(\\w+)\\s+(.*)$", Pattern.CASE_INSENSITIVE
                        | Pattern.DOTALL).matcher(item);
                if (upper.startsWith("PRIMARY KEY")) {
                    primaryKey.addAll(columnsIn(item));
                } else if (upper.startsWith("UNIQUE KEY")) {
                    uniqueKeys.put(nameAfter(item, 2), columnsIn(item));
                } else if (upper.startsWith("KEY ") || upper.startsWith("INDEX ")) {
                    indexes.put(nameAfter(item, 1), columnsIn(item));
                } else if (constraint.matches()) {
                    String constraintName = constraint.group(1).toLowerCase(Locale.ROOT);
                    String body = constraint.group(2).trim();
                    String bodyUpper = body.toUpperCase(Locale.ROOT);
                    if (bodyUpper.startsWith("UNIQUE")) {
                        uniqueKeys.put(constraintName, columnsIn(body));
                    } else if (bodyUpper.startsWith("CHECK")) {
                        checks.put(constraintName, normalize(body.substring("CHECK".length())));
                    } else if (bodyUpper.startsWith("FOREIGN KEY")) {
                        foreignKeys.put(constraintName, normalize(body.substring("FOREIGN KEY".length())));
                    } else {
                        throw new IllegalStateException("解釈できない制約: " + item);
                    }
                } else {
                    String[] head = item.split("\\s+", 3);
                    String rest = head.length > 2 ? head[2].toUpperCase(Locale.ROOT) : "";
                    String generated = null;
                    int as = rest.indexOf("GENERATED ALWAYS AS");
                    if (as >= 0) {
                        int open = rest.indexOf('(', as);
                        generated = normalize(rest.substring(open + 1, matchingParen(rest, open)));
                    }
                    boolean notNull = rest.contains("NOT NULL") || rest.contains("PRIMARY KEY");
                    columns.put(head[0].toLowerCase(Locale.ROOT), new Column(head[1].toUpperCase(Locale.ROOT),
                            !notNull, generated, rest.contains("AUTO_INCREMENT")));
                }
            }
            tables.put(name, new TableShape(columns, primaryKey, uniqueKeys, indexes, checks, foreignKeys));
        }
        Matcher index = CREATE_INDEX.matcher(sql);
        while (index.find()) {
            TableShape table = tables.get(index.group(2).toLowerCase(Locale.ROOT));
            assertThat(table).as("索引の表: " + index.group()).isNotNull();
            table.indexes().put(index.group(1).toLowerCase(Locale.ROOT), columnsIn("(" + index.group(3) + ")"));
        }
        return tables;
    }

    private static String nameAfter(String item, int words) {
        return item.trim().split("\\s+")[words].replaceAll("\\(.*$", "").toLowerCase(Locale.ROOT);
    }

    private static List<String> columnsIn(String item) {
        int open = item.indexOf('(');
        List<String> columns = new ArrayList<>();
        for (String column : item.substring(open + 1, matchingParen(item, open)).split(",")) {
            columns.add(column.trim().toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException("括弧が閉じていない: " + text.substring(open));
    }

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        return parts;
    }

    /** 大文字にし、空白を全て除く（改行・字下げの違いを無視して式を比べる）。 */
    private static String normalize(String expression) {
        return expression.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String withoutComments(String sql) {
        return Pattern.compile("--[^\\n]*").matcher(sql).replaceAll("");
    }

    private static String engineerSchemaSection() throws Exception {
        String engineer = read(ENGINEER_SCHEMA);
        int start = engineer.indexOf(V202_SECTION_MARKER);
        assertThat(start).as("engineer-schema-h2.sql に V202 の節がある").isNotNegative();
        return engineer.substring(start);
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- H2 の helper

    private static Connection h2() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:bq_contract_"
                + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL", "sa", "");
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema-backup-quiesce-h2.sql"));
        return connection;
    }

    private static String request(String token, String mode, String jobKind, String status) {
        return "INSERT INTO sys_backup_quiesce_request (request_token, mode, job_kind, status, lease_seconds, requested_by, "
                + "protocol_version, requested_at, lease_expires_at) VALUES ('" + token + "', '" + mode + "', '" + jobKind
                + "', '" + status + "', 60, 'h2-contract', 1, TIMESTAMP '2026-11-21 00:00:00', "
                + "TIMESTAMP '2026-11-21 00:01:00')";
    }

    private static String instance(String instanceId, String status, String gateState) {
        return "INSERT INTO sys_backup_quiesce_instance (instance_id, host_name, db_client_host, db_connection_id, "
                + "app_commit, protocol_version, poll_interval_ms, heartbeat_interval_ms, fence_after_ms, "
                + "max_hold_checkpoint_ms, max_hold_full_ms, scheduling_enabled, status, gate_state, started_at, "
                + "last_beat_at) VALUES ('" + instanceId + "', 'h2-host', '10.0.0.1', 1, 'unknown', 1, 1000, 5000, 20000, "
                + "300000, 3600000, 0, '" + status + "', '" + gateState + "', TIMESTAMP '2026-11-21 00:00:00', "
                + "TIMESTAMP '2026-11-21 00:00:00')";
    }

    private static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getLong(1);
        }
    }
}
