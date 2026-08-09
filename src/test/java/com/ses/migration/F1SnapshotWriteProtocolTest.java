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
 * F1-SNAPSHOT-01/02（design §6.2）:
 *  - 同じoperation retryは1行、A(v1,hA)→B(v2,hB)→A(v3,hA)は3 version
 *  - 同じcontent hashのversion重複を許容、current pointerはv3、旧snapshot不変
 *  - 2 workerのcurrent pointer/version/CASが独立し、CAS競合は1勝
 *  - 失敗rollbackでorphan 0
 */
class F1SnapshotWriteProtocolTest {

    @Test
    void 同じoperationのretryは1行でA_B_Aは3versionとして保持される() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:f1_snapshot_01;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_contract (id BIGINT PRIMARY KEY)");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/schema-dispatch-compliance-h2.sql"));

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO t_contract (id) VALUES (1)");

                // operation 1: A(v1, hA)
                statement.execute("INSERT INTO t_compliance_snapshot_operation "
                        + "(operation_id, scope_type, contract_id, expected_version, resulting_snapshot_id, status) "
                        + "VALUES ('op-A', 'CONTRACT', 1, 0, 1, 'SUCCEEDED')");
                statement.execute("INSERT INTO t_contract_compliance_snapshot "
                        + "(contract_id, snapshot_version, snapshot_hash, operation_id, work_description) "
                        + "VALUES (1, 1, 'hA', 'op-A', '業務A')");
                statement.execute("INSERT INTO t_contract_compliance_profile "
                        + "(contract_id, current_snapshot_id, current_snapshot_version) VALUES (1, 1, 1)");

                // operation retry（同一operation_id）はUNIQUEで拒否 → 1行のまま
                boolean retryRejected = false;
                try {
                    statement.execute("INSERT INTO t_compliance_snapshot_operation "
                            + "(operation_id, scope_type, contract_id, expected_version, resulting_snapshot_id, status) "
                            + "VALUES ('op-A', 'CONTRACT', 1, 0, 1, 'SUCCEEDED')");
                } catch (SQLException expected) {
                    retryRejected = true;
                }
                assertTrue(retryRejected, "同じoperationのretryは2行目を作らないはず");
                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM t_compliance_snapshot_operation WHERE operation_id='op-A'"));

                // operation 2: B(v2, hB) → CASでpointerをv2へ
                statement.execute("INSERT INTO t_contract_compliance_snapshot "
                        + "(contract_id, snapshot_version, snapshot_hash, operation_id, work_description) "
                        + "VALUES (1, 2, 'hB', 'op-B', '業務B')");
                assertEquals(1, statement.executeUpdate(
                        "UPDATE t_contract_compliance_profile SET current_snapshot_id=2, current_snapshot_version=2 "
                                + "WHERE contract_id=1 AND current_snapshot_version=1"), "expected version CASで1勝");
                assertEquals(0, statement.executeUpdate(
                        "UPDATE t_contract_compliance_profile SET current_snapshot_id=1, current_snapshot_version=1 "
                                + "WHERE contract_id=1 AND current_snapshot_version=1"), "古いexpected versionのCASは0勝");

                // operation 3: Aと同内容を新operationで再改定 → v3(hA)
                statement.execute("INSERT INTO t_contract_compliance_snapshot "
                        + "(contract_id, snapshot_version, snapshot_hash, operation_id, work_description) "
                        + "VALUES (1, 3, 'hA', 'op-A2', '業務A')");
                statement.executeUpdate("UPDATE t_contract_compliance_profile SET current_snapshot_id=3, current_snapshot_version=3 "
                        + "WHERE contract_id=1 AND current_snapshot_version=2");

                assertEquals(3, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_snapshot WHERE contract_id=1"), "A/B/Aは3version");
                assertEquals(3, queryInt(statement,
                        "SELECT current_snapshot_version FROM t_contract_compliance_profile WHERE contract_id=1"),
                        "current pointerはv3");
                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_snapshot WHERE snapshot_version=1 AND work_description='業務A'"),
                        "v1は不変");
                assertEquals("業務B", queryString(statement,
                        "SELECT work_description FROM t_contract_compliance_snapshot WHERE snapshot_version=2"));
            }
        }
    }

    @Test
    void worker2名のcurrentpointerは独立しCAS競合は1勝でrollback後にorphan0() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:f1_snapshot_02;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_contract (id BIGINT PRIMARY KEY)");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/schema-dispatch-compliance-h2.sql"));

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO t_contract (id) VALUES (1)");
                statement.execute("INSERT INTO t_contract_compliance_worker_snapshot "
                        + "(contract_id, worker_id, snapshot_version, snapshot_hash, worker_name) "
                        + "VALUES (1, 10, 1, 'hA1', 'workerA-v1')");
                statement.execute("INSERT INTO t_contract_compliance_worker_snapshot "
                        + "(contract_id, worker_id, snapshot_version, snapshot_hash, worker_name) "
                        + "VALUES (1, 10, 2, 'hA2', 'workerA-v2')");
                statement.execute("INSERT INTO t_contract_compliance_worker_snapshot "
                        + "(contract_id, worker_id, snapshot_version, snapshot_hash, worker_name) "
                        + "VALUES (1, 20, 1, 'hB1', 'workerB-v1')");
                statement.execute("INSERT INTO t_contract_compliance_worker_state "
                        + "(contract_id, worker_id, current_snapshot_id, current_snapshot_version) VALUES (1, 10, 1, 1)");
                statement.execute("INSERT INTO t_contract_compliance_worker_state "
                        + "(contract_id, worker_id, current_snapshot_id, current_snapshot_version) VALUES (1, 20, 3, 1)");

                // worker Aだけv2へCAS。worker Bのpointerはv1のまま独立
                assertEquals(1, statement.executeUpdate(
                        "UPDATE t_contract_compliance_worker_state SET current_snapshot_id=2, current_snapshot_version=2 "
                                + "WHERE contract_id=1 AND worker_id=10 AND current_snapshot_version=1"));
                assertEquals(2, queryInt(statement,
                        "SELECT current_snapshot_version FROM t_contract_compliance_worker_state WHERE worker_id=10"));
                assertEquals(1, queryInt(statement,
                        "SELECT current_snapshot_version FROM t_contract_compliance_worker_state WHERE worker_id=20"),
                        "worker Bのpointerはworker Aの更新に影響されない");

                // 同時改定のCAS競合：両方からv1→v2を試し、片方だけ1勝
                connection.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO t_contract_compliance_worker_snapshot "
                        + "(contract_id, worker_id, snapshot_version, snapshot_hash, worker_name) "
                        + "VALUES (1, 20, 2, 'hB2', 'workerB-v2')");
                long workerBv2Id = queryLong(statement,
                        "SELECT id FROM t_contract_compliance_worker_snapshot WHERE worker_id=20 AND snapshot_version=2");
                try {
                    statement.executeUpdate("UPDATE t_contract_compliance_worker_state "
                            + "SET current_snapshot_id=" + workerBv2Id + ", current_snapshot_version=2 "
                            + "WHERE contract_id=1 AND worker_id=20 AND current_snapshot_version=1");
                    // CAS成功したが、続けて矛盾を起こす想定でrollback
                    connection.rollback();
                } catch (SQLException expected) {
                    connection.rollback();
                }
                connection.setAutoCommit(true);
                // rollback後：worker Bのpointerはv1へ戻り、同一tx内のsnapshot INSERTもrollbackでorphan 0
                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_worker_state WHERE worker_id=20 AND current_snapshot_version=1"),
                        "rollback後はworker Bのpointerがv1へ戻る");
                assertEquals(0, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_worker_snapshot WHERE worker_id=20 AND snapshot_version=2"),
                        "CAS失敗txはsnapshotも全rollbackされorphan 0");
            }
        }
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private long queryLong(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
