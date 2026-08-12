package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R22-P1-04の同種再発防止: t_compliance_operation_ledger.attempt_countのcanonical default（1）が
 * V1/V102/H2 schema/mapper/entity/V102 metadata manifestの全てで同期していることを検証する。
 */
class G2AttemptCountSyncTest {

    @Test
    void attemptCountのcanonicalDefault1がV1とV102とH2schemaとmanifestで同期している() throws Exception {
        String v1 = read("db/migration/V1__create_tables.sql");
        String v102 = read("db/migration/V102__dispatch_compliance_g2_gate_schema.sql");
        String h2 = read("sql/schema-dispatch-compliance-h2.sql");

        // V1 / V102 / H2: t_compliance_operation_ledger の attempt_count は DEFAULT 1
        assertTrue(lineWith(v1, "attempt_count").contains("DEFAULT 1"),
                "V1のattempt_countはDEFAULT 1");
        assertTrue(lineWith(v102, "attempt_count").contains("DEFAULT 1"),
                "V102 CREATEのattempt_countはDEFAULT 1");
        assertTrue(lineWith(h2, "attempt_count").contains("DEFAULT 1"),
                "H2 schemaのattempt_countはDEFAULT 1");

        // V102 metadata manifest（__ses_g2_assert_column_contract）は'1'を期待する
        Matcher manifest = Pattern.compile(
                "__ses_g2_assert_column_contract\\('t_compliance_operation_ledger', 'attempt_count', 'int', NULL, NULL, 'NO', '([^']+)'\\)")
                .matcher(v102);
        assertTrue(manifest.find(), "V102 manifestにattempt_countのassertが存在する");
        assertEquals("1", manifest.group(1), "V102 metadata manifestのattempt_count default期待値は'1'");

        // entity / mapperにattemptCountが存在する
        assertNotNull(Class.forName("com.ses.entity.ComplianceOperationLedger")
                        .getDeclaredField("attemptCount"),
                "entityにattemptCountが存在する");
        String mapper = read("com/ses/mapper/ComplianceOperationLedgerMapper.class");
        assertTrue(mapper.contains("attemptCount") || mapper.contains("attempt_count"),
                "mapperがattemptCount列を扱う");
    }

    @Test
    void V1実DDLのattemptCount列defaultが1である() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:g2_attempt_sync;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
                statement.execute(new String(new ClassPathResource(
                        "db/migration/V1__create_tables.sql").getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8));
            }
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                                 + "WHERE UPPER(TABLE_NAME)='T_COMPLIANCE_OPERATION_LEDGER' "
                                 + "AND UPPER(COLUMN_NAME)='ATTEMPT_COUNT'")) {
                assertTrue(resultSet.next(), "attempt_count列が存在する");
                assertEquals("1", resultSet.getString(1), "V1実DDLのattempt_count defaultは1");
            }
        }
    }

    private String read(String path) throws Exception {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String lineWith(String content, String needle) {
        for (String line : content.split("\r?\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        throw new AssertionError("対象行が見つかりません: " + needle);
    }
}
