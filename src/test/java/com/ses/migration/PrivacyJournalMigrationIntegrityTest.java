package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF07 Journal 用 migration（{@code classpath:db/privacy-journal}）の整合性をDB無しで固定する
 * （{@link MigrationScriptIntegrityTest} と同型。journal-witness-grant-design.md §2.5）。
 *
 * <p>版番号は application の {@code db/migration} と独立しているため、location ごとに重複・空 script を検査する。
 * あわせて、Journal の表が合成 schema（{@code privacy-journal/schema-test.sql}）と名前以外同一であること
 * （production-bridge-phased-plan.md §3.3。列・制約・collation の意味の変更は Plan Review を要する）を固定する。
 * classpath から読むため、script の改名後は {@code mvn clean test} で古い copy を消すこと。
 */
class PrivacyJournalMigrationIntegrityTest {

    private static final String LOCATION = "classpath:db/privacy-journal";
    private static final Pattern VERSIONED = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");
    private static final List<String> JOURNAL_TABLES = List.of("pj_head", "pj_event", "pj_command", "pj_action", "pj_lease");

    @Test
    void Journal用migrationのバージョンが重複していないこと() throws Exception {
        Map<String, List<String>> byVersion = new TreeMap<>();
        for (Resource resource : resources("/*.sql")) {
            Matcher matcher = VERSIONED.matcher(resource.getFilename());
            assertThat(matcher.matches()).as("versioned migrationの命名: %s", resource.getFilename()).isTrue();
            String version = matcher.group(1).replace('_', '.');
            byVersion.computeIfAbsent(version, key -> new ArrayList<>()).add(resource.getFilename());
        }
        assertThat(byVersion).as("db/privacy-journal からmigrationを1件も読み込めていません").isNotEmpty();
        assertThat(byVersion.values()).as("同じ版のJournal用migrationがあります").allMatch(files -> files.size() == 1);
        assertThat(byVersion).as("Journal は V1 から始まる").containsKey("1");
    }

    @Test
    void Journal用migrationが空ファイルでないこと() throws Exception {
        for (Resource resource : resources("/*.sql")) {
            assertThat(withoutComments(read(resource)).isBlank())
                    .as("空のmigration: %s", resource.getFilename()).isFalse();
        }
    }

    @Test
    void Journal用locationにはversioned_SQLだけを置く() throws Exception {
        List<String> others = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/privacy-journal/**/*")) {
            String name = resource.getFilename();
            if (resource.isReadable() && name != null && !name.isEmpty() && !VERSIONED.matcher(name).matches()) {
                others.add(name);
            }
        }
        assertThat(others)
                .as("Repeatable・callback（beforeMigrate.sql等）・Java migrationを置かない（applicationのcallbackを持たない）")
                .isEmpty();
    }

    @Test
    void V1の表は合成schemaのsf_journal表と名前以外同一であること() throws Exception {
        String journal = normalize(read(resource("V1__privacy_journal_core.sql")));
        String synthetic = normalize(read(new ClassPathResource("privacy-journal/schema-test.sql")))
                .replace("sf_journal_", "pj_")
                .replace("_sf_", "_pj_");

        assertThat(journal).as("列・型・制約・collation（identity列はutf8mb4_0900_bin）は合成schemaと同一").isEqualTo(synthetic);
    }

    @Test
    void V1はJournalの5表だけを作り業務表や破壊的な文を含まない() throws Exception {
        String sql = withoutComments(read(resource("V1__privacy_journal_core.sql")));
        String upper = sql.toUpperCase(Locale.ROOT);

        Matcher create = Pattern.compile("CREATE\\s+TABLE\\s+`?([A-Za-z0-9_]+)`?", Pattern.CASE_INSENSITIVE).matcher(sql);
        List<String> created = new ArrayList<>();
        while (create.find()) {
            created.add(create.group(1).toLowerCase(Locale.ROOT));
        }
        assertThat(created).containsExactlyElementsOf(JOURNAL_TABLES);
        for (String forbidden : List.of("DROP ", "DELETE ", "TRUNCATE ", "ALTER ", "INSERT ", "UPDATE ", "GRANT ",
                "CREATE USER", "CREATE DATABASE", "PROCEDURE", "TRIGGER", "${")) {
            assertThat(upper).as("Journal の DDL に %s を含めない", forbidden.trim()).doesNotContain(forbidden);
        }
        Matcher references = Pattern.compile("REFERENCES\\s+`?([A-Za-z0-9_]+)`?", Pattern.CASE_INSENSITIVE).matcher(sql);
        while (references.find()) {
            assertThat(JOURNAL_TABLES).as("外部キーはJournal内の表だけを参照する").contains(references.group(1).toLowerCase(Locale.ROOT));
        }
        assertThat(upper).as("全表がInnoDB").contains("ENGINE=INNODB");
        assertThat(upper.split("ENGINE=INNODB", -1)).hasSize(JOURNAL_TABLES.size() + 1);
    }

    @Test
    void application_DBのmigrationはJournalの表を作らない() throws Exception {
        TreeSet<String> offenders = new TreeSet<>();
        Pattern journalTable = Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?pj_", Pattern.CASE_INSENSITIVE);
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
            if (journalTable.matcher(read(resource)).find()) {
                offenders.add(resource.getFilename());
            }
        }
        assertThat(offenders).as("Journal の表は独立インスタンスにだけ作る（application DB に置かない）").isEmpty();
    }

    // ---------------------------------------------------------------- helper

    private static Resource[] resources(String pattern) throws Exception {
        return new PathMatchingResourcePatternResolver().getResources(LOCATION + pattern);
    }

    private static Resource resource(String name) {
        Resource resource = new PathMatchingResourcePatternResolver().getResource(LOCATION + "/" + name);
        assertThat(resource.exists()).as("%s が classpath にあること", name).isTrue();
        return resource;
    }

    private static String read(Resource resource) throws Exception {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String withoutComments(String sql) {
        StringBuilder result = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("--")) {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    /** comment行・空行を除き、行末の空白をそろえる（比較用）。 */
    private static String normalize(String sql) {
        StringBuilder result = new StringBuilder();
        for (String line : withoutComments(sql).split("\\R")) {
            String trimmed = line.stripTrailing();
            if (!trimmed.isBlank()) {
                result.append(trimmed).append('\n');
            }
        }
        return result.toString();
    }
}
