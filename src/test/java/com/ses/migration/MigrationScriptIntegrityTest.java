package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void 公開済みV5は不変で会計帰属列を含まないこと() throws Exception {
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V5__create_work_record_billing.sql");
        byte[] bytes = resource.getInputStream().readAllBytes();
        // 改行コードを正規化してからハッシュする。CRLFのまま固定すると、
        // Windows作業機では通るのにLFでcheckoutするCI/Linuxでだけ落ちる
        // （＝V5の内容が変わっていなくても失敗する）プラットフォーム依存テストになる。
        byte[] normalized = new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
        assertEquals("f6d1194cb1e3bfcf58890bb89289cd0eb91b860a66ac06b2be0edaa12ce9fdc7", checksum,
                "適用済みV5を編集すると既存DBのFlyway checksumが壊れます");
        String sql = new String(bytes, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        assertTrue(!sql.contains("organization_id") && !sql.contains("cost_center_id")
                        && !sql.contains("accounting_dimension_frozen"),
                "V5へ会計帰属列を追加せず、V60の未公開DDLで追加してください");
    }

    @Test
    void 公開済みV63はchecksumを変更しないこと() throws Exception {
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V63__enterprise_identity_security.sql");
        String normalized = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8)));

        assertEquals("3fb36799a753780cb4451d567d0f7ed6943354cdf81e2f50b5c9d6ef0dc1f292", checksum,
                "公開済みV63を編集せず、追加DDL/DMLはV64以降へ置いてください");
        assertFalse(normalized.contains("role-admin"),
                "default permission group seedはV63へ戻さずV64へ置いてください");
    }

    @Test
    void V60のlegacy補列は対象列ごとに独立しBP外部キーより先に追加されること() throws Exception {
        String sql = v60();
        int bpColumn = sql.indexOf("ALTER TABLE t_bp_payment ADD COLUMN cost_center_id");
        int bpForeignKey = sql.indexOf("fk_bp_payment_cost_center");
        int workOrganization = sql.indexOf("ALTER TABLE t_work_record ADD COLUMN organization_id");
        int workCostCenter = sql.indexOf("ALTER TABLE t_work_record ADD COLUMN cost_center_id");
        int frozen = sql.indexOf("ALTER TABLE t_work_record ADD COLUMN accounting_dimension_frozen");

        assertTrue(bpColumn >= 0 && bpForeignKey >= 0 && bpColumn < bpForeignKey,
                "BP支払のcost_center_idを追加してから外部キーを作成してください");
        assertTrue(workOrganization >= 0 && workCostCenter >= 0 && frozen >= 0,
                "WorkRecordの組織・原価部門・凍結フラグはV60に個別追加してください");
        assertTrue(workOrganization != workCostCenter && workCostCenter != frozen,
                "WorkRecordの各列は同一の存在判定に束ねないでください");
    }

    /**
     * 生成列をSTOREDにすると、空DB(CREATE TABLE)は通るのに既存DB(ALTER)だけが
     * {@code ERROR 1215 Cannot add foreign key constraint} で落ちる。
     *
     * <p>MySQL 8は「STORED生成列の元になっている列の外部キーに ON UPDATE CASCADE を使えない」
     * という制約をALTER時にだけ課すためで、V60の3つの生成列
     * (legal_entity_key / active_primary_user_id / cost_center_key) は
     * いずれも外部キー列(legal_entity_id以外)を元にしている。
     * Dockerが無いCI環境でも落とせるよう、静的検査として固定する。
     */
    @Test
    void V60とV1の生成列はVIRTUALで揃っていること() throws Exception {
        assertFalse(withoutComments(v60()).contains("STORED"),
                "V60の生成列をSTOREDにすると既存DBのALTERがERROR 1215で失敗します（VIRTUALのままにしてください）");

        String v1 = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V1__create_tables.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertFalse(withoutComments(v1).contains("STORED"),
                "V1統合baselineの生成列もVIRTUALに揃えてください（新規DBと既存DBでスキーマが分岐します）");
    }

    /**
     * V61の履歴テーブルは「現在値しか持たない列を過去日で参照しない」ための版元。
     * backfillが無いとV61適用直後に過去月の部門損益と待機原価がまとめて欠落するため、
     * テーブル作成とbackfillの両方が揃っていること、順序が逆でないことを固定する。
     */
    @Test
    void V61は履歴テーブル作成の後にbackfillすること() throws Exception {
        String sql = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V61__organization_and_engineer_accounting_history.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        int createRelation = sql.indexOf("CREATE TABLE IF NOT EXISTS t_organization_relation_history");
        int createEngineer = sql.indexOf("CREATE TABLE IF NOT EXISTS t_engineer_accounting_history");
        int backfillRelation = sql.indexOf("INSERT INTO t_organization_relation_history");
        int backfillEngineer = sql.indexOf("INSERT INTO t_engineer_accounting_history");

        assertTrue(createRelation >= 0 && createEngineer >= 0, "V61に履歴テーブルの作成がありません");
        assertTrue(backfillRelation >= 0 && backfillEngineer >= 0, "V61に既存行のbackfillがありません");
        assertTrue(createRelation < backfillRelation && createEngineer < backfillEngineer,
                "履歴テーブルを作成してからbackfillしてください");
        assertFalse(withoutComments(sql).contains("STORED"),
                "V61でも生成列はVIRTUALに揃えてください");
    }

    /**
     * V62は要員の所属組織もV61の会計属性履歴テーブルへ同時に記録できるようにする。
     * 列追加とbackfillが揃っていること、順序が逆でないことを固定する
     * （version/organization_id列と同じ「先に列、あとでbackfill」の事故を防ぐ）。
     */
    @Test
    void V62は所属組織列追加の後にbackfillすること() throws Exception {
        String sql = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V62__engineer_organization_history.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        int addColumn = sql.indexOf("ALTER TABLE t_engineer_accounting_history ADD COLUMN organization_id");
        int addStatusColumn = sql.indexOf("ADD COLUMN organization_history_status");
        int backfill = sql.indexOf("UPDATE t_engineer_accounting_history");

        assertTrue(addColumn >= 0, "V62に所属組織列の追加がありません");
        assertTrue(addStatusColumn >= 0, "V62に復元不能履歴の状態列がありません");
        assertTrue(backfill >= 0, "V62に既存履歴行への所属組織backfillがありません");
        assertTrue(addColumn < backfill && addStatusColumn < backfill,
                "所属組織列と状態列を追加してからbackfillしてください");
        assertTrue(sql.contains("organization_history_status = 'UNKNOWN'"),
                "復元不能なclosed行はUNKNOWNとして明示してください");
    }

    @Test
    void V64の既定groupは既存candidate導線を維持すること() throws Exception {
        String sql = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V64__seed_default_permission_groups.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("SELECT 'role-sales', 'candidate.*'"),
                "V16で営業へ公開した候補者導線を既定groupにも移行してください");
        assertTrue(sql.contains("SELECT 'role-hr', 'candidate.*'"),
                "V16でHRへ公開した候補者導線を既定groupにも移行してください");
    }

    /** 判定対象はDDLだけ。理由を書いた `--` コメントの語句で誤検知しないよう除去する。 */
    private String withoutComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(java.util.stream.Collectors.joining("\n"))
                .toUpperCase(java.util.Locale.ROOT);
    }

    private String v60() throws Exception {
        return new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V60__organization_management_accounting.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
