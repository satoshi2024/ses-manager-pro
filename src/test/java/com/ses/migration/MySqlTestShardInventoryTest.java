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

    /**
     * source に対応する compile 済みの試験の class が JUnit の {@code @Tag("mysql")} を持つか（注釈を reflection で読む）。
     * 以前は source の文字列の {@code contains} で見ていたため、text block の fixture の注釈まで数えていた（RB-QUIESCE 2b review
     * P3-9）。class は初期化しない（Testcontainers の container の static field を作らない）。{@code @Tag} は {@code @Inherited}
     * なので、基底 class から受け継いだ tag も JUnit の選択と同じく数える。抽象 class は JUnit が実行しないため数えない。
     */
    private boolean hasMySqlTag(Path source) {
        String relative = TEST_SOURCE_ROOT.relativize(source).toString().replace('\\', '/');
        if (relative.endsWith("package-info.java") || relative.endsWith("module-info.java")) {
            return false;
        }
        String className = relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
        Class<?> type;
        try {
            type = Class.forName(className, false, getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("試験の class を読み込めません（test-compile の後に流す）: " + className, e);
        }
        if (java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
            return false;
        }
        return taggedMySql(type);
    }

    /**
     * class・その method・JUnit が実行する入れ子の class（{@code @Nested} の static でない内部 class）のどれかに mysql の tag が
     * あるか。fixture の static な入れ子の class（{@code ContainerBoundContextCloseListenerTest} 等）は JUnit が実行しないため数えない。
     */
    private static boolean taggedMySql(Class<?> type) {
        if (containsMySql(type.getAnnotationsByType(org.junit.jupiter.api.Tag.class))) {
            return true;
        }
        // method の単位の tag（例: 1件だけ実 MySQL の並行の試験を持つ class）も、従来どおり class を shard に載せる
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            if (containsMySql(method.getAnnotationsByType(org.junit.jupiter.api.Tag.class))) {
                return true;
            }
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (nested.isAnnotationPresent(org.junit.jupiter.api.Nested.class)
                    && !java.lang.reflect.Modifier.isStatic(nested.getModifiers()) && taggedMySql(nested)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMySql(org.junit.jupiter.api.Tag[] tags) {
        for (org.junit.jupiter.api.Tag tag : tags) {
            if ("mysql".equals(tag.value())) {
                return true;
            }
        }
        return false;
    }
}
