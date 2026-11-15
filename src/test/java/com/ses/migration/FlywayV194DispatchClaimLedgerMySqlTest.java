package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V194（NF08 B2c-1）の実MySQL適用。既存行の既定値、列型、一意制約のNULL複数許容、CHECK制約、
 * 照合索引・監査の存在照会索引の列順、claim IDの {@code UUID_TO_BIN} / {@code BIN_TO_UUID(?, 0)} 往復を確かめる。
 */
@Tag("mysql")
@Testcontainers
class FlywayV194DispatchClaimLedgerMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("nf08_v194").withUsername("root").withPassword("ses");
    static JdbcTemplate jdbc;
    static long legacyA;
    static long legacyB;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("192").load().migrate();
        Long artifact = jdbc.queryForObject("SELECT id FROM m_ai_artifact_version ORDER BY id LIMIT 1", Long.class);
        legacyA = insertLegacyRun("tenant-a", artifact);
        legacyB = insertLegacyRun("tenant-b", artifact);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    }

    @Test
    void V194は成功し既存行をNOT_APPLICABLEと全dispatch列NULLのまま残す() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='194'", Integer.class))
                .isEqualTo(1);
        for (long id : List.of(legacyA, legacyB)) {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM t_ai_recommendation_run WHERE id=?", id);
            assertThat(row.get("dispatch_state")).isEqualTo("NOT_APPLICABLE");
            row.forEach((column, value) -> {
                if (column.startsWith("dispatch_") && !"dispatch_state".equals(column)
                        || "preflight_snapshot_hash".equals(column)) {
                    assertThat(value).as(column).isNull();
                }
            });
        }
    }

    @Test
    void 列型はclaim_ID_16byte_hash_32byte_時刻はマイクロ秒でtenant幅はrunと同じ() {
        assertThat(columnType("t_ai_recommendation_run", "dispatch_claim_id")).isEqualTo("binary(16)");
        for (String hash : List.of("dispatch_nonce_hash", "dispatch_idempotency_hash", "dispatch_binding_hash",
                "preflight_snapshot_hash", "dispatch_request_sha256", "dispatch_config_hash", "dispatch_scope_hash",
                "dispatch_input_hash", "dispatch_approval_digest", "dispatch_card_digest", "dispatch_policy_digest")) {
            assertThat(columnType("t_ai_recommendation_run", hash)).as(hash).isEqualTo("binary(32)");
        }
        for (String time : List.of("dispatch_issued_at", "dispatch_expires_at", "dispatch_claimed_at",
                "dispatch_started_at")) {
            assertThat(columnType("t_ai_recommendation_run", time)).as(time).isEqualTo("datetime(6)");
        }
        assertThat(columnType("t_ai_external_send_cost_entry", "reservation_id")).isEqualTo("binary(16)");
        assertThat(columnType("t_ai_external_send_cost_entry", "tenant_id"))
                .isEqualTo(columnType("t_ai_recommendation_run", "tenant_id"));
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE "
                + "table_schema=DATABASE() AND table_name='t_ai_recommendation_run' AND column_name='dispatch_state'",
                String.class)).isEqualTo("NOT_APPLICABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='t_ai_recommendation_run' AND column_name IN ('dispatch_endpoint_id', "
                + "'dispatch_tls_peer_hash', 'dispatch_observed_at', 'response_received_at', 'response_http_status', "
                + "'response_content_type', 'response_sha256')", Integer.class))
                .as("transport observation・response列は別migration").isZero();
    }

    @Test
    void 照合索引と監査の存在照会索引と一意制約の列順() {
        assertThat(indexColumns("t_ai_recommendation_run", "idx_ai_run_dispatch_reconcile"))
                .containsExactly("tenant_id", "dispatch_state", "status", "dispatch_expires_at", "id");
        assertThat(indexColumns("t_ai_recommendation_run", "uk_ai_run_dispatch_claim")).containsExactly("dispatch_claim_id");
        assertThat(indexColumns("t_ai_recommendation_run", "uk_ai_run_dispatch_nonce"))
                .containsExactly("tenant_id", "dispatch_nonce_hash");
        assertThat(indexColumns("t_ai_recommendation_run", "uk_ai_run_dispatch_idem"))
                .containsExactly("tenant_id", "dispatch_idempotency_hash");
        assertThat(indexColumns("t_audit_log", "idx_audit_dispatch_key"))
                .containsExactly("idempotency_key", "application_code", "id");
        assertThat(indexColumns("t_ai_external_send_cost_bucket", "uk_ai_cost_bucket"))
                .containsExactly("cost_scope", "bucket_type", "bucket_key");
        assertThat(indexColumns("t_ai_external_send_cost_bucket", "idx_ai_cost_clock"))
                .containsExactly("cost_scope", "clock_high_water_at");
        assertThat(indexColumns("t_ai_external_send_cost_entry", "uk_ai_cost_entry_reservation"))
                .containsExactly("reservation_id");
        List<Map<String, Object>> audit = jdbc.queryForList("EXPLAIN SELECT id FROM t_audit_log "
                + "WHERE idempotency_key=? AND application_code=? ORDER BY id LIMIT 1", "v194-legacy-key",
                "AI_EXTERNAL_SEND_ATTEMPT");
        assertThat(String.valueOf(audit.get(0).get("possible_keys"))).contains("idx_audit_dispatch_key");
        List<Map<String, Object>> reconcile = jdbc.queryForList("EXPLAIN SELECT id FROM t_ai_recommendation_run "
                + "WHERE tenant_id=? AND dispatch_state='READY' AND status='PENDING' AND dispatch_expires_at < NOW(6) "
                + "ORDER BY dispatch_expires_at, id LIMIT 100", "tenant-a");
        assertThat(String.valueOf(reconcile.get(0).get("possible_keys"))).contains("idx_ai_run_dispatch_reconcile");
    }

    @Test
    void 一意制約はNULLを複数許しclaim_IDは全tenantで一意nonceとidempotencyはtenant内で一意() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_ai_recommendation_run WHERE dispatch_claim_id IS NULL "
                + "AND id IN (?, ?)", Integer.class, legacyA, legacyB)).isEqualTo(2);
        String claimId = UUID.randomUUID().toString();
        jdbc.update("UPDATE t_ai_recommendation_run SET dispatch_claim_id=UUID_TO_BIN(?, 0) WHERE id=?", claimId, legacyA);
        assertThatThrownBy(() -> jdbc.update("UPDATE t_ai_recommendation_run SET dispatch_claim_id=UUID_TO_BIN(?, 0) "
                + "WHERE id=?", claimId, legacyB)).isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE t_ai_recommendation_run SET dispatch_nonce_hash=UNHEX(REPEAT('11', 32)) WHERE id IN (?, ?)",
                legacyA, legacyB);
        long otherA = insertLegacyRun("tenant-a",
                jdbc.queryForObject("SELECT id FROM m_ai_artifact_version ORDER BY id LIMIT 1", Long.class));
        assertThatThrownBy(() -> jdbc.update("UPDATE t_ai_recommendation_run SET dispatch_nonce_hash="
                + "UNHEX(REPEAT('11', 32)) WHERE id=?", otherA)).isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE t_ai_recommendation_run SET dispatch_claim_id=NULL, dispatch_nonce_hash=NULL "
                + "WHERE id IN (?, ?)", legacyA, legacyB);
    }

    @Test
    void CHECK制約は未知の状態と負の合計と1円未満の予約と未知のbucket種別を拒否する() {
        assertThatThrownBy(() -> jdbc.update("UPDATE t_ai_recommendation_run SET dispatch_state='SENT' WHERE id=?",
                legacyA)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO t_ai_external_send_cost_bucket (cost_scope, bucket_type, "
                + "bucket_key) VALUES ('v194', 'WEEK', '2026-W46')")).isInstanceOf(DataAccessException.class);
        jdbc.update("INSERT INTO t_ai_external_send_cost_bucket (cost_scope, bucket_type, bucket_key) "
                + "VALUES ('v194', 'DAY', '2026-11-15')");
        assertThatThrownBy(() -> jdbc.update("UPDATE t_ai_external_send_cost_bucket SET committed_yen=-1 "
                + "WHERE cost_scope='v194'")).isInstanceOf(DataAccessException.class);
        String entry = "INSERT INTO t_ai_external_send_cost_entry (reservation_id, tenant_id, run_id, trace_id, "
                + "cost_scope, day_key, month_key, decision_id, approval_digest, reserved_yen, entry_state, created_at) "
                + "VALUES (UUID_TO_BIN(?, 0), 'tenant-a', NULL, ?, 'v194', '2026-11-15', '2026-11', 'NF08-EXT', "
                + "UNHEX(REPEAT('ab', 32)), ?, ?, '2026-11-15 01:00:00.000001')";
        assertThatThrownBy(() -> jdbc.update(entry, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0,
                "RESERVED")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(entry, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1,
                "SENT")).isInstanceOf(DataAccessException.class);
        String reservation = UUID.randomUUID().toString();
        jdbc.update(entry, reservation, UUID.randomUUID().toString(), 1, "RESERVED");
        assertThatThrownBy(() -> jdbc.update(entry, reservation, UUID.randomUUID().toString(), 1, "RESERVED"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void claim_IDは小文字36文字表記とUUID_TO_BINのswap_flag0で往復しbyte順を入れ替えない() {
        String text = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
        assertThat(jdbc.queryForObject("SELECT HEX(UUID_TO_BIN(?, 0))", String.class, text))
                .isEqualToIgnoringCase("0f1e2d3c4b5a69788796a5b4c3d2e1f0");
        assertThat(jdbc.queryForObject("SELECT BIN_TO_UUID(UUID_TO_BIN(?, 0), 0)", String.class, text)).isEqualTo(text);
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(BIN_TO_UUID(UUID_TO_BIN(?, 0), 0))", Integer.class, text))
                .isEqualTo(36);
    }

    private static long insertLegacyRun(String tenant, Long artifact) {
        String trace = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO t_ai_recommendation_run (tenant_id, trace_id, use_case, artifact_version_id, "
                + "input_hash, status, status_version) VALUES (?, ?, 'MANAGEMENT_COPILOT', ?, REPEAT('0', 64), "
                + "'SUCCEEDED', 1)", tenant, trace, artifact);
        return jdbc.queryForObject("SELECT id FROM t_ai_recommendation_run WHERE trace_id=?", Long.class, trace);
    }

    private static String columnType(String table, String column) {
        return jdbc.queryForObject("SELECT LOWER(COLUMN_TYPE) FROM information_schema.columns WHERE "
                + "table_schema=DATABASE() AND table_name=? AND column_name=?", String.class, table, column);
    }

    private static List<String> indexColumns(String table, String index) {
        return jdbc.queryForList("SELECT LOWER(column_name) FROM information_schema.statistics WHERE "
                + "table_schema=DATABASE() AND table_name=? AND index_name=? ORDER BY seq_in_index",
                String.class, table, index);
    }
}
