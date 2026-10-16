package com.ses.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    @Autowired private Environment environment;
    @Autowired private ResourceLoader resourceLoader;

    @Test
    void applicationTestの全schemaLocationsを同じDBへ再投入しても参照制約が維持される() throws Exception {
        replayCompleteSchemaLocations();
        replayCompleteSchemaLocations();

        assertForeignKey("FK_IMPORT_JOB_DOCUMENT", "T_DOCUMENT",
                List.of("TENANT_ID", "DOCUMENT_ID"), List.of("TENANT_ID", "ID"));
        assertForeignKey("FK_IMPORT_JOB_DOCUMENT_VERSION", "T_DOCUMENT_VERSION",
                List.of("TENANT_ID", "DOCUMENT_VERSION_ID"), List.of("TENANT_ID", "ID"));
        assertForeignKey("FK_IMPORT_JOB_ACTIVE_RUN", "T_IMPORT_VALIDATION_RUN",
                List.of("TENANT_ID", "ID", "ACTIVE_VALIDATION_RUN_ID"),
                List.of("TENANT_ID", "JOB_ID", "ID"));
        assertForeignKey("FK_IMPORT_JOB_CURRENT_RUN", "T_IMPORT_VALIDATION_RUN",
                List.of("TENANT_ID", "ID", "CURRENT_VALIDATION_RUN_ID"),
                List.of("TENANT_ID", "JOB_ID", "ID"));
    }

    private void assertForeignKey(String name, String targetTable,
                                  List<String> sourceColumns, List<String> targetColumns) throws SQLException {
        List<ImportedKey> keys = new ArrayList<>();
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rows = metaData.getImportedKeys(connection.getCatalog(), "PUBLIC", "T_IMPORT_JOB")) {
                while (rows.next()) {
                    if (name.equalsIgnoreCase(rows.getString("FK_NAME"))) {
                        keys.add(new ImportedKey(rows.getShort("KEY_SEQ"), rows.getString("FKTABLE_NAME"),
                                rows.getString("FKCOLUMN_NAME"), rows.getString("PKTABLE_NAME"),
                                rows.getString("PKCOLUMN_NAME")));
                    }
                }
            }
        }
        keys.sort(Comparator.comparingInt(ImportedKey::sequence));
        assertThat(keys).as("%s の列mapping", name).hasSize(sourceColumns.size());
        assertThat(keys.stream().map(ImportedKey::sourceColumn).toList()).containsExactlyElementsOf(sourceColumns);
        assertThat(keys.stream().map(ImportedKey::targetColumn).toList()).containsExactlyElementsOf(targetColumns);
        assertThat(keys).allSatisfy(key -> {
            assertThat(key.sourceTable()).isEqualToIgnoringCase("T_IMPORT_JOB");
            assertThat(key.targetTable()).isEqualToIgnoringCase(targetTable);
        });
    }

    private void replayCompleteSchemaLocations() {
        List<String> locations = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("spring.sql.init.schema-locations",
                        org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                .orElseThrow(() -> new IllegalStateException("schema-locationsが設定されていません"));
        Resource[] resources = locations.stream().map(resourceLoader::getResource).toArray(Resource[]::new);
        new ResourceDatabasePopulator(resources).execute(dataSource);
    }

    private record ImportedKey(int sequence, String sourceTable, String sourceColumn,
                               String targetTable, String targetColumn) {}
}
