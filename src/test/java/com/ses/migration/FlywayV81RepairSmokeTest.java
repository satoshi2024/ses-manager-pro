package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.ses.test.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V80適用済みDBを変更せずV81で最終shapeへ収束させる実MySQL gate。 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayV81RepairSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v81_repair")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void 公開済みV80成功DBのwrongIndexFkをV81が全shape検証して修復する() throws Exception {
        Path dir = prepareMigrationDir();
        migratePublishedV80(dir);
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE t_contract DROP FOREIGN KEY fk_contract_order_line");
            statement.execute("ALTER TABLE t_contract DROP INDEX uk_contract_order_line");
            statement.execute("ALTER TABLE t_contract ADD INDEX uk_contract_order_line (order_line_id, customer_id)");
            statement.execute("ALTER TABLE t_contract ADD CONSTRAINT fk_contract_order_line "
                    + "FOREIGN KEY (order_line_id) REFERENCES t_sales_order_line(id) "
                    + "ON UPDATE RESTRICT ON DELETE RESTRICT");
        }

        flyway(dir, "81").migrate();
        flyway(dir, "81").validate();

        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract' "
                    + "AND index_name='uk_contract_order_line' AND non_unique=0 "
                    + "AND seq_in_index=1 AND column_name='order_line_id' AND sub_part IS NULL"));
            assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract' "
                    + "AND index_name='uk_contract_order_line'"));
            assertEquals(1, queryInt(statement, "SELECT COUNT(*) "
                    + "FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() "
                    + "AND table_name='t_contract' AND constraint_name='fk_contract_order_line' "
                    + "AND update_rule='CASCADE' AND delete_rule='SET NULL'"));
            assertTrue(hasRow(statement, "SELECT 1 FROM information_schema.table_constraints "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract' "
                    + "AND constraint_name='chk_contract_acceptance_exemption' AND constraint_type='CHECK'"));
            assertTrue(hasRow(statement, "SELECT 1 FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='t_document_hash_claim'"));
        }
    }

    @Test
    void V81hashBackfillは既存重複をfailClosedで拒否する() throws Exception {
        Path dir = prepareMigrationDir();
        migratePublishedV80(dir);
        String hash = "a".repeat(64);
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO t_document (document_type, direction, status) "
                    + "VALUES ('ORDER_RECEIVED','INCOMING','CONFIRMED'),('ORDER_RECEIVED','INCOMING','CONFIRMED')");
            statement.execute("INSERT INTO t_document_version "
                    + "(document_id,version_no,storage_key,original_name,sha256,source_type,business_key,version_discriminator,scan_status,created_by) "
                    + "SELECT id,1,CONCAT('k-',id),'order.pdf','" + hash + "','RECEIVED',CONCAT('ORDER_RECEIVED:',id),'1','CLEAN',1 "
                    + "FROM t_document WHERE document_type='ORDER_RECEIVED'");
        }

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(dir, "81").migrate());
        assertTrue(allMessages(failure).contains("Duplicate document hash"));
    }

    private void migratePublishedV80(Path dir) throws Exception {
        flyway(dir, "81").clean();
        flyway(dir, "79.1").migrate();
        applyHistoricalV79_1Fixture();
        flyway(dir, "80").migrate();
        flyway(dir, "80").validate();
    }

    private Path prepareMigrationDir() throws Exception {
        Path temp = Files.createTempDirectory("v81-fixture");
        try (Stream<Path> files = Files.list(Paths.get("src/main/resources/db/migration"))) {
            for (Path file : files.toList()) {
                Files.copy(file, temp.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return temp;
    }

    private void applyHistoricalV79_1Fixture() throws Exception {
        String sql = Files.readString(Paths.get("src/test/resources/sql/v79_1-order-acceptance-legacy.sql"),
                StandardCharsets.UTF_8);
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            for (String command : sql.split(";")) {
                String trimmed = command.replaceAll("(?m)^--.*$", "").trim();
                if (!trimmed.isEmpty()) statement.execute(trimmed);
            }
        }
    }

    private Flyway flyway(Path dir, String target) {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + dir)
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private boolean hasRow(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next();
        }
    }

    private String allMessages(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null) messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
