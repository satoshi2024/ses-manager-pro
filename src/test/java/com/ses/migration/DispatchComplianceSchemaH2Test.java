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

/** T061 F1のH2 replay shapeと明示NULL/一意制約を確認する。 */
class DispatchComplianceSchemaH2Test {

    @Test
    void H2でprofileのsnapshotとfinding一意性を再現できる() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:t061_dispatch;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_contract (id BIGINT PRIMARY KEY)");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/schema-dispatch-compliance-h2.sql"));

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO t_contract (id) VALUES (1)");
                statement.execute("INSERT INTO m_workplace (customer_id, name, address) "
                        + "VALUES (1, 'H2 workplace', '旧住所')");
                statement.execute("INSERT INTO t_contract_compliance_profile "
                        + "(contract_id, workplace_id, limitation_date, workplace_limitation_date, "
                        + "worker_limitation_date, snapshot_json, workplace_snapshot_json, worker_snapshot_json) "
                        + "VALUES (1, 1, NULL, '2026-12-31', NULL, '{\"address\":\"旧住所\"}', "
                        + "'{\"organization\":\"旧組織\"}', '{\"worker\":1}')");
                assertEquals("{\"address\":\"旧住所\"}", queryString(statement,
                        "SELECT snapshot_json FROM t_contract_compliance_profile WHERE contract_id=1"));
                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_profile "
                                + "WHERE limitation_date IS NULL AND worker_limitation_date IS NULL"));
                statement.execute("INSERT INTO t_compliance_finding "
                        + "(contract_id, code, condition_fingerprint) VALUES (1, 'MISSING_LIMITATION_DATE', 'h2-fp')");
                boolean duplicateRejected = false;
                try {
                    statement.execute("INSERT INTO t_compliance_finding "
                            + "(contract_id, code, condition_fingerprint) VALUES (1, 'MISSING_LIMITATION_DATE', 'h2-fp')");
                } catch (SQLException expected) {
                    duplicateRejected = true;
                }
                assertTrue(duplicateRejected, "H2でもfindingの重複を拒否するはず");
                boolean invalidPeriodRejected = false;
                try {
                    statement.execute("INSERT INTO m_workplace "
                            + "(customer_id, name, valid_from, valid_to) VALUES (1, 'invalid', '2026-12-31', '2026-01-01')");
                } catch (SQLException expected) {
                    invalidPeriodRejected = true;
                }
                assertTrue(invalidPeriodRejected, "事業所期間の逆転を拒否するはず");
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
