package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flywayマイグレーションスクリプトの静的な整合性を、DB無し・Docker無しで検証する。
 *
 * <p>{@link FlywayMigrationSmokeTest} はDockerが無い環境では自動スキップされるため、
 * 「同一バージョンのスクリプトが2本ある」といった致命的な不整合が {@code mvn test} 緑のまま
 * mainに入り、起動時に初めて
 * {@code FlywayException: Found more than one migration with version NN} で全環境が
 * 起動不能になる、という事故が起こりうる。並行ブランチをマージすると採番が衝突しやすいため、
 * 本テストでその穴を埋める（Flywayの解決処理と同じ観点をファイル名だけで検査する）。
 */
class MigrationScriptIntegrityTest {

    /** Flywayのバージョン付きマイグレーション命名規約: V&lt;version&gt;__&lt;description&gt;.sql */
    private static final Pattern VERSIONED = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");

    @Test
    void マイグレーションのバージョンが重複していないこと() throws Exception {
        Map<String, List<String>> byVersion = new LinkedHashMap<>();

        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql")) {
            String fileName = resource.getFilename();
            if (fileName == null) {
                continue;
            }
            Matcher m = VERSIONED.matcher(fileName);
            if (!m.matches()) {
                continue; // R__(Repeatable)等はバージョンを持たないため対象外
            }
            // Flywayは "1.0" と "1" を同一バージョンとみなすため、比較キーは区切りを正規化する
            String version = m.group(1).replace('_', '.');
            byVersion.computeIfAbsent(version, k -> new ArrayList<>()).add(fileName);
        }

        assertTrue(!byVersion.isEmpty(), "db/migration からマイグレーションを1件も読み込めていません");

        List<String> duplicates = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " -> " + e.getValue())
                .toList();

        assertTrue(duplicates.isEmpty(),
                "同一バージョンのマイグレーションが複数存在します（Flywayが起動時に例外を投げます）。"
                        + "後から追加された側を未使用の最新番号へ採番し直してください: " + duplicates);
    }

    /**
     * 空のスクリプトは {@code spring.sql.init}（テストのH2スキーマ投入）に拒否されるため、
     * 意図的なno-opでも {@code SELECT 1;} を残す必要がある（CLAUDE.md の V3/V8 の経緯）。
     */
    @Test
    void マイグレーションが空ファイルでないこと() throws Exception {
        List<String> empties = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql")) {
            if (resource.contentLength() == 0) {
                empties.add(String.valueOf(resource.getFilename()));
            }
        }
        assertTrue(empties.isEmpty(), "空のマイグレーションスクリプトがあります（no-opでも SELECT 1; が必要）: " + empties);
    }

    @Test
    void V60のlegacy互換列は所属backfillより先に追加されること() throws Exception {
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V60__organization_management_accounting.sql");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        int addVersion = sql.indexOf("ALTER TABLE t_user_organization ADD COLUMN version");
        int backfill = sql.indexOf("INSERT INTO t_user_organization");

        assertTrue(addVersion >= 0, "V60にlegacy用version列追加がありません");
        assertTrue(backfill >= 0, "V60に既存ユーザー所属backfillがありません");
        assertTrue(addVersion < backfill,
                "legacy DBではt_user_organization.versionを追加してから所属backfillを実行してください");
    }
}
