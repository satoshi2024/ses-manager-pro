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

    /**
     * 要員の所属組織はV60で追加してからbackfillする。逆順だと実MySQLで
     * {@code Unknown column 'organization_id'} になる（version列と同じ事故）。
     */
    @Test
    void V60の要員所属組織列はbackfillより先に追加されること() throws Exception {
        String sql = v60();

        int addColumn = sql.indexOf("ALTER TABLE t_engineer ADD COLUMN organization_id");
        int backfill = sql.indexOf("UPDATE t_engineer e");

        assertTrue(addColumn >= 0, "V60にt_engineer.organization_idの追加がありません");
        assertTrue(backfill >= 0, "V60に既存要員の所属組織backfillがありません");
        assertTrue(addColumn < backfill,
                "t_engineer.organization_idを追加してから要員のbackfillを実行してください");
    }

    /**
     * 業務一意制約は所属backfillの後に追加する。先に張ると、backfillが入れる行と
     * 既存行の重複で ALTER が失敗し、V60全体がロールバックされる。
     * 生成列→UNIQUEの順序も崩すと {@code Key column doesn't exist} になる。
     */
    @Test
    void V60の一意制約は生成列追加とbackfillの後に来ること() throws Exception {
        String sql = v60();

        int assignmentBackfill = sql.indexOf("INSERT INTO t_user_organization");
        int addGeneratedColumn = sql.indexOf("ADD COLUMN active_primary_user_id");
        int addUniqueKey = sql.indexOf("ADD UNIQUE KEY uk_user_org_active_primary");
        int addLegalKey = sql.indexOf("ADD COLUMN legal_entity_key");
        int addOrgCodeKey = sql.indexOf("ADD UNIQUE KEY uk_organization_code");

        assertTrue(addGeneratedColumn >= 0 && addUniqueKey >= 0,
                "V60に主所属一意のための生成列とUNIQUEがありません");
        assertTrue(addLegalKey >= 0 && addOrgCodeKey >= 0,
                "V60に組織コード一意のための生成列とUNIQUEがありません");
        assertTrue(assignmentBackfill < addGeneratedColumn,
                "所属backfillの後に主所属一意の生成列を追加してください");
        assertTrue(addGeneratedColumn < addUniqueKey,
                "生成列 active_primary_user_id を追加してからUNIQUEを張ってください");
        assertTrue(addLegalKey < addOrgCodeKey,
                "生成列 legal_entity_key を追加してからUNIQUEを張ってください");
    }

    /**
     * V1統合baselineとV60の最終形が食い違うと、新規DBと既存DBでスキーマが分岐する。
     * 本specで追加した列・制約が両方に載っていることを確認する。
     */
    @Test
    void 組織関連の列と一意制約はV1統合baselineにも反映されていること() throws Exception {
        String v1 = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V1__create_tables.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        for (String expected : List.of(
                "organization_id",
                "uk_organization_code",
                "uk_user_org_active_primary",
                "uk_user_org_period",
                "active_primary_user_id",
                "legal_entity_key")) {
            assertTrue(v1.contains(expected),
                    "V1統合baselineに " + expected + " が反映されていません（V60適用済みDBと分岐します）");
        }
    }

    private String v60() throws Exception {
        return new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V60__organization_management_accounting.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
