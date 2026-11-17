package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * b-14（W2-NF06 review P3-5）: 取込 stage 照合の storage key 照会に索引がある（V200）ことと、H2 の試験 schema が同じ索引を
 * 持つことを Docker なしで固定する。実 MySQL での索引の存在は {@code FlywayMigrationSmokeTest}（mysql shard 3）が確かめる。
 */
class ImportStageStorageKeyIndexContractTest {
    private static final Pattern INDEX = Pattern.compile(
            "CREATE\\s+INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?idx_dv_storage_key\\s+ON\\s+t_document_version\\s*\\(\\s*storage_key\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    @Test
    void V200はt_document_versionのstorage_keyへ非一意の索引を作りV1とV67を変えない() throws Exception {
        String v200 = withoutComments(Files.readString(
                Path.of("src/main/resources/db/migration/V200__nf06_document_version_storage_key_index.sql"),
                StandardCharsets.UTF_8));

        assertThat(INDEX.matcher(v200).find()).as("V200 の索引").isTrue();
        assertThat(v200).doesNotContainIgnoringCase("UNIQUE").doesNotContainIgnoringCase("IF NOT EXISTS");
        for (String baseline : new String[]{"V1__create_tables.sql", "V67__document_archive.sql"}) {
            assertThat(Files.readString(Path.of("src/main/resources/db/migration", baseline), StandardCharsets.UTF_8))
                    .as(baseline + " は変えない（索引は V200 だけが作る）").doesNotContain("idx_dv_storage_key");
        }
    }

    @Test
    void H2の文書schemaは同じ索引を作りreplayはV200のfileを重ねない() throws Exception {
        String h2 = withoutComments(Files.readString(Path.of("src/test/resources/sql/schema-document-archive-h2.sql"),
                StandardCharsets.UTF_8));
        String replay = Files.readString(Path.of("src/test/resources/application-test.yml"), StandardCharsets.UTF_8);

        assertThat(INDEX.matcher(h2).find()).as("schema-document-archive-h2.sql の索引").isTrue();
        assertThat(h2.indexOf("idx_dv_storage_key")).as("表の作成より後に索引を作る")
                .isGreaterThan(h2.indexOf("CREATE TABLE t_document_version"));
        assertThat(replay).contains("classpath:sql/schema-document-archive-h2.sql")
                .doesNotContain("classpath:db/migration/V200__");
    }

    private static String withoutComments(String sql) {
        return Pattern.compile("--[^\\n]*").matcher(sql).replaceAll("");
    }
}
