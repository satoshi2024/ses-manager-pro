package com.ses.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 同じH2 DBにNF-06文書schemaとNF-08 import schemaを再投入して参照制約が維持されることを確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TestSchemaReplayIntegrationTest {

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "schema_replay_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 文書schemaとimport参照制約を同じDBへ2回再投入できる() {
        replayDocumentAndImportSchema();
        replayDocumentAndImportSchema();

        assertForeignKey("FK_IMPORT_JOB_DOCUMENT", "T_DOCUMENT", "ID");
        assertForeignKey("FK_IMPORT_JOB_DOCUMENT_VERSION", "T_DOCUMENT_VERSION", "ID");
        assertForeignKey("FK_IMPORT_JOB_ACTIVE_RUN", "T_IMPORT_VALIDATION_RUN", "ID");
        assertForeignKey("FK_IMPORT_JOB_CURRENT_RUN", "T_IMPORT_VALIDATION_RUN", "ID");
    }

    private void assertForeignKey(String name, String targetTable, String targetColumn) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                        + "WHERE CONSTRAINT_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'T_IMPORT_JOB' "
                        + "AND CONSTRAINT_NAME = ? AND REFERENCED_TABLE_NAME = ? "
                        + "AND REFERENCED_COLUMN_NAME = ?", Integer.class,
                name, targetTable, targetColumn);
        assertThat(count).as("%s の参照先", name).isEqualTo(1);
    }

    private void replayDocumentAndImportSchema() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-document-archive-h2.sql"),
                new ClassPathResource("sql/schema-import-center-f1a-h2.sql"));
        populator.execute(dataSource);
    }
}
