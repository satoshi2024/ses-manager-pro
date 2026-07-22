package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class FlywayRepairRunbookTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void 旧prod履歴V10のチェックサム不一致をリペアしてRepeatableを実行しログイン検証する() throws Exception {
        // 1. まずV1からV9までを適用
        Flyway flywayV1V9 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("9")
                .load();
        flywayV1V9.migrate();

        // 2. 旧prod V10を手動で履歴に書き込む（旧V10は異なるchecksumで、実スキーマはV10のインデックスを持たない状態を再現）
        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) " +
                    "VALUES (10, '10', 'update admin password bcrypt', 'SQL', 'V10__update_admin_password_bcrypt.sql', 123456789, 'ses', 10, 1)");
            
            // Baseline 9の状態で uk_work_record_layer があるように設定（beforeMigrate相当の適用前状態）
            st.execute("ALTER TABLE t_bp_payment ADD COLUMN layer_order INT NOT NULL DEFAULT 1");
            st.execute("ALTER TABLE t_bp_payment ADD UNIQUE KEY uk_work_record_layer (work_record_id, layer_order)");
        }

        // 3. この状態でマイグレーションしようとすると失敗するはず（Validateエラー）
        Flyway flywayRepair = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration", "classpath:db/migration-prod")
                .load();
        
        // Assert validate fails (or we just repair directly)
        flywayRepair.repair(); // Runbook: flyway repair
        flywayRepair.migrate(); // migrateを実行するとLegacyDatabaseFlywayCallbackが補償を行う

        // 4. Repeatable migration の検証
        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT password FROM sys_user WHERE username = 'admin'")) {
                assertTrue(rs.next(), "adminユーザーが存在すること");
                String hash = rs.getString("password");
                BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
                assertTrue(encoder.matches("admin123", hash), "ハッシュが admin123 と一致すること");
            }

            // 5. Schema Compensation の検証
            try (ResultSet rs = st.executeQuery("SELECT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_bp_payment' AND index_name = 'uk_work_record_layer'")) {
                assertTrue(!rs.next(), "uk_work_record_layer が削除されていること");
            }
            try (ResultSet rs = st.executeQuery("SELECT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_bp_payment' AND index_name = 'idx_bp_payment_work_record'")) {
                assertTrue(rs.next(), "idx_bp_payment_work_record が作成されていること");
            }
        }
    }
}
