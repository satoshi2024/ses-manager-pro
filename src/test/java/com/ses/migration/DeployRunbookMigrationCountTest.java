package com.ses.migration;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
