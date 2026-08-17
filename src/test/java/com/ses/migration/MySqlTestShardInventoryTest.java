package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 実MySQL testとCI shard定義が常に一致することを、Docker無しで検証する。
 */
class MySqlTestShardInventoryTest {

    private static final Path TEST_SOURCE_ROOT = Path.of("src/test/java");
    private static final Path SHARD_ROOT = Path.of("scripts/test-suites");
    private static final int SHARD_COUNT = 3;

    @Test
    void mysqlタグ付きtestが重複なく全てCI_shardへ登録されていること() throws IOException {
        Set<String> taggedTests = findTaggedTests();
        Set<String> shardTests = new TreeSet<>();
        List<String> duplicates = new ArrayList<>();

        for (int shard = 1; shard <= SHARD_COUNT; shard++) {
            Path shardFile = SHARD_ROOT.resolve("mysql-shard-" + shard + ".txt");
            assertTrue(Files.isRegularFile(shardFile), "CI shard定義がありません: " + shardFile);

            for (String line : Files.readAllLines(shardFile)) {
                String testName = line.trim();
                if (testName.isEmpty() || testName.startsWith("#")) {
                    continue;
                }
                if (!shardTests.add(testName)) {
                    duplicates.add(testName);
                }
            }
        }

        assertTrue(duplicates.isEmpty(), "複数のMySQL shardへ重複登録されたtestがあります: " + duplicates);
        assertEquals(taggedTests, shardTests,
                "@Tag(\"mysql\") とCI shard定義が一致しません。追加・削除したtestをshardへ反映してください");
    }

    private Set<String> findTaggedTests() throws IOException {
        Set<String> tests = new TreeSet<>();
        try (var paths = Files.walk(TEST_SOURCE_ROOT)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(this::hasMySqlTag)
                    .map(path -> path.getFileName().toString().replaceFirst("\\.java$", ""))
                    .forEach(tests::add);
        }
        assertTrue(!tests.isEmpty(), "@Tag(\"mysql\") 付きtestが1件も見つかりません");
        return tests;
    }

    private boolean hasMySqlTag(Path source) {
        try {
            return Files.readString(source).contains("@Tag(\"mysql\")");
        } catch (IOException e) {
            throw new IllegalStateException("test sourceを読み込めません: " + source, e);
        }
    }
}
