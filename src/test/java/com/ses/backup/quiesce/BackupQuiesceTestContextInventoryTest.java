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
 *
 * <p>BWQ-007（BWQ-RQ-008・design.md §12.2）: 全ての {@code @SpringBootTest} は test profile（{@code application-test.yml} の
 * {@code enabled=false} で INERT。基底 class の {@code @ActiveProfiles} を含む）か、{@code app.backup-quiesce.enabled} の明示を
 * 持つこと。prod の profile では明示が必須で、{@code enabled=false}（停止口。ticket 無し）を明示した context は prod の起動時検査が
 * 止めるため {@code @MockBean BackupQuiesceProductionValidator} を持つこと。
 */
class BackupQuiesceTestContextInventoryTest {

    private static final Path TEST_JAVA = Path.of("src/test/java");
    private static final Pattern SPRING_BOOT_TEST = Pattern.compile("(?m)^\\s*@SpringBootTest\\b");
    private static final Pattern PROD_PROFILE = Pattern.compile(
            "@ActiveProfiles\\s*\\(\\s*(?:value\\s*=\\s*|profiles\\s*=\\s*)?\\{?[^)]*\"prod\"|spring\\.profiles\\.active\\s*=\\s*prod\\b");
    private static final Pattern TEST_PROFILE = Pattern.compile(
            "@ActiveProfiles\\s*\\(\\s*(?:value\\s*=\\s*|profiles\\s*=\\s*)?\\{?[^)]*\"test\"");
    private static final Pattern EXPLICIT_QUIESCE = Pattern.compile("app\\.backup-quiesce\\.enabled\\s*=\\s*(true|false)\\b");
    private static final Pattern VALIDATOR_MOCK = Pattern.compile(
            "@MockBean\\s+(?:private\\s+)?(?:[\\w.]+\\.)?BackupQuiesceProductionValidator\\b");
    private static final Pattern EXTENDS = Pattern.compile("\\bclass\\s+\\w+(?:<[^>]*>)?\\s+extends\\s+([\\w.]+)");
    private static final Pattern H2_URL = Pattern.compile("spring\\.datasource\\.url\\s*=\\s*jdbc:h2:mem:([A-Za-z0-9_\\-]+)");
    private static final Pattern DIRTIES_CONTEXT = Pattern.compile("@(?:[\\w.]*\\.)?DirtiesContext\\b");
    private static final Pattern MYSQL_TAG = Pattern.compile("@Tag\\s*\\(\\s*\"mysql\"\\s*\\)");
    /** 基底 class を辿る深さの上限。 */
    private static final int MAX_SUPER_DEPTH = 5;

    @Test
    void 全てのSpringBootTestはtest_profileか静止の有効無効の明示を持ちprodの停止口はvalidatorをMockBeanにする()
            throws IOException {
        Map<String, String> sources = testSources();
        Map<String, String> bySimpleName = bySimpleName(sources);

        List<String> contexts = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        sources.forEach((path, source) -> {
            if (path.endsWith("/BackupQuiesceTestContextInventoryTest.java") || !SPRING_BOOT_TEST.matcher(source).find()) {
                return;
            }
            contexts.add(path);
            String violation = quiesceDeclarationViolation(source, bySimpleName);
            if (violation != null) {
                violations.add(path + ": " + violation);
            }
        });

        assertThat(contexts).as("@SpringBootTest を見つけられない（走査の空振り）").hasSizeGreaterThan(300)
                .contains("com/ses/config/ProductionSecurityConfigurationTest.java",
                        "com/ses/integration/ProjectTenantIsolationIntegrationTest.java");
        assertThat(violations)
                .as("@SpringBootTest の context が静止応答の thread を動かすかどうかを明示していません（test profile にするか、"
                        + "app.backup-quiesce.enabled を明示し、prod の enabled=false は BackupQuiesceProductionValidator を "
                        + "@MockBean にする）")
                .isEmpty();
    }

