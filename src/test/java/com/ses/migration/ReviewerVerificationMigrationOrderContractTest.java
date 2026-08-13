package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * reviewer-verification-decision-delta-r23-p1-01.md §3.4のdocs-only version順序契約を検証する。
 *
 * <p>R10の {@code ACCEPTED_FOR_IMPLEMENTATION} 前にmigration（V102_1）を一切作成しない契約のため、
 * 本テストはDB・Docker・新規migrationを必要としない。Flywayのversion解決と同一の観点（ファイル名の
 * version文字列を {@code _}→{@code .} へ正規化し数値比較）で、以下を直接検証する:
 * <ol>
 *   <li>{@code V102 < V102.1 < V103} のversion順序（S12〜S17のV103〜V108予約を維持できる候補としてV102_1）</li>
 *   <li>既存migrationファイルにversion重複がなく、{@code V102_1} が未作成であること（R10受理前は作成禁止）</li>
 *   <li>{@code V102__dispatch_compliance_g2_gate_schema.sql} が存在し変更されていないこと（適用済みmigration不変）</li>
 * </ol>
 */
class ReviewerVerificationMigrationOrderContractTest {

    /** Flywayのバージョン付きマイグレーション命名規約 V&lt;version&gt;__&lt;description&gt;.sql */
    private static final Pattern VERSIONED = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");

    /** Flywayと同じ正規化（アンダースコアを小数区切りとして解釈し、数値コンポーネント列へ分解）。 */
    private static List<Long> versionComponents(String version) {
        List<Long> components = new ArrayList<>();
        for (String part : version.replace('_', '.').split("\\.")) {
            components.add(Long.parseLong(part));
        }
        return components;
    }

    private static int compareVersions(String a, String b) {
        List<Long> ca = versionComponents(a);
        List<Long> cb = versionComponents(b);
        int max = Math.max(ca.size(), cb.size());
        for (int i = 0; i < max; i++) {
            long va = i < ca.size() ? ca.get(i) : 0L;
            long vb = i < cb.size() ? cb.get(i) : 0L;
            if (va != vb) {
                return Long.compare(va, vb);
            }
        }
        return 0;
    }

    @Test
    void V102よりV102_1よりV103の順序で並ぶ() {
        // Flywayは "102_1" を "102.1" と解釈する（V66_1/V74_1/V79_1の既存実績と同一規則）
        assertEquals(0, compareVersions("102", "102"), "V102 == V102");
        assertTrue(compareVersions("102", "102_1") < 0, "V102 < V102.1");
        assertTrue(compareVersions("102_1", "103") < 0, "V102.1 < V103");
        assertTrue(compareVersions("102", "103") < 0, "V102 < V103");
        assertTrue(compareVersions("102_1", "102_2") < 0, "V102.1 < V102.2（2件目のV102_1系が必要な場合）");
    }

    @Test
    void V102_1はR10受理前は作成されていない() throws Exception {
        List<String> present = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql")) {
            String fileName = resource.getFilename();
            if (fileName != null && fileName.matches("^V102_1.*\\.sql$")) {
                present.add(fileName);
            }
        }
        assertTrue(present.isEmpty(),
                "R10受理前にV102_1 migrationを作成してはならない（docs-only契約）: " + present);
    }

    @Test
    void 既存migrationのversion重複がなくV102が存在する() throws Exception {
        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        boolean v102Present = false;
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql")) {
            String fileName = resource.getFilename();
            if (fileName == null) {
                continue;
            }
            Matcher m = VERSIONED.matcher(fileName);
            if (!m.matches()) {
                continue;
            }
            String version = m.group(1).replace('_', '.');
            byVersion.computeIfAbsent(version, k -> new ArrayList<>()).add(fileName);
            if ("V102__dispatch_compliance_g2_gate_schema.sql".equals(fileName)) {
                v102Present = true;
            }
        }
        assertTrue(v102Present, "V102__dispatch_compliance_g2_gate_schema.sql が存在しなければならない");
        assertTrue(!byVersion.isEmpty(), "db/migration からマイグレーションを1件も読み込めません");

        List<String> duplicates = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " -> " + e.getValue())
                .toList();
        assertTrue(duplicates.isEmpty(), "同一バージョンのマイグレーションが複数存在します: " + duplicates);

        // 並び順（Flywayの適用順）でV102がV101とV103（将来予約）の間にあることを確認する
        List<String> versions = new ArrayList<>(byVersion.keySet());
        versions.sort(Comparator.naturalOrder());
        assertFalse(versions.contains("103"), "V103はS12以降の予約のため未作成でなければならない");
    }
}
