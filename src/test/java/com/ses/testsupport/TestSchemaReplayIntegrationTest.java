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

        Integer constraints = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'T_IMPORT_JOB' "
                        + "AND CONSTRAINT_NAME IN ('FK_IMPORT_JOB_DOCUMENT_VERSION', "
                        + "'FK_IMPORT_JOB_ACTIVE_RUN', 'FK_IMPORT_JOB_CURRENT_RUN')",
                Integer.class);
        assertThat(constraints).isEqualTo(3);
    }

    private void replayDocumentAndImportSchema() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-document-archive-h2.sql"),
                new ClassPathResource("sql/schema-import-center-f1a-h2.sql"));
        populator.execute(dataSource);
    }
}