    @Test
    void 静止の明示の規則はprofileと明示と基底classとMockBeanを見る() {
        Map<String, String> bases = Map.of("BaseTestContext", """
                @SpringBootTest
                @ActiveProfiles("test")
                abstract class BaseTestContext {}
                """, "BaseNoProfile", """
                abstract class BaseNoProfile {}
                """);
        String testProfile = """
                @SpringBootTest
                @ActiveProfiles({"test"})
                class A {}
                """;
        String inherited = """
                @SpringBootTest(properties = "app.security.oidc.tenant-id=")
                class B extends BaseTestContext {}
                """;
        String noProfile = """
                @SpringBootTest
                class C extends BaseNoProfile {}
                """;
        String explicitWithoutProfile = """
                @SpringBootTest(properties = "app.backup-quiesce.enabled=true")
                class D {}
                """;
        String prodImplicit = """
                @SpringBootTest(properties = "spring.config.import=optional:classpath:application-test.yml")
                @ActiveProfiles("prod")
                class E {}
                """;
        String prodDisabledWithoutMock = """
                @SpringBootTest(properties = "app.backup-quiesce.enabled=false")
                @ActiveProfiles("prod")
                class F {}
                """;
        String prodDisabledWithMock = """
                @SpringBootTest(properties = "app.backup-quiesce.enabled=false")
                @ActiveProfiles("prod")
                class G {
                    @MockBean
                    private com.ses.backup.quiesce.BackupQuiesceProductionValidator validator;
                }
                """;
        String prodEnabled = """
                @SpringBootTest(properties = "app.backup-quiesce.enabled=true")
                @ActiveProfiles(value = "prod")
                class H {}
                """;
        String prodAndTest = """
                @SpringBootTest
                @ActiveProfiles({"test", "prod"})
                class I {}
                """;

        assertThat(quiesceDeclarationViolation(testProfile, bases)).isNull();
        assertThat(quiesceDeclarationViolation(inherited, bases)).isNull();
        assertThat(quiesceDeclarationViolation(noProfile, bases)).isNotNull();
        assertThat(quiesceDeclarationViolation(explicitWithoutProfile, bases)).isNull();
        assertThat(quiesceDeclarationViolation(prodImplicit, bases)).isNotNull();
        assertThat(quiesceDeclarationViolation(prodDisabledWithoutMock, bases)).isNotNull();
        assertThat(quiesceDeclarationViolation(prodDisabledWithMock, bases)).isNull();
        assertThat(quiesceDeclarationViolation(prodEnabled, bases)).isNull();
        assertThat(quiesceDeclarationViolation(prodAndTest, bases)).as("prod を含めば test profile と数えない").isNotNull();
    }

    /** 規則の違反の理由（違反が無ければ null）。 */
    static String quiesceDeclarationViolation(String source, Map<String, String> bySimpleName) {
        Matcher explicit = EXPLICIT_QUIESCE.matcher(source);
        boolean declared = explicit.find();
        if (isProdSpringBootTest(source)) {
            if (!declared) {
                return "prod の profile で app.backup-quiesce.enabled の明示が無い";
            }
            if ("false".equals(explicit.group(1)) && !VALIDATOR_MOCK.matcher(source).find()) {
                return "prod の profile の enabled=false で BackupQuiesceProductionValidator の @MockBean が無い";
            }
            return null;
        }
        if (declared || hasTestProfile(source, bySimpleName, 0)) {
            return null;
        }
        return "test profile でも app.backup-quiesce.enabled の明示でもない";
    }

    private static boolean hasTestProfile(String source, Map<String, String> bySimpleName, int depth) {
        if (PROD_PROFILE.matcher(source).find()) {
            return false;
        }
        if (TEST_PROFILE.matcher(source).find()) {
            return true;
        }
        Matcher parent = EXTENDS.matcher(source);
        if (depth >= MAX_SUPER_DEPTH || !parent.find()) {
            return false;
        }
        String name = parent.group(1);
        String parentSource = bySimpleName.get(name.substring(name.lastIndexOf('.') + 1));
        return parentSource != null && hasTestProfile(parentSource, bySimpleName, depth + 1);
    }

    private static Map<String, String> testSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(TEST_JAVA)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources.put(TEST_JAVA.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return sources;
    }

    /** 単純名から source へ（同じ単純名が複数あれば最初のもの。基底 class の解決に使う）。 */
    private static Map<String, String> bySimpleName(Map<String, String> sources) {
        Map<String, String> byName = new TreeMap<>();
        sources.forEach((path, source) -> {
            String file = path.substring(path.lastIndexOf('/') + 1);
            byName.putIfAbsent(file.substring(0, file.length() - ".java".length()), source);
        });
        return byName;
    }

    @Test
    void prodのprofileのSpringBootTestは専用のDBとDirtiesContextを持つ() throws IOException {
        Map<String, String> sources = testSources();

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
        // MySqlTestShardInventoryTest は compile 済みの class の注釈で数えるため、text block の中の tag は数えない（2b review P3-9）
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
