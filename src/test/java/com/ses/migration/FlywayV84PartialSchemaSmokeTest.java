package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.ses.test.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1-MYSQL-PARTIAL-SCHEMA-01（design §6.2）:
 * dispatch tableのpresent/absent/old definitionを持つpartial fixtureへV84を適用・再実行し、
 * freshと同じschemaへ収束する（IF NOT EXISTS＋情報スキーマ条件付きALTER/FK）。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayV84PartialSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_dispatch_partial")
            .withUsername("root").withPassword("ses");

    @Test
    void partialschemaのpresent_absent_olddefinitionを検出してfreshへ収束する() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("83").load().migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            // present/absent/old definitionのpartial fixtureを作るため、V84適用前に
            // dispatch tableを除去する（現行V1は統合baselineでdispatch shapeを含むため）。
            // present: m_workplace はそのまま残す（V1/V84で同一shape）
            st.execute("DROP TABLE IF EXISTS t_document_delivery");
            st.execute("DROP TABLE IF EXISTS t_compliance_finding");
            st.execute("DROP TABLE IF EXISTS t_ledger_work_snapshot");
            st.execute("DROP TABLE IF EXISTS t_notification_difference_history");
            st.execute("DROP TABLE IF EXISTS t_direct_hire_dispute_history");
            st.execute("DROP TABLE IF EXISTS t_planned_introduction_history");
            st.execute("DROP TABLE IF EXISTS t_planned_introduction_terms");
            st.execute("DROP TABLE IF EXISTS t_career_consulting_history");
            st.execute("DROP TABLE IF EXISTS t_training_history");
            st.execute("DROP TABLE IF EXISTS t_employment_stability_history");
            st.execute("DROP TABLE IF EXISTS t_compliance_complaint_history");
            st.execute("DROP TABLE IF EXISTS t_compliance_work_calendar");
            st.execute("DROP TABLE IF EXISTS t_compliance_snapshot_operation");
            st.execute("DROP TABLE IF EXISTS t_contract_compliance_worker_state");
            st.execute("DROP TABLE IF EXISTS t_contract_compliance_worker_snapshot");
            st.execute("DROP TABLE IF EXISTS t_contract_compliance_profile");
            st.execute("DROP TABLE IF EXISTS t_contract_compliance_snapshot");
            // old definition: t_document_deliveryを旧shape（新5列なし・FKなし）で事前作成
            // V84実shapeと同じcollation（utf8mb4_unicode_ci）を明示する。DB既定collationのままだと
            // V102のcomposite FK（tenant_id参照）がcollation不一致（MySQL 3780）で失敗する。
            st.executeUpdate("CREATE TABLE t_document_delivery ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',"
                    + "contract_id BIGINT, document_id BIGINT NOT NULL, recipient_contact_id BIGINT,"
                    + "recipient_name_snapshot VARCHAR(200), recipient_email_snapshot VARCHAR(255),"
                    + "delivery_method VARCHAR(30) NOT NULL, delivery_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',"
                    + "delivered_at DATETIME, confirmed_at DATETIME, confirmation_note VARCHAR(1000),"
                    + "idempotency_key VARCHAR(200), version INT NOT NULL DEFAULT 0,"
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "deleted_flag TINYINT NOT NULL DEFAULT 0,"
                    + "UNIQUE KEY uk_document_delivery_idempotency (tenant_id, idempotency_key))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            // absent: t_contract_compliance_snapshot等は未作成のまま
        }

        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("84").load().migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            for (String table : new String[]{
                    "t_contract_compliance_profile", "t_contract_compliance_snapshot",
                    "t_contract_compliance_worker_snapshot", "t_contract_compliance_worker_state",
                    "t_compliance_snapshot_operation", "t_compliance_work_calendar",
                    "t_compliance_complaint_history", "t_employment_stability_history",
                    "t_training_history", "t_career_consulting_history", "t_planned_introduction_terms",
                    "t_planned_introduction_history", "t_direct_hire_dispute_history",
                    "t_notification_difference_history", "t_ledger_work_snapshot",
                    "t_compliance_finding"}) {
                assertEquals(1, queryInt(st, "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name='" + table + "'"),
                        "absent tableがV84で作成されるはず: " + table);
            }
            // old definition → R5列へ収束
            for (String column : new String[]{"document_type", "template_version", "effective_from", "effective_to", "snapshot_hash"}) {
                assertEquals(1, queryInt(st, "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='t_document_delivery' AND column_name='" + column + "'"),
                        "old definition deliveryが新列へ収束するはず: " + column);
            }
            assertEquals(1, queryInt(st, "SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE constraint_schema=DATABASE() AND table_name='t_document_delivery' "
                    + "AND constraint_name='fk_delivery_document' AND constraint_type='FOREIGN KEY'"),
                    "fk_delivery_documentが収束するはず");
            assertEquals(1, queryInt(st, "SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE constraint_schema=DATABASE() AND table_name='t_contract_compliance_profile' "
                    + "AND constraint_name='fk_profile_current_snapshot' AND constraint_type='FOREIGN KEY'"),
                    "current snapshot FKが成立するはず");
            assertEquals(1, queryInt(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='84' AND success=1"));
        }

        // retry：再実行しても無エラーで収束状態を維持し、V84は一度だけ完了
        Flyway retry = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load();
        retry.migrate();
        retry.validate();
        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            assertEquals(1, queryInt(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='84' AND success=1"),
                    "V84は一度だけ完了するはず");
        }
    }

    private int queryInt(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
