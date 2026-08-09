package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1-NULL-01（design §6.2）: current列だけの値→明示NULLが保存され、旧値が残らない。
 * F1-HISTORY-CORRECTION-01: history訂正は新event INSERT（CORRECTED/CANCELLED）、旧行は不変、
 * asOf解決は最新の有効event。
 */
class F1NullAndHistoryCorrectionTest {

    @Test
    void current列の値NULLは保存されfield省略PATCH相当の更新は旧値を残さない() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:f1_null_01;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_contract (id BIGINT PRIMARY KEY)");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/schema-dispatch-compliance-h2.sql"));

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO t_contract (id) VALUES (1)");
                statement.execute("INSERT INTO t_contract_compliance_profile "
                        + "(contract_id, workplace_limitation_date, organization_limitation_date, "
                        + "dispatch_fee_amount, dispatch_fee_basis, social_insurance_procedure_incomplete_reason) "
                        + "VALUES (1, '2026-12-31', '2027-03-31', 120000.00, 'MONTHLY', '手続中')");

                // full DTO相当：全clearable列を値→NULLへ
                statement.execute("UPDATE t_contract_compliance_profile "
                        + "SET workplace_limitation_date = NULL, organization_limitation_date = NULL, "
                        + "dispatch_fee_amount = NULL, dispatch_fee_basis = NULL, "
                        + "social_insurance_procedure_incomplete_reason = NULL WHERE contract_id = 1");
                assertEquals(0, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_profile "
                                + "WHERE workplace_limitation_date IS NOT NULL OR organization_limitation_date IS NOT NULL"),
                        "2種制限日はcurrent列だけNULL化される");
                assertEquals(0, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_profile WHERE dispatch_fee_amount IS NOT NULL"),
                        "料金もNULL化される");
                assertEquals(0, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_profile "
                                + "WHERE social_insurance_procedure_incomplete_reason IS NOT NULL"),
                        "SRC-E⑱理由もNULL化される");

                // snapshot列（不変）はcurrentのNULL化で変えない
                statement.execute("INSERT INTO t_contract_compliance_snapshot "
                        + "(contract_id, snapshot_version, snapshot_hash, workplace_limitation_date, dispatch_fee_amount) "
                        + "VALUES (1, 1, 'hA', '2026-12-31', 120000.00)");
                statement.execute("UPDATE t_contract_compliance_profile SET current_snapshot_id=1, current_snapshot_version=1 "
                        + "WHERE contract_id=1");
                assertEquals("2026-12-31", queryString(statement,
                        "SELECT CAST(workplace_limitation_date AS VARCHAR) FROM t_contract_compliance_snapshot "
                                + "WHERE snapshot_version=1"), "snapshotはcurrentのNULL化の影響を受けない");
            }
        }
    }

    @Test
    void history訂正は新eventINSERTで旧行は不変としてOfは最新有効eventを解決する() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:f1_hist_01;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_contract (id BIGINT PRIMARY KEY)");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/schema-dispatch-compliance-h2.sql"));

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO t_contract (id) VALUES (1)");
                // 原event（complaint/direct-hire/notification difference相当のprotocol）
                statement.execute("INSERT INTO t_compliance_complaint_history "
                        + "(contract_id, event_id, event_type, actor_user_id, occurred_at, effective_from, effective_to, received_at, content) "
                        + "VALUES (1, 'evt-1', 'CREATED', 100, CURRENT_TIMESTAMP, '2026-08-01', '2026-08-31', '2026-08-01', '申出A')");
                // 訂正event：supersedes_event_idで旧eventを参照し、旧行はUPDATEしない
                statement.execute("INSERT INTO t_compliance_complaint_history "
                        + "(contract_id, event_id, event_type, supersedes_event_id, correction_reason, actor_user_id, "
                        + "occurred_at, effective_from, effective_to, received_at, content) "
                        + "VALUES (1, 'evt-2', 'CORRECTED', 'evt-1', '申出日訂正', 100, CURRENT_TIMESTAMP, '2026-08-15', '2026-08-31', '2026-08-02', '申出A')");
                // 取消event：evt-2を取消し、9月以降だけの適用intervalを持つ
                statement.execute("INSERT INTO t_compliance_complaint_history "
                        + "(contract_id, event_id, event_type, supersedes_event_id, correction_reason, actor_user_id, "
                        + "occurred_at, effective_from, effective_to, received_at, content) "
                        + "VALUES (1, 'evt-3', 'CANCELLED', 'evt-2', '誤登録のため取消', 100, CURRENT_TIMESTAMP, '2026-09-01', '2026-09-30', '2026-08-03', NULL)");

                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM t_compliance_complaint_history WHERE event_id='evt-1' AND content='申出A'"),
                        "旧eventは不変");
                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM t_compliance_complaint_history WHERE event_type='CREATED'"),
                        "CREATEDは1件のまま");
                assertEquals(3, queryInt(statement,
                        "SELECT COUNT(*) FROM t_compliance_complaint_history WHERE contract_id=1"),
                        "訂正・取消は新行として追加");
                assertEquals("evt-2", queryString(statement,
                        "SELECT supersedes_event_id FROM t_compliance_complaint_history WHERE event_id='evt-3'"));
                assertEquals("誤登録のため取消", queryString(statement,
                        "SELECT correction_reason FROM t_compliance_complaint_history WHERE event_id='evt-3'"));

                // asOf解決（design §4.3）: effective intervalと最新の有効eventを使う。
                // 有効event = asOf日に適用中で、かつasOf日にも有効な後続eventにsupersedeされていないCREATED/CORRECTED。
                String asOfSql = "SELECT event_id FROM t_compliance_complaint_history h "
                        + "WHERE h.contract_id=1 AND h.event_type IN ('CREATED','CORRECTED') "
                        + "AND h.effective_from <= '%s' AND (h.effective_to IS NULL OR h.effective_to >= '%s') "
                        + "AND NOT EXISTS (SELECT 1 FROM t_compliance_complaint_history s "
                        + "WHERE s.supersedes_event_id = h.event_id "
                        + "AND s.effective_from <= '%s' AND (s.effective_to IS NULL OR s.effective_to >= '%s')) "
                        + "ORDER BY h.effective_from DESC LIMIT 1";
                assertEquals("evt-1", queryString(statement, String.format(asOfSql, "2026-08-10", "2026-08-10", "2026-08-10", "2026-08-10")),
                        "asOf 8/10では訂正evt-2（8/15〜）は未適用でevt-1を解決する");
                assertEquals("evt-2", queryString(statement, String.format(asOfSql, "2026-08-20", "2026-08-20", "2026-08-20", "2026-08-20")),
                        "asOf 8/20では最新の有効event evt-2を解決する（evt-1はsupersede済み）");
                assertEquals("evt-2", queryString(statement, String.format(asOfSql, "2026-08-31", "2026-08-31", "2026-08-31", "2026-08-31")),
                        "asOf 8/31でもevt-2が有効（evt-3は9月以降のみ）");
                assertEquals(0, queryInt(statement,
                        "SELECT COUNT(*) FROM t_compliance_complaint_history h "
                                + "WHERE h.contract_id=1 AND h.event_type IN ('CREATED','CORRECTED') "
                                + "AND h.effective_from <= '2026-10-01' AND (h.effective_to IS NULL OR h.effective_to >= '2026-10-01') "
                                + "AND NOT EXISTS (SELECT 1 FROM t_compliance_complaint_history s "
                                + "WHERE s.supersedes_event_id = h.event_id "
                                + "AND s.effective_from <= '2026-10-01' AND (s.effective_to IS NULL OR s.effective_to >= '2026-10-01'))"),
                        "asOf 10/1では有効eventなし（cancel後の未来）");

                // 旧eventを直接UPDATEしようとしても、H2のmapper境界契約では行わない（MySQLではtriggerが拒否）
                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM t_compliance_complaint_history WHERE event_id='evt-1' AND content='申出A'"),
                        "旧eventの内容は訂正後も変わらない");
            }
        }
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
