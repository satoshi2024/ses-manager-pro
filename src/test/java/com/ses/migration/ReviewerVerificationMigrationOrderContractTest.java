package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R23-P1-01 §3.1/§3.4のmigration順序契約を検証する（Step 1指示により
 * 「V102_1不存在を恒久assertする旧test」から置換した実装版）。
 *
 * <p>検証内容:
 * <ol>
 *   <li>V102 blob/checksum golden: V102ファイルが変更されていないこと（V102はpublished/immutable）</li>
 *   <li>V102_1が存在すること</li>
 *   <li>Flyway MigrationVersionとして V102 &lt; V102.1 &lt; V103 の順序が成立すること</li>
 *   <li>既存migrationにversion重複がないこと</li>
 * </ol>
 *
 * <p>V102 blob goldenは、レビュー済みHead `75ba33e4`（accepted docs）時点のV102 blob
 * （`e8a6152055027f9f16a5028f31641472582d5375`）を正本として保持する。
 * V102に変更を加える実装が混入した場合、このテストが即座に失敗する。
 */
class ReviewerVerificationMigrationOrderContractTest {

    /** Flywayのバージョン付きマイグレーション命名規約 V&lt;version&gt;__&lt;description&gt;.sql */
    private static final Pattern VERSIONED = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");

    /** accepted docs Head（75ba33e4）時点のV102 blob。V102はpublished/immutableのため不変。 */
    private static final String V102_GOLDEN_BLOB = "e8a6152055027f9f16a5028f31641472582d5375";

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

    private static List<String> versionedFiles() throws Exception {
        List<String> files = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql")) {
            String fileName = resource.getFilename();
            if (fileName != null && VERSIONED.matcher(fileName).matches()) {
                files.add(fileName);
            }
        }
        return files;
    }

    @Test
    void V102のblobがgoldenと一致し変更されていない() throws Exception {
        String blob = null;
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V102__dispatch_compliance_g2_gate_schema.sql")) {
            byte[] bytes = resource.getInputStream().readAllBytes();
            // gitはcore.autocrlf設定でLFへ正規化してblob化するため、CRLF→LF正規化後に
            // 「blob <size>\0 + 内容」のSHA-1を計算してgoldenと比較する。
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            byte[] normalized = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] header = ("blob " + normalized.length + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] canonical = new byte[header.length + normalized.length];
            System.arraycopy(header, 0, canonical, 0, header.length);
            System.arraycopy(normalized, 0, canonical, header.length, normalized.length);
            blob = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-1").digest(canonical));
        }
        assertEquals(V102_GOLDEN_BLOB, blob,
                "V102 blobがaccepted docs時点と異なる。V102はpublished/immutableのため変更禁止");
    }

    @Test
    void V102_1が存在しV102よりV102_1よりV103の順序で並ぶ() throws Exception {
        List<String> files = versionedFiles();
        assertTrue(files.stream().anyMatch(f -> f.startsWith("V102_1__")),
                "V102_1__reviewer_verification_events.sql が存在しなければならない");

        // Flywayは "102_1" を "102.1" と解釈する（V66_1/V74_1/V79_1の既存実績と同一規則）
        assertEquals(0, compareVersions("102", "102"), "V102 == V102");
        assertTrue(compareVersions("102", "102_1") < 0, "V102 < V102.1");
        assertTrue(compareVersions("102_1", "103") < 0, "V102.1 < V103");
        assertTrue(compareVersions("102", "103") < 0, "V102 < V103");
    }

    @Test
    void 既存migrationのversion重複がなくV102_1が一意である() throws Exception {
        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        for (String fileName : versionedFiles()) {
            Matcher m = VERSIONED.matcher(fileName);
            assertTrue(m.matches(), "命名規約に一致しないファイル: " + fileName);
            String version = m.group(1).replace('_', '.');
            byVersion.computeIfAbsent(version, k -> new ArrayList<>()).add(fileName);
        }
        assertTrue(!byVersion.isEmpty(), "db/migration からマイグレーションを1件も読み込めません");

        List<String> duplicates = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " -> " + e.getValue())
                .toList();
        assertTrue(duplicates.isEmpty(), "同一バージョンのマイグレーションが複数存在します: " + duplicates);

        List<String> v1021 = byVersion.getOrDefault("102.1", List.of());
        assertEquals(1, v1021.size(), "V102.1は1ファイルでなければならない");
    }
}
