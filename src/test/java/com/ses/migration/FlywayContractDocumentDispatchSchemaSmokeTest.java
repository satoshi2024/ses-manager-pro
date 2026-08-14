package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HFP-02-02: V109（t_contract_document のadditive schema）をfresh/legacy両shapeで検証する。
 *
 * <ul>
 *   <li>fresh: 空DBから全migrationを通しで適用し、追加列・索引・default値を確認</li>
 *   <li>legacy: 公開済みlatest(V102)実形状へlegacy行を入れ、V109適用後も
 *       既存のlocal PDF/CloudSign IDが保持され、新列が安全なdefaultで埋まることを確認</li>
 *   <li>V109のchecksumがflyway historyに記録されること</li>
 * </ul>
 *
 * <p>採番: S12〜S17 が V103〜V108 を予約済みのため、HFP-02 は V109（実在latest V102 の次で、
 * 全予約の次）を使用する。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayContractDocumentDispatchSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> FRESH = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_cd_fresh")
            .withUsername("root")
            .withPassword("ses");

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> LEGACY = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_cd_legacy")
            .withUsername("root")
            .withPassword("ses");

    private static final String[] NEW_COLUMNS = {
            "signed_pdf_sha256", "certificate_sha256",
            "signed_archive_document_id", "certificate_archive_document_id",
            "cloudsign_participant_id", "cloudsign_status", "dispatch_state",
            "operation_id", "send_payload_sha256", "dispatch_attempt_count",
            "next_attempt_at", "claimed_at", "claim_owner",
            "last_provider_error_code", "version"
    };

    @Test
    void V109は空DBからの全migration適用で追加列と索引を成立させる() throws Exception {
        Flyway.configure()
                .dataSource(FRESH.getJdbcUrl(), FRESH.getUsername(), FRESH.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = FRESH.createConnection(""); Statement st = conn.createStatement()) {
            for (String column : NEW_COLUMNS) {
                assertColumnExists(st, "t_contract_document", column);
            }
            assertIndexExists(st, "t_contract_document", "idx_contract_doc_dispatch");
            assertIndexExists(st, "t_contract_document", "idx_contract_doc_operation");
            // dispatch_state / dispatch_attempt_count / version のdefaultが安全側であること
            assertTrue(queryInt(st, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract_document' "
                    + "AND column_name='dispatch_state' AND is_nullable='NO' "
                    + "AND column_default='NONE'") == 1, "dispatch_stateはNOT NULL DEFAULT 'NONE'");
            assertTrue(queryInt(st, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract_document' "
                    + "AND column_name='version' AND column_default='0'") == 1,
                    "versionはNOT NULL DEFAULT 0");
            assertTrue(queryInt(st, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract_document' "
                    + "AND column_name='dispatch_attempt_count' AND column_default='0'") == 1,
                    "dispatch_attempt_countはNOT NULL DEFAULT 0");

            // fresh適用時のchecksum記録
            try (ResultSet rs = st.executeQuery(
                    "SELECT success, checksum FROM flyway_schema_history "
                            + "WHERE version = '109' ORDER BY installed_rank DESC LIMIT 1")) {
                assertTrue(rs.next(), "Flyway historyにV109が存在するはず");
                assertEquals(1, rs.getInt("success"), "V109はsuccessであるはず");
                org.junit.jupiter.api.Assertions.assertNotNull(rs.getObject("checksum"),
                        "V109のchecksumが記録されるはず");
            }
        }
    }

    @Test
    void V109はV102実形状のlegacy行を保持し安全なdefaultで埋める() throws Exception {
        // 公開済みlatest(V102)実形状を構築
        Flyway.configure()
                .dataSource(LEGACY.getJdbcUrl(), LEGACY.getUsername(), LEGACY.getPassword())
                .locations("classpath:db/migration")
                .target("102")
                .load()
                .migrate();

        String sourceHash = "a".repeat(64);
        try (Connection conn = LEGACY.createConnection(""); Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO m_contract_template "
                    + "(name, contract_type, html_content, version, active_flag) "
                    + "VALUES ('smoke-template', '派遣', '<p>x</p>', 1, 1)");
            st.executeUpdate("INSERT INTO t_contract_document "
                    + "(contract_id, template_id, template_version, rendered_html, pdf_path, pdf_sha256, "
                    + "cloudsign_document_id, cloudsign_file_id, status, recipient_name, recipient_email, "
                    + "signed_pdf_path, certificate_path, error_message) "
                    + "SELECT c.id, t.id, 1, '<p>legacy</p>', '/uploads/contracts/legacy.pdf', '"
                    + sourceHash + "', '0123456789abcdef0123456789abcdef01', 'f-legacy', '先方確認中', "
                    + "'マスク宛先', 'masked@example.invalid', '/uploads/contracts/signed-legacy.pdf', "
                    + "'/uploads/contracts/cert-legacy.dat', NULL "
                    + "FROM t_contract c, m_contract_template t WHERE c.contract_no='CON-2026-0001' LIMIT 1");
        }

        // V109適用
        Flyway.configure()
                .dataSource(LEGACY.getJdbcUrl(), LEGACY.getUsername(), LEGACY.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = LEGACY.createConnection(""); Statement st = conn.createStatement()) {
            for (String column : NEW_COLUMNS) {
                assertColumnExists(st, "t_contract_document", column);
            }
            // legacy行が保持され、新列が安全なdefaultで埋まる
            try (ResultSet rs = st.executeQuery(
                    "SELECT pdf_path, pdf_sha256, cloudsign_document_id, cloudsign_file_id, status, "
                            + "dispatch_state, dispatch_attempt_count, version, signed_pdf_sha256, "
                            + "cloudsign_status "
                            + "FROM t_contract_document WHERE recipient_email='masked@example.invalid'")) {
                assertTrue(rs.next(), "legacy行が保持されるはず");
                assertEquals("/uploads/contracts/legacy.pdf", rs.getString("pdf_path"),
                        "既存pdf_pathは保持される");
                assertEquals(sourceHash, rs.getString("pdf_sha256"), "既存source hashは保持される");
                assertEquals("0123456789abcdef0123456789abcdef01", rs.getString("cloudsign_document_id"),
                        "既存CloudSign IDは保持される");
                assertEquals("先方確認中", rs.getString("status"), "既存statusは保持される");
                assertEquals("NONE", rs.getString("dispatch_state"), "新列はdefault NONE");
                assertEquals(0, rs.getInt("dispatch_attempt_count"), "新列はdefault 0");
                assertEquals(0, rs.getInt("version"), "新列はdefault 0");
                assertEquals(null, rs.getObject("signed_pdf_sha256"), "新列はNULL初期化");
                assertEquals(null, rs.getObject("cloudsign_status"), "新列はNULL初期化");
            }
        }
    }

    private void assertColumnExists(Statement st, String table, String column) throws Exception {
        assertTrue(queryInt(st, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='" + column + "'") == 1,
                table + "." + column + "が存在するはず");
    }

    private void assertIndexExists(Statement st, String table, String index) throws Exception {
        assertTrue(queryInt(st, "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND index_name='" + index + "'") > 0,
                table + "." + index + "が存在するはず");
    }

    private int queryInt(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
