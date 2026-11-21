package com.ses.backup.quiesce;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 試験の context と backup の書込み静止の静止応答の関係を、試験の source から静的に固定する（DB・Docker 不要）。
 *
 * <p>prod の profile の context では静止応答が instance を登録し（{@code enabled=true} は ARMED、test 設定を import して
 * {@code enabled=false} なら停止口の DISABLED）、heartbeat・要求の読取り・保存期間の削除の thread を動かし続ける。cache された
 * context が共有の {@code jdbc:h2:mem:testdb} でこれを動かすと、他の試験が書いた要求の行に REFUSED を返す等、実行順で結果が変わる
 * （RB-QUIESCE 2a review P3-5）。そこで prod の profile の {@code @SpringBootTest} は、class 専用の H2（{@code testdb} 以外）と
 * {@code @DirtiesContext}（class の後に閉じて thread を止める）を持つか、{@code mysql} の tag（専用の container）を持つこと。
 * BWQ-007 で、全ての {@code @SpringBootTest} が test profile か {@code app.backup-quiesce.enabled} の明示を持つことを足す。
 */
class BackupQuiesceTestContextInventoryTest {

    private static final Path TEST_JAVA = Path.of("src/test/java");
    private static final Pattern SPRING_BOOT_TEST = Pattern.compile("(?m)^\\s*@SpringBootTest\\b");
    private static final Pattern PROD_PROFILE = Pattern.compile(
            "@ActiveProfiles\\s*\\(\\s*(?:value\\s*=\\s*|profiles\\s*=\\s*)?\\{?[^)]*\"prod\"|spring\\.profiles\\.active\\s*=\\s*prod\\b");
    private static final Pattern H2_URL = Pattern.compile("spring\\.datasource\\.url\\s*=\\s*jdbc:h2:mem:([A-Za-z0-9_\\-]+)");
    private static final Pattern DIRTIES_CONTEXT = Pattern.compile("@(?:[\\w.]*\\.)?DirtiesContext\\b");
    private static final Pattern MYSQL_TAG = Pattern.compile("@Tag\\s*\\(\\s*\"mysql\"\\s*\\)");

    @Test
    void prodのprofileのSpringBootTestは専用のDBとDirtiesContextを持つ() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(TEST_JAVA)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources.put(TEST_JAVA.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        }

        List<String> prodContexts = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        sources.forEach((path, source) -> {
            // この class の fixture（text block の中の注釈）は対象にしない
            if (!path.endsWith("/BackupQuiesceTestContextInventoryTest.java") && isProdSpringBootTest(source)) {
                prodContexts.add(path);
                if (!isIsolated(source)) {
                    violations.add(path);
                }
            }
        });

        assertThat(prodContexts).as("prod の profile の @SpringBootTest を見つけられない（走査の空振り）")
                .contains("com/ses/config/ProductionSecurityConfigurationTest.java");
        assertThat(violations)
                .as("prod の profile の @SpringBootTest が共有の testdb で静止応答の thread を動かします。class 専用の H2 と "
                        + "@DirtiesContext を持たせてください")
                .isEmpty();
    }

    @Test
    void 規則は共有のtestdbと閉じないcontextを拒否する() {
        String prodShared = """
                @SpringBootTest(properties = "spring.config.import=optional:classpath:application-test.yml")
                @ActiveProfiles("prod")
                class A {}
                """;
        String prodNamedTestdb = """
                @SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:testdb;MODE=MySQL")
                @ActiveProfiles({"prod"})
                @DirtiesContext
                class B {}
                """;
        String prodNotDirtied = """
                @SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:own_db;MODE=MySQL")
                @ActiveProfiles(value = "prod")
                class C {}
                """;
        String prodIsolated = """
                @SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:own_db;MODE=MySQL")
                @ActiveProfiles("prod")
                @org.springframework.test.annotation.DirtiesContext(classMode = AFTER_CLASS)
                class D {}
                """;
        String prodMysql = """
                @Tag("mysql")
                @SpringBootTest(properties = "spring.profiles.active=prod")
                class E {}
                """;
        String testProfile = """
                @SpringBootTest
                @ActiveProfiles("test")
                class F {}
                """;

        assertThat(isProdSpringBootTest(prodShared) && !isIsolated(prodShared)).isTrue();
        assertThat(isProdSpringBootTest(prodNamedTestdb) && !isIsolated(prodNamedTestdb)).isTrue();
        assertThat(isProdSpringBootTest(prodNotDirtied) && !isIsolated(prodNotDirtied)).isTrue();
        assertThat(isProdSpringBootTest(prodIsolated) && isIsolated(prodIsolated)).isTrue();
        assertThat(isProdSpringBootTest(prodMysql) && isIsolated(prodMysql)).isTrue();
        assertThat(isProdSpringBootTest(testProfile)).isFalse();
    }

    static boolean isProdSpringBootTest(String source) {
        return SPRING_BOOT_TEST.matcher(source).find() && PROD_PROFILE.matcher(source).find();
    }

    static boolean isIsolated(String source) {
        if (MYSQL_TAG.matcher(source).find()) {
            return true;
        }
        Matcher url = H2_URL.matcher(source);
        boolean dedicated = false;
        while (url.find()) {
            if ("testdb".equalsIgnoreCase(url.group(1))) {
                return false;
            }
            dedicated = true;
        }
        return dedicated && DIRTIES_CONTEXT.matcher(source).find();
    }
}
