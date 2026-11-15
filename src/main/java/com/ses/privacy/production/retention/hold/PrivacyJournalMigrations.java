package com.ses.privacy.production.retention.hold;

import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * artifact が持つ Journal 用 migration（{@code classpath:db/privacy-journal}）の一覧。
 *
 * <p>起動時検査は期待版をコードへ固定せず、artifact の migration と Journal の Flyway 履歴表
 * （{@value #HISTORY_TABLE}）を版・script 名・checksum で照合する（application の {@code ProductionSchemaVersionGuard}
 * と同じ考え方）。application は migration を実行しない（適用は専用 Flyway job だけ。P-3）。
 */
final class PrivacyJournalMigrations {

    /** Journal 用 migration の classpath location（application の db/migration とは別）。 */
    static final String LOCATION = "db/privacy-journal";

    /** Journal 用 Flyway の履歴表（application の flyway_schema_history とは別）。 */
    static final String HISTORY_TABLE = "flyway_schema_history_privacy_journal";

    private static final Pattern VERSIONED = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");

    private PrivacyJournalMigrations() {
    }

    /** versioned migration 1件（版・script 名・Flyway と同じ checksum）。 */
    record Migration(MigrationVersion version, String script, int checksum) {
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
            String content;
            try (var in = resource.getInputStream()) {
                content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Journal用migrationを読めません", e);
            }
            migrations.add(new Migration(version, name, checksum(content)));
        }
        migrations.sort(Comparator.comparing(Migration::version));
        return List.copyOf(migrations);
    }

    /**
     * Flyway（10.x の {@code ChecksumCalculator}）と同じ SQL migration の checksum。{@link BufferedReader#readLine()} で
     * 分けた各行（改行を含まない。先頭行は BOM を除く）の UTF-8 bytes を順に CRC32 へ入れ、{@code int} にする。
     * 同じ計算であることは {@code PrivacyJournalStartupVerifierTest} が Flyway の実装と突き合わせて固定する。
     */
    static int checksum(String content) {
        CRC32 crc32 = new CRC32();
        try (BufferedReader reader = new BufferedReader(new StringReader(content), 4096)) {
            String line = reader.readLine();
            boolean first = true;
            while (line != null) {
                if (first && !line.isEmpty() && line.charAt(0) == '﻿') {
                    line = line.substring(1);
                }
                first = false;
                crc32.update(line.getBytes(StandardCharsets.UTF_8));
                line = reader.readLine();
            }
        } catch (IOException impossible) {
            throw new IllegalStateException("checksumを計算できません", impossible);
        }
        return (int) crc32.getValue();
    }
}
