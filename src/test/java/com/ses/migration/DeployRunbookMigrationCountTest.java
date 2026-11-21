package com.ses.migration;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/** 配備手順の適用件数とLATESTを、SQLおよびJavaのversioned migration実体へ結び付ける。 */
class DeployRunbookMigrationCountTest {
    @Test
    void 配備手順の最新版と適用件数がソースの実体と一致する() throws Exception {
        List<String> versions = new ArrayList<>();
        int sql = collect(Path.of("src/main/resources/db/migration"), "sql", versions);
        int java = collect(Path.of("src/main/java/db/migration"), "java", versions);
        String latest = versions.stream().map(MigrationVersion::fromVersion).max(MigrationVersion::compareTo)
                .orElseThrow().getVersion();
        String runbook = Files.readString(Path.of(".kiro/specs/production-launch/deploy-runbook.md"));
        Matcher summary = required(runbook,
                "実行順:\\s*V1〜V([0-9._]+)（[^\\n]*?versioned\\s+(\\d+)件。SQL\\s+(\\d+)件＋Java migration",
                "3.3の実行順は V1〜V最新（versioned N件。SQL N件＋Java migration ...）と記載してください");
        assertThat(summary.group(1)).isEqualTo(latest);
        assertThat(Integer.parseInt(summary.group(2))).isEqualTo(sql + java);
        assertThat(Integer.parseInt(summary.group(3))).isEqualTo(sql);
        assertThat(latest).isEqualTo(LatestMigrationVersion.LATEST);
        Matcher latestSql = required(runbook,
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL\\s+ORDER BY installed_rank DESC LIMIT 1;[^\\n]*--\\s*'([0-9._]+)'",
                "3.4の最新version照会末尾へ -- '最新version' を記載してください");
        assertThat(latestSql.group(1)).isEqualTo(latest);
        Matcher countSql = required(runbook,
                "SELECT COUNT\\(\\*\\) FROM flyway_schema_history WHERE version IS NOT NULL AND success = 1;[^\\n]*--\\s*(\\d+)",
                "3.4のversioned適用件数照会末尾へ -- 件数 を記載してください");
        assertThat(Integer.parseInt(countSql.group(1))).isEqualTo(versions.size());
        // backup の checkpoint は FLYWAY_VERSION を復元先の flyway_schema_history と照合する（古い値は復元検証で FAIL）
        Matcher backupVersion = required(runbook,
                "`FLYWAY_VERSION`[^\\n]*?本 release は `([0-9.]+)`",
                "§1.7「Backup 開始の前提」の FLYWAY_VERSION に 本 release は `最新version` と記載してください");
        assertThat(backupVersion.group(1)).as(".env.backup の FLYWAY_VERSION（§1.7「Backup 開始の前提」）").isEqualTo(latest);
    }

    /**
     * 配備手順の trigger 件数（§1.2 の権限説明と §3.4 の COUNT 照会）を、versioned migration の CREATE / DROP TRIGGER を
     * 版の順に適用した正味の件数へ結び付ける（過去に静的計数を誤った。W3-18 review P3-6）。コメント内の語は数えない。
     */
    @Test
    void 配備手順のtrigger件数はmigrationのCREATEとDROP_TRIGGERを版順に適用した件数と一致する() throws Exception {
        Pattern name = Pattern.compile("^V([0-9]+(?:_[0-9]+)*)__.+\\.sql$");
        TreeMap<MigrationVersion, Path> scripts = new TreeMap<>();
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.toList()) {
                Matcher match = name.matcher(file.getFileName().toString());
                if (match.matches()) {
                    scripts.put(MigrationVersion.fromVersion(match.group(1).replace('_', '.')), file);
                }
            }
        }
        Pattern statement = Pattern.compile("\\b(CREATE|DROP)\\s+(?:DEFINER\\s*=\\s*\\S+\\s+)?TRIGGER\\s+"
                + "(IF\\s+(?:NOT\\s+)?EXISTS\\s+)?`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
        Set<String> triggers = new TreeSet<>();
        for (Map.Entry<MigrationVersion, Path> script : scripts.entrySet()) {
            Matcher match = statement.matcher(stripComments(Files.readString(script.getValue())));
            while (match.find()) {
                String trigger = match.group(3).toLowerCase(java.util.Locale.ROOT);
                if ("DROP".equalsIgnoreCase(match.group(1))) {
                    triggers.remove(trigger);
                } else {
                    boolean added = triggers.add(trigger);
                    assertThat(added || match.group(2) != null)
                            .as("V%s が既存の trigger %s を IF NOT EXISTS 無しで再作成している", script.getKey(), trigger)
                            .isTrue();
                }
            }
        }
        assertThat(triggers).as("trigger が1件も読めていない").isNotEmpty();

        // versioned 以外（Repeatable・prod 専用 script・Java migration）は trigger を作らない前提で数える
        for (Path root : List.of(Path.of("src/main/resources/db/migration"), Path.of("src/main/resources/db/migration-prod"),
                Path.of("src/main/java/db/migration"))) {
            try (var files = Files.list(root)) {
                for (Path file : files.toList()) {
                    if (name.matcher(file.getFileName().toString()).matches()) {
                        continue;
                    }
                    assertThat(statement.matcher(stripComments(Files.readString(file))).find())
                            .as("%s は trigger を作成・削除しない", file).isFalse();
                }
            }
        }

        String runbook = Files.readString(Path.of(".kiro/specs/production-launch/deploy-runbook.md"));
        Matcher privilege = required(runbook, "\\*\\*trigger（(\\d+)件",
                "1.2の migrator 権限に **trigger（N件 ...）とbinlog** と記載してください");
        Matcher count = required(runbook,
                "SELECT COUNT\\(\\*\\) FROM information_schema\\.triggers WHERE trigger_schema = DATABASE\\(\\);\\s*--\\s*(\\d+)",
                "3.4の trigger 件数照会末尾へ -- 件数 を記載してください");
        assertThat(Integer.parseInt(privilege.group(1))).as("§1.2 の trigger 件数").isEqualTo(triggers.size());
        assertThat(Integer.parseInt(count.group(1))).as("§3.4 の trigger 件数").isEqualTo(triggers.size());
    }

    /** SQLのブロックコメントと、2連ハイフンから行末までの行コメントを除く。 */
    private static String stripComments(String sql) {
        String withoutBlocks = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(sql).replaceAll(" ");
        return Pattern.compile("--[^\\n]*").matcher(withoutBlocks).replaceAll("");
    }

    private int collect(Path root, String extension, List<String> versions) throws Exception {
        Pattern name = Pattern.compile("^V([0-9]+(?:_[0-9]+)*)__.+\\." + extension + "$");
        int count = 0;
        try (var files = Files.list(root)) {
            for (Path file : files.toList()) {
                Matcher match = name.matcher(file.getFileName().toString());
                if (match.matches()) { versions.add(match.group(1).replace('_', '.')); count++; }
            }
        }
        assertThat(count).as(root.toString()).isPositive();
        return count;
    }

    private Matcher required(String text, String regex, String explanation) {
        Matcher match = Pattern.compile(regex).matcher(text);
        assertThat(match.find()).as(explanation).isTrue();
        return match;
    }
}
