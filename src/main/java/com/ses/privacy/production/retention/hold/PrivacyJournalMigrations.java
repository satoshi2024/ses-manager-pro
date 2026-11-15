package com.ses.privacy.production.retention.hold;

import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * artifact が持つ Journal 用 migration（{@code classpath:db/privacy-journal}）の一覧。
 *
 * <p>起動時検査は期待版をコードへ固定せず、artifact の migration と Journal の Flyway 履歴表
 * （{@value #HISTORY_TABLE}）を照合する（application の {@code ProductionSchemaVersionGuard} と同じ考え方）。
 * application は migration を実行しない（適用は専用 Flyway job だけ。P-3）。
 */
final class PrivacyJournalMigrations {

    /** Journal 用 migration の classpath location（application の db/migration とは別）。 */
    static final String LOCATION = "db/privacy-journal";

    /** Journal 用 Flyway の履歴表（application の flyway_schema_history とは別）。 */
    static final String HISTORY_TABLE = "flyway_schema_history_privacy_journal";

    private static final Pattern VERSIONED = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");

    private PrivacyJournalMigrations() {
    }

    /** versioned migration 1件（版と script 名）。 */
    record Migration(MigrationVersion version, String script) {
    }

    /** classpath 上の Journal 用 versioned migration を版の昇順で返す。読めない・版の重複は例外。 */
    static List<Migration> fromClasspath() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver(PrivacyJournalMigrations.class.getClassLoader())
                    .getResources("classpath*:" + LOCATION + "/*.sql");
        } catch (IOException e) {
            throw new IllegalStateException("Journal用migrationを解決できません", e);
        }
        List<Migration> migrations = new ArrayList<>();
        for (Resource resource : resources) {
            String name = resource.getFilename();
            Matcher matcher = name == null ? null : VERSIONED.matcher(name);
            if (matcher == null || !matcher.matches()) {
                throw new IllegalStateException("Journal用locationにversioned migration以外のfileがあります");
            }
            MigrationVersion version = MigrationVersion.fromVersion(matcher.group(1).replace('_', '.'));
            if (migrations.stream().anyMatch(existing -> existing.version().equals(version))) {
                throw new IllegalStateException("Journal用migrationの版が重複しています");
            }
            migrations.add(new Migration(version, name));
        }
        migrations.sort(Comparator.comparing(Migration::version));
        return List.copyOf(migrations);
    }
}
