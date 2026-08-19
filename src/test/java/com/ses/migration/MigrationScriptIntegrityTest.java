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
import java.util.Set;
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

    private static final Pattern FUNCTION_IF = Pattern.compile("\\bIF\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODIFIER_IF = Pattern.compile(
            "\\bIF\\s+(NOT\\s+)?EXISTS\\s+`?[A-Za-z_]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_IF = Pattern.compile("\\bIF\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_IF = Pattern.compile("\\bEND\\s+IF\\b", Pattern.CASE_INSENSITIVE);

    /**
     * ストアドプロシージャ内の {@code IF ... THEN} と {@code END IF} の均衡を、
     * DB無し・Docker無しで静的検査する（CRM-R4-P0-01）。
     *
     * <p>R__crm_contact_reconciliation.sql は複合文の {@code IF ... THEN} が1個しか無いのに
     * {@code END IF;} を2個持っていた。MySQLのcompound statement文法上 {@code END IF} は
     * 対応する {@code IF} の終端でしか使えないため {@code CREATE PROCEDURE} がERROR 1064で失敗し、
     * Flywayが例外を投げて全MySQL環境（fresh/legacy/既存本番）が起動不能になる。
     * {@link FlywayMigrationSmokeTest} 系はDocker不在で自動skipされ、H2 replay
     * （application-test.yml）もRepeatableを読まないため、この静的検査が最後の防波堤になる。
     *
     * <p>判定対象は複合文の {@code IF ... THEN ... END IF} のみ。以下は対象外:
     * <ul>
     *   <li>{@code IF(expr1, expr2, expr3)} という3引数の関数呼び出し（{@code THEN}を伴わない）</li>
     *   <li>{@code DROP PROCEDURE IF EXISTS x} / {@code CREATE TABLE IF NOT EXISTS x} という
     *       EXISTS修飾（識別子が直後に来るため、サブクエリ条件の {@code IF EXISTS (...)}
     *       と区別できる。{@code END IF} も不要）</li>
     * </ul>
     */
    @Test
    void ストアド内のIF_THENとEND_IFが均衡していること() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql")) {
            sources.put(String.valueOf(resource.getFilename()),
                    resource.getContentAsString(StandardCharsets.UTF_8));
        }
        java.io.File[] runbookFiles = new java.io.File("sql/runbook")
                .listFiles((dir, name) -> name.endsWith(".sql"));
        if (runbookFiles != null) {
            for (java.io.File file : runbookFiles) {
                sources.put("sql/runbook/" + file.getName(),
                        java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8));
            }
        }

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String sql = stripComments(entry.getValue());

            List<int[]> endIfSpans = new ArrayList<>();
            Matcher endIfMatcher = END_IF.matcher(sql);
            while (endIfMatcher.find()) {
                endIfSpans.add(new int[]{endIfMatcher.start(), endIfMatcher.end()});
            }

            int controlIfCount = 0;
            Matcher ifMatcher = ANY_IF.matcher(sql);
            while (ifMatcher.find()) {
                int pos = ifMatcher.start();
                if (endIfSpans.stream().anyMatch(span -> span[0] <= pos && pos < span[1])) {
                    continue; // "END IF" のIF部分
                }
                Matcher fm = FUNCTION_IF.matcher(sql);
                if (fm.find(pos) && fm.start() == pos) {
                    continue; // IF(expr1, expr2, expr3) 関数呼び出し
                }
                Matcher mm = MODIFIER_IF.matcher(sql);
                if (mm.find(pos) && mm.start() == pos) {
                    continue; // DROP PROCEDURE IF EXISTS x / CREATE TABLE IF NOT EXISTS x
                }
                controlIfCount++;
            }

            if (controlIfCount != endIfSpans.size()) {
                violations.add(entry.getKey() + ": IF...THEN=" + controlIfCount
                        + " END IF=" + endIfSpans.size());
            }
        }

        assertTrue(violations.isEmpty(),
                "ストアド内の IF...THEN と END IF が均衡していません。MySQLで CREATE PROCEDURE が"
                        + " ERROR 1064 になり、Flywayが起動時に例外を投げて全環境が起動不能になります: " + violations);
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
    void 公開済みV80はchecksumを変更しないこと() throws Exception {
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V80__order_acceptance_workflow.sql");
        String normalized = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8)));

        assertEquals("852a1a4f567c11216140d6bfa764871b74783a30d1be85489bd348cf4800f054", checksum,
                "公開済みV80を編集せず、注文・検収の補正はV81以降へ置いてください");
    }

    @Test
    void 公開済みV106_2はchecksumを変更しないこと() throws Exception {
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V106_2__accounting_company_boundary_forward_repair.sql");
        String normalized = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8)));

        assertEquals("2562d7b08bf441a45f249f7d6a4e6ce701dd0ed3dc9ace63f4a46c893fd8954a", checksum,
                "適用済みV106.2を編集せず、追加修正は後続migrationまたはrunbookへ置いてください");
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

    @Test
    void V66_1は非管理者の全局wildcardを削除して既知actionへ置換すること() throws Exception {
        String sql = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V66_1__close_security_review_boundaries.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("AND a.action_key = '*'"), "旧全局wildcardの削除条件が必要です");
        assertTrue(sql.contains("SELECT 'dashboard.*'") && sql.contains("SELECT 'proposal.*'"),
                "後方互換は既知resourceの明示許可で維持してください");
        assertTrue(sql.contains("ADD COLUMN allowed_actions"), "break-glass scope列が必要です");
    }

    /** 判定対象はDDLだけ。理由を書いた `--` コメントの語句で誤検知しないよう除去する。 */
    @Test
    void V1で定義されたカラムや制約が後続マイグレーションで裸のADDで重複定義されていないこと() throws Exception {
        String v1Sql = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V1__create_tables.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        Map<String, List<String>> v1TableColumns = new LinkedHashMap<>();
        String currentTable = null;
        for (String line : v1Sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("CREATE TABLE")) {
                Matcher m = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?`?([a-zA-Z0-9_]+)`?").matcher(trimmed);
                if (m.find()) {
                    currentTable = m.group(1);
                    v1TableColumns.put(currentTable, new ArrayList<>());
                }
            } else if (currentTable != null && (trimmed.startsWith(")") || trimmed.startsWith(");"))) {
                currentTable = null;
            } else if (currentTable != null && !trimmed.startsWith("--")) {
                Matcher colMatcher = Pattern.compile("^`?([a-zA-Z0-9_]+)`?\\s+[A-Z]+").matcher(trimmed);
                if (colMatcher.find()) {
                    String col = colMatcher.group(1);
                    if (!col.equalsIgnoreCase("KEY") && !col.equalsIgnoreCase("INDEX") && !col.equalsIgnoreCase("CONSTRAINT") && !col.equalsIgnoreCase("UNIQUE")) {
                        v1TableColumns.get(currentTable).add(col);
                    }
                }
            }
        }

        List<String> violations = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")) {
            String filename = resource.getFilename();
            if ("V1__create_tables.sql".equals(filename)) continue;

            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            // ファイルごとではなく、文(statement)単位で情報スキーマ保護の有無をチェック
            String[] statements = content.split(";");
            for (String stmt : statements) {
                String trimmedStmt = stmt.trim();
                if (trimmedStmt.isEmpty()) continue;
                if (trimmedStmt.contains("information_schema") || trimmedStmt.contains("IF NOT EXISTS")) {
                    continue; // この文はガード付きなので安全
                }

                for (Map.Entry<String, List<String>> entry : v1TableColumns.entrySet()) {
                    String table = entry.getKey();
                    for (String col : entry.getValue()) {
                        Pattern addColPattern = Pattern.compile("ALTER\\s+TABLE\\s+`?" + table + "`?\\s+ADD\\s+(?:COLUMN\\s+)?`?" + col + "`?\\b", Pattern.CASE_INSENSITIVE);
                        if (addColPattern.matcher(trimmedStmt).find()) {
                            violations.add(filename + ": " + table + "." + col + " は既に V1 で定義されているため保護無しの ADD COLUMN は衝突します");
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), "V1で作成済みのカラムを後続マイグレーションで重複ADD COLUMNしています: " + violations);
    }

    @Test
    void Entityの全テーブルフィールドに対応するDBカラムが対象テーブルのマイグレーションSQL内に存在すること() throws Exception {
        List<Class<?>> entityClasses = List.of(
                com.ses.entity.Contract.class,
                com.ses.entity.BpCompany.class,
                com.ses.entity.BpTerms.class,
                com.ses.entity.EngineerBpAffiliation.class
        );

        List<String> allMigrationSqls = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
            allMigrationSqls.add(resource.getContentAsString(StandardCharsets.UTF_8));
        }
        String combinedSql = String.join("\n;\n", allMigrationSqls);

        List<String> missingFields = new ArrayList<>();
        for (Class<?> clazz : entityClasses) {
            com.baomidou.mybatisplus.annotation.TableName tn = clazz.getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class);
            String tableName = (tn != null) ? tn.value() : camelToSnake(clazz.getSimpleName());

            // 特定テーブルのDDL記述 (CREATE/ALTER TABLE ... ;) を集める
            StringBuilder tableDdl = new StringBuilder();
            Pattern pTable = Pattern.compile("(?:CREATE\\s+(?:TEMPORARY\\s+)?|ALTER\\s+)TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?" + tableName + "`?\\b[\\s\\S]*?;", Pattern.CASE_INSENSITIVE);
            Matcher mTable = pTable.matcher(combinedSql);
            while (mTable.find()) {
                tableDdl.append(mTable.group()).append("\n");
            }
            String ddlTextLower = tableDdl.toString().toLowerCase(java.util.Locale.ROOT);

            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                com.baomidou.mybatisplus.annotation.TableField tf = field.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class);
                if (tf != null && !tf.exist()) continue;

                String fieldName = field.getName();
                String colName = camelToSnake(fieldName);
                if (!ddlTextLower.contains(colName.toLowerCase(java.util.Locale.ROOT))) {
                    missingFields.add(clazz.getSimpleName() + " (" + tableName + ")." + fieldName + " -> " + colName);
                }
            }
        }

        assertTrue(missingFields.isEmpty(), "Entityに定義されているが対象テーブルのMigration DDL内に存在しないDBカラムがあります: " + missingFields);
    }

    @Test
    void INSERT文で指定されたカラムがテーブル定義内に存在すること() throws Exception {
        List<String> allMigrationSqls = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
            allMigrationSqls.add(resource.getContentAsString(StandardCharsets.UTF_8));
        }
        String combinedSql = String.join("\n;\n", allMigrationSqls);

        // INSERT INTO `table` (`col1`, `col2`, ...) または INSERT INTO `table` (col1, col2, ...)
        Pattern insertPattern = Pattern.compile("INSERT\\s+INTO\\s+`?([a-zA-Z0-9_]+)`?\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
        Matcher mInsert = insertPattern.matcher(combinedSql);

        List<String> invalidInserts = new ArrayList<>();
        while (mInsert.find()) {
            String tableName = mInsert.group(1);
            String[] columns = mInsert.group(2).split(",");

            // 対象テーブルの DDL (CREATE/ALTER TABLE ... ;) を抽出
            StringBuilder tableDdl = new StringBuilder();
            Pattern pTable = Pattern.compile("(?:CREATE\\s+(?:TEMPORARY\\s+)?|ALTER\\s+)TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?" + tableName + "`?\\b[\\s\\S]*?;", Pattern.CASE_INSENSITIVE);
            Matcher mTable = pTable.matcher(combinedSql);
            while (mTable.find()) {
                tableDdl.append(mTable.group()).append("\n");
            }
            String tableDdlLower = tableDdl.toString().toLowerCase(java.util.Locale.ROOT);

            for (String col : columns) {
                String cleanCol = col.replace("`", "").trim().toLowerCase(java.util.Locale.ROOT);
                if (cleanCol.isEmpty()) continue;
                if (!tableDdlLower.contains(cleanCol)) {
                    invalidInserts.add("テーブル " + tableName + " への INSERT 内の列 '" + cleanCol + "' は Migration DDL に定義されていません");
                }
            }
        }

        assertTrue(invalidInserts.isEmpty(), "INSERT文で存在しないカラムが指定されています: " + invalidInserts);
    }

    /**
     * マイグレーションが参照するテーブルは、いずれかのマイグレーションで作られていなければならない。
     *
     * <p>V73(CRM)の初版は権限seedを {@code FROM m_role r … r.role_code IN ('admin',…)} と書いていたが、
     * 本リポジトリに {@code m_role} は存在せず（ロールは {@code sys_user.role} の日本語literalで、
     * 権限は {@code t_role_menu(role, menu_id)}）、fresh / legacy を問わず
     * {@code ERROR 1146 Table 'm_role' doesn't exist} で全環境が起動不能になる。
     * 列名の誤りは既存の別testが拾うが、テーブル名の誤りは誰も拾っていなかったため追加する。
     */
    @Test
    void マイグレーションが参照するテーブルが定義済みであること() throws Exception {
        Map<String, List<String>> createdTables = createTableOccurrences();

        // UPDATE は対象外。`ON UPDATE CASCADE` / `ON DUPLICATE KEY UPDATE <col>` を
        // テーブル参照と誤検知するため、テーブル名が確実に来る位置だけを見る。
        Pattern referenced = Pattern.compile(
                "\\b(?:INSERT\\s+(?:IGNORE\\s+)?INTO|FROM|JOIN)\\s+`?([a-zA-Z_][a-zA-Z0-9_]*)`?",
                Pattern.CASE_INSENSITIVE);

        List<String> unknown = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql")) {
            String fileName = String.valueOf(resource.getFilename());
            String sql = stripComments(resource.getContentAsString(StandardCharsets.UTF_8));
            Matcher m = referenced.matcher(sql);
            while (m.find()) {
                String table = m.group(1).toLowerCase(java.util.Locale.ROOT);
                if (NON_TABLE_TOKENS.contains(table) || createdTables.containsKey(table)
                        || EXTERNAL_RUNBOOK_TABLES.contains(table)) {
                    continue;
                }
                unknown.add(fileName + ": " + table);
            }
        }

        assertTrue(unknown.isEmpty(),
                "db/migration のどこでも CREATE TABLE されていないテーブルを参照しています"
                        + "（実MySQLで ERROR 1146 になります）: " + unknown);
    }

    /**
     * 同じテーブルを2本のマイグレーションが作る場合、後から走る側は必ず
     * {@code IF NOT EXISTS} を持たなければならない。持たないと、V1統合baselineを適用する
     * 空DBだけが {@code ERROR 1050 Table already exists} で起動不能になる。
     *
     * <p>V1(素のCREATE) → V60/V67(IF NOT EXISTS) という既存の形は正しい。
     * V73の初版は逆にV1側もV73側もCRM 3テーブルを定義しており、この検査で落ちる。
     */
    @Test
    void 同一テーブルを再CREATEする後発マイグレーションはIF_NOT_EXISTSを持つこと() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : createTableOccurrences().entrySet()) {
            List<String> occurrences = entry.getValue();
            if (occurrences.size() <= 1) {
                continue;
            }
            // 先頭(=最も若いバージョン)以外はすべてガードが必要
            for (String later : occurrences.subList(1, occurrences.size())) {
                if (!later.endsWith("(guarded)")) {
                    violations.add(entry.getKey() + " -> " + occurrences);
                    break;
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "先に作られたテーブルを後発マイグレーションが保護無しでCREATEしています"
                        + "（空DBだけが ERROR 1050 で起動不能になります）: " + violations);
    }

    /**
     * V73(CRM)は「V73だけが正」で fresh / legacy を同一スキーマへ収束させる設計。
     * V1へ同じ定義を書き戻すと空DBで衝突するため、V1側にCRMの定義が無いことを固定する。
     */
    @Test
    void V73のCRM定義はV1統合baselineへ二重に書かないこと() throws Exception {
        String v1 = stripComments(new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V1__create_tables.sql")
                .getContentAsString(StandardCharsets.UTF_8));

        for (String forbidden : List.of(
                "t_customer_contact", "t_lead", "t_opportunity", "source_opportunity_id")) {
            assertFalse(v1.contains(forbidden),
                    "V1へ " + forbidden + " を書くとV73と重複し空DBが起動しません（V73だけを正にしてください）");
        }

        String v6 = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V6__create_sales_activity.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String forbidden : List.of("contact_id", "opportunity_id", "assignee_user_id")) {
            assertFalse(stripComments(v6).contains(forbidden),
                    "適用済みのV6を編集するとchecksumが壊れます。t_sales_activityの拡張列はV73で追加してください: "
                            + forbidden);
        }
    }

    /**
     * V73は「テーブル作成 → 参照する列の追加 → 移行backfill → メニューseed」の順でなければ、
     * 実MySQLで FK 先が未作成／列が未追加のまま参照して落ちる。
     */
    @Test
    void V73はテーブル作成の後に参照列追加とbackfillを行うこと() throws Exception {
        String sql = stripComments(v73());

        int createContact = sql.indexOf("CREATE TABLE IF NOT EXISTS t_customer_contact");
        int createOpportunity = sql.indexOf("CREATE TABLE IF NOT EXISTS t_opportunity");
        int alterActivity = sql.indexOf("ALTER TABLE t_sales_activity");
        int alterProject = sql.indexOf("ALTER TABLE t_project");
        int backfill = sql.indexOf("INSERT INTO t_customer_contact");
        int menuSeed = sql.indexOf("INSERT IGNORE INTO m_menu");

        assertTrue(createContact >= 0 && createOpportunity >= 0, "V73にCRMテーブルの作成がありません");
        assertTrue(alterActivity > createOpportunity,
                "t_sales_activityのFKはt_customer_contact/t_opportunityを作ってから張ってください");
        assertTrue(alterProject > createOpportunity,
                "t_project.source_opportunity_idのFKはt_opportunityを作ってから張ってください");
        assertTrue(backfill > createContact, "移行backfillはt_customer_contactを作ってから実行してください");
        assertTrue(menuSeed > 0, "V73にCRMメニューのseedがありません");
        assertFalse(sql.contains("STORED"),
                "V73の生成列もVIRTUALに揃えてください（既存DBのALTERがERROR 1215になります）");
    }

    @Test
    void CRMのrollback手順は現行migration履歴を全て戻すこと() throws Exception {
        String ledger = java.nio.file.Files.readString(
                java.nio.file.Path.of(".kiro/specs/crm-contact-opportunity/review-ledger.md"),
                StandardCharsets.UTF_8);
        int rollback = ledger.indexOf("### Rollback");
        assertTrue(rollback >= 0, "CRM rollback手順がledgerに存在するはず");
        String section = ledger.substring(rollback);
        for (String version : List.of("73", "74", "74.1", "74.2", "74.3")) {
            assertTrue(section.contains("DELETE FROM flyway_schema_history WHERE version = '" + version + "';"),
                    "rollbackにV" + version + "履歴の削除が必要です");
        }
        assertTrue(section.contains("DELETE FROM flyway_schema_history WHERE script = 'R__crm_contact_reconciliation.sql';"),
                "rollbackにRepeatable履歴の削除が必要です");
        assertTrue(section.indexOf("version = '74.3'") < section.indexOf("version = '74.2'"),
                "rollbackはV74.3をV74.2より先に履歴から除去するはずです");
    }

    /**
     * V73のメニュー権限は design §6.2 のとおり 管理者 / マネージャー / 営業 のみ。
     * HR・要員へ広げると顧客の商機情報が本来見えない主体へ露出する。
     */
    @Test
    void V73のCRMメニューは営業系3ロールにだけ付与すること() throws Exception {
        String sql = stripComments(v73());

        assertTrue(sql.contains("'crm-lead'") && sql.contains("'crm-opportunity'"),
                "V73にCRMメニューのmenu_keyがありません");
        assertTrue(sql.contains("'/crm/leads'") && sql.contains("'/api/crm/leads'"),
                "m_menuにはpath_prefix/api_prefixの両方が必要です（MenuPermissionFilterが両方を見ます）");

        int roleSeed = sql.indexOf("INSERT IGNORE INTO t_role_menu");
        assertTrue(roleSeed >= 0, "V73にt_role_menuのseedがありません");
        String roleSection = sql.substring(roleSeed);
        assertTrue(roleSection.contains("'管理者'") && roleSection.contains("'マネージャー'")
                        && roleSection.contains("'営業'"),
                "CRMメニューは管理者/マネージャー/営業へ付与してください");
        assertFalse(roleSection.contains("'HR'") || roleSection.contains("'要員'"),
                "design §6.2によりHR/要員へCRMメニューを付与してはいけません");
    }

    /**
     * migrationが {@code m_menu} へ登録する {@code api_prefix} は、必ず
     * {@link com.ses.service.security.ActionPermissionResolver#resolve} が
     * action keyを返せるものでなければならない。
     *
     * <p>{@code resolve()} は URIから機械導出しているように見えて実際は
     * {@code RESOURCE_NAMES} への明示登録制で、未登録rootでは null を返す。
     * null になると {@code MenuPermissionFilter} は
     * (1) {@code /api/**} をそのまま deny() し、
     * (2) pageも matchedMenu の api_prefix から再解決して null なら deny() する。
     * この deny は<b>管理者bypassより前</b>にあるため、menuを登録した瞬間に
     * <b>管理者を含む全roleが403</b>になる（menuを登録しない方がマシ、という壊れ方をする）。
     *
     * <p>V73(CRM)がこれを踏んだ（R08 Round 2 CRM-R2-P1-01）。同じ事故は
     * 新しいURI prefixを導入するspecごとに再発しうるので、prefixの追加側で静的に固定する。
     */
    @Test
    void migrationが登録するメニューのapi_prefixがaction_keyへ解決できること() throws Exception {
        // m_menu を対象にしたDML文の中に現れる '/api/...' リテラルを集める
        Pattern menuStatement = Pattern.compile(
                "(?:INSERT|REPLACE)\\s+(?:IGNORE\\s+)?INTO\\s+`?m_menu`?[\\s\\S]*?;|"
                        + "UPDATE\\s+`?m_menu`?[\\s\\S]*?;",
                Pattern.CASE_INSENSITIVE);
        Pattern apiLiteral = Pattern.compile("'(/api/[^']*)'");

        Map<String, String> unresolved = new LinkedHashMap<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql")) {
            String fileName = String.valueOf(resource.getFilename());
            String sql = stripComments(resource.getContentAsString(StandardCharsets.UTF_8));
            Matcher stmt = menuStatement.matcher(sql);
            while (stmt.find()) {
                Matcher lit = apiLiteral.matcher(stmt.group());
                while (lit.find()) {
                    String apiPrefix = lit.group(1);
                    if (com.ses.service.security.ActionPermissionResolver.resolve("GET", apiPrefix) == null) {
                        unresolved.put(fileName + ": " + apiPrefix, apiPrefix);
                    }
                }
            }
        }

        assertTrue(unresolved.isEmpty(),
                "m_menuへ登録するapi_prefixがActionPermissionResolverで解決できません"
                        + "（MenuPermissionFilterが管理者を含む全roleを403にします）。"
                        + "ActionPermissionResolver.RESOURCE_NAMES へrootを登録してください: "
                        + unresolved.keySet());
    }

    /**
     * メニューを足したら、その resource の権限seedも足さなければならない。
     *
     * <p>V66_1が非管理者groupから全局 {@code *} を削除して「既知resource wildcardの列挙」へ
     * 置換したため、権限modelは baseline+deny ではなく<b>許可listの列挙制</b>になっている。
     * 新しいresource rootを {@code RESOURCE_NAMES} へ登録しても
     * {@code t_permission_group_action} へ {@code <resource>.*} をseedしなければ、
     * group割当済みの営業/HR/マネージャーはその機能全体で403になる。
     *
     * <p>V67(document)だけがこれを守っており、V68/V69(search/task/saved-view/batch-operation)と
     * V70(bp-company)は出荷済みなのにseedが無かった（S08 Round 2 で発見、V74で補完）。
     * 同じ漏れをS07以降で繰り返さないよう静的に固定する。
     */
    @Test
    void メニューを持つresourceには権限seedがあること() throws Exception {
        // 管理者専用resource。menuはあるが非管理者groupへは意図的に付与しない。
        java.util.Set<String> adminOnly = java.util.Set.of("user", "permission", "audit");

        List<String> allSql = new ArrayList<>();
        Map<String, String> resourceOrigin = new LinkedHashMap<>();

        Pattern menuStatement = Pattern.compile(
                "(?:INSERT|REPLACE)\\s+(?:IGNORE\\s+)?INTO\\s+`?m_menu`?[\\s\\S]*?;",
                Pattern.CASE_INSENSITIVE);
        Pattern apiLiteral = Pattern.compile("'(/api/[^']*)'");

        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql")) {
            String fileName = String.valueOf(resource.getFilename());
            String sql = stripComments(resource.getContentAsString(StandardCharsets.UTF_8));
            allSql.add(sql);
            Matcher stmt = menuStatement.matcher(sql);
            while (stmt.find()) {
                Matcher lit = apiLiteral.matcher(stmt.group());
                while (lit.find()) {
                    String actionKey = com.ses.service.security.ActionPermissionResolver
                            .resolve("GET", lit.group(1));
                    if (actionKey == null) {
                        continue; // 別testが検出する
                    }
                    String res = actionKey.substring(0, actionKey.indexOf('.'));
                    resourceOrigin.putIfAbsent(res, fileName + " (" + lit.group(1) + ")");
                }
            }
        }
        String combined = String.join("\n", allSql);

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : resourceOrigin.entrySet()) {
            if (adminOnly.contains(entry.getKey())) {
                continue;
            }
            if (!combined.contains("'" + entry.getKey() + ".*'")) {
                missing.add(entry.getKey() + " <- " + entry.getValue());
            }
        }

        assertTrue(missing.isEmpty(),
                "m_menuへ登録したresourceの権限seed（t_permission_group_action の '<resource>.*'）が"
                        + "どのマイグレーションにもありません。group割当済みの非管理者が当該機能全体で403になります: "
                        + missing);
    }

    /** {@code FROM}/{@code JOIN} の直後に現れうる、テーブル名ではない語。
     * {@code flyway_schema_history} はどのマイグレーションもCREATEしない
     * Flyway自身の管理テーブルだが、実行順序上R__より前に必ず存在するため除外する。 */
    /** Flyway migrationが参照し、Flyway外の運用runbookが作成するstaging table。 */
    private static final Set<String> EXTERNAL_RUNBOOK_TABLES = Set.of(
            "m_accounting_legacy_freee_preflight_v105_4");

    private static final java.util.Set<String> NON_TABLE_TOKENS = java.util.Set.of(
            "information_schema", "dual", "select", "if", "not", "exists", "flyway_schema_history",
            "json_table");

    /**
     * db/migration 全体の {@code CREATE TABLE} を「テーブル名 -> 出現箇所」で集める。
     * {@code IF NOT EXISTS} 付きの出現は末尾に {@code (guarded)} を付ける。
     */
    private Map<String, List<String>> createTableOccurrences() throws Exception {
        Pattern create = Pattern.compile(
                "CREATE\\s+(?:TEMPORARY\\s+)?TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?`?([a-zA-Z_][a-zA-Z0-9_]*)`?",
                Pattern.CASE_INSENSITIVE);

        // ファイル名の辞書順ではなくFlywayの適用順（バージョン昇順）で走査する。
        // 「先に作った側」「後から再CREATEする側」の判定がファイル名順だと逆転する（V6 < V60 は辞書順で逆）。
        List<Resource> ordered = new ArrayList<>(List.of(new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql")));
        ordered.sort(java.util.Comparator.comparingDouble(MigrationScriptIntegrityTest::versionOf));

        Map<String, List<String>> tables = new LinkedHashMap<>();
        for (Resource resource : ordered) {
            String fileName = String.valueOf(resource.getFilename());
            String sql = stripComments(resource.getContentAsString(StandardCharsets.UTF_8));
            Matcher m = create.matcher(sql);
            while (m.find()) {
                String table = m.group(2).toLowerCase(java.util.Locale.ROOT);
                String occurrence = fileName + (m.group(1) != null ? " (guarded)" : "");
                tables.computeIfAbsent(table, k -> new ArrayList<>()).add(occurrence);
            }
        }
        return tables;
    }

    /** ファイル名からFlywayバージョンを取り出す（Repeatable等は末尾扱い）。 */
    private static double versionOf(Resource resource) {
        Matcher m = VERSIONED.matcher(String.valueOf(resource.getFilename()));
        if (!m.matches()) {
            return Double.MAX_VALUE;
        }
        try {
            return Double.parseDouble(m.group(1).replace('_', '.'));
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }

    /** {@code --} 行コメントを落とす（大文字化はしない）。 */
    private String stripComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String v73() throws Exception {
        return new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V73__crm_contact_lead_opportunity.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static String camelToSnake(String str) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                result.append('_').append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

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


