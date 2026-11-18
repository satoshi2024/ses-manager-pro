package com.ses.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 実MySQL testのimageが {@link MySqlTestImages} だけで決まることを、Docker無しで検証する。
 *
 * <p>image名が各test classへ直書きされていると、本番の版（MySQL 8.4 LTS）へ切り替えたときに一部のclassだけが
 * 旧版のまま残り、CIが「8.4で通った」と誤って示す。そのためliteralの再混入をここで止める。
 */
class MySqlTestImagesTest {

    private static final Path TEST_SOURCE_ROOT = Path.of("src/test/java");
    private static final Path IMAGES_SOURCE = TEST_SOURCE_ROOT.resolve("com/ses/test/MySqlTestImages.java");

    /** 引用符で始まるMySQL imageのtag付きliteral（版番号・浮動tag）。 */
    private static final Pattern IMAGE_LITERAL = Pattern.compile("\"mysql:(?:\\d|latest\\b|lts\\b|innovation\\b)");
    /** container constructorへの文字列literal直渡し。 */
    private static final Pattern CONSTRUCTOR_LITERAL = Pattern.compile("MySQLContainer<>\\(\\s*\"");

    @Test
    void test_sourceにMySQL_imageのliteralを直書きしない() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        int usages = 0;
        try (Stream<Path> paths = Files.walk(TEST_SOURCE_ROOT)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                if (source.normalize().equals(IMAGES_SOURCE.normalize())) {
                    continue;
                }
                scanned++;
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (isHardcodedImage(line)) {
                        offenders.add(source + ":" + (i + 1) + ": " + line.trim());
                    }
                    if (line.contains("MySqlTestImages.image()") || line.contains("MySqlTestImages.freshInstallImage()")) {
                        usages++;
                    }
                }
            }
        }
        assertTrue(scanned > 100, "test sourceを走査できていません: " + scanned + "件");
        assertTrue(usages > 0, "MySqlTestImages の利用箇所が1件も見つかりません（走査対象の誤り）");
        assertTrue(offenders.isEmpty(),
                "MySQL imageは MySqlTestImages.image()（本番fresh-installは freshInstallImage()）から取得してください: "
                        + offenders);
    }

    @Test
    void 検出規則はliteral直書きを拾い集中定義の呼出しは拾わない() {
        // 本class自身が検出されないよう、検出対象の並びは連結で組み立てる。
        String quote = "\"";
        String ctor = "MySQLContainer<>" + "(";
        assertTrue(isHardcodedImage("new " + ctor + quote + "mysql" + ":8.0" + quote + ")"));
        assertTrue(isHardcodedImage("new com.ses.test." + ctor + quote + "mysql" + ":8.4.13" + quote + ")"));
        assertTrue(isHardcodedImage("String image = " + quote + "mysql" + ":latest" + quote + ";"));
        assertTrue(isHardcodedImage("new " + ctor + " " + quote + "registry.invalid/db:1" + quote + ")"));
        assertFalse(isHardcodedImage("new " + ctor + "MySqlTestImages.image())"));
        assertFalse(isHardcodedImage("new " + ctor + "MYSQL_IMAGE)"));
        assertFalse(isHardcodedImage(" * {@code mysql" + ":8.0.36} はローカルでの上書き例"));
    }

    @Test
    void 既定imageは本番と同じMySQL_8_4系の固定tagである() {
        assertTrue(MySqlTestImages.DEFAULT_IMAGE.matches("^mysql:8\\.4\\.\\d+$"),
                "既定imageは 8.4 LTS のpatch版まで固定したtagにしてください: " + MySqlTestImages.DEFAULT_IMAGE);
    }

    @Test
    void CIのMySQL_jobは既定imageで走りその証拠を残す() throws IOException {
        String ci = Files.readString(Path.of(".github/workflows/ci.yml"), StandardCharsets.UTF_8);
        assertTrue(ci.contains("src/test/java/com/ses/test/MySqlTestImages.java"),
                "CIのMySQL jobは既定imageを MySqlTestImages から読んで実行証拠を残すこと");
        assertFalse(ci.contains("-D" + MySqlTestImages.IMAGE_PROPERTY) || ci.contains(MySqlTestImages.IMAGE_ENV + "="),
                "CIではMySQL imageを上書きしない（8.4の証拠にならなくなる）");
    }

    @Test
    void offline_simulationも同じ既定imageを使い8_4で削除された起動optionを渡さない() throws IOException {
        String compose = Files.readString(Path.of("infra/ecs/offline/docker-compose.yml"), StandardCharsets.UTF_8);
        assertTrue(compose.contains("image: ${SES_OFFLINE_MYSQL_IMAGE:-" + MySqlTestImages.DEFAULT_IMAGE + "}"),
                "offline simulationのMySQL既定imageがtestの既定値と食い違っています");
        assertFalse(Pattern.compile("(?m)^\\s*-\\s*--default[-_]authentication[-_]plugin").matcher(compose).find(),
                "--default-authentication-plugin は MySQL 8.4 で削除されたため指定できません");

        String simulation = Files.readString(Path.of("infra/ecs/offline/verify-offline-sim.ps1"), StandardCharsets.UTF_8);
        assertTrue(simulation.contains("'" + MySqlTestImages.DEFAULT_IMAGE + "'"),
                "verify-offline-sim.ps1 の補助imageがcomposeの既定値と食い違っています");
    }

    @Test
    void 指定が無ければ既定imageを使う() {
        assertEquals(MySqlTestImages.DEFAULT_IMAGE, MySqlTestImages.resolve(name -> null, name -> null));
        assertEquals(MySqlTestImages.DEFAULT_IMAGE, MySqlTestImages.resolveFreshInstall(name -> null, name -> null));
    }

    @Test
    void system_propertyは環境変数より優先する() {
        Map<String, String> properties = Map.of(MySqlTestImages.IMAGE_PROPERTY, "prop-image:1");
        Map<String, String> environment = Map.of(MySqlTestImages.IMAGE_ENV, "env-image:1");
        assertEquals("prop-image:1", MySqlTestImages.resolve(properties::get, environment::get));
        assertEquals("env-image:1", MySqlTestImages.resolve(name -> null, environment::get));
    }

    @Test
    void 空白だけの指定は未指定として扱い前後の空白は除く() {
        Map<String, String> properties = Map.of(MySqlTestImages.IMAGE_PROPERTY, "  ");
        Map<String, String> environment = Map.of(MySqlTestImages.IMAGE_ENV, " env-image:2 ");
        assertEquals("env-image:2", MySqlTestImages.resolve(properties::get, environment::get));
        assertEquals(MySqlTestImages.DEFAULT_IMAGE,
                MySqlTestImages.resolve(properties::get, Map.of(MySqlTestImages.IMAGE_ENV, "")::get));
    }

    @Test
    void 本番fresh_installは従来propertyを最優先しそれ以外は共通の解決に従う() {
        Map<String, String> all = Map.of(
                MySqlTestImages.FRESH_INSTALL_IMAGE_PROPERTY, "alias-image:1",
                MySqlTestImages.IMAGE_PROPERTY, "prop-image:1");
        Map<String, String> environment = Map.of(MySqlTestImages.IMAGE_ENV, "env-image:1");
        assertEquals("alias-image:1", MySqlTestImages.resolveFreshInstall(all::get, environment::get));
        assertEquals("prop-image:1", MySqlTestImages.resolveFreshInstall(
                Map.of(MySqlTestImages.IMAGE_PROPERTY, "prop-image:1")::get, environment::get));
        assertEquals("env-image:1", MySqlTestImages.resolveFreshInstall(
                Map.of(MySqlTestImages.FRESH_INSTALL_IMAGE_PROPERTY, " ")::get, environment::get));
        assertEquals("prop-image:1", MySqlTestImages.resolve(all::get, environment::get),
                "fresh-install以外のtestは従来propertyを参照しない");
    }

    @Test
    void 実行時の解決はsystem_propertyと環境変数をそのまま読む() {
        String expected = MySqlTestImages.resolve(System::getProperty, System::getenv);
        assertEquals(expected, MySqlTestImages.image());
        assertFalse(MySqlTestImages.image().isBlank());
    }

    private static boolean isHardcodedImage(String line) {
        return IMAGE_LITERAL.matcher(line).find() || CONSTRUCTOR_LITERAL.matcher(line).find();
    }
}
