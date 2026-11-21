package com.ses.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 実MySQL testのimageが {@link MySqlTestImages} だけで決まることを、Docker無しで検証する。
 *
 * <p>image名が各test classで決まっていると、本番の版（MySQL 8.4 LTS）へ切り替えたときに一部のclassだけが
 * 旧版のまま残り、CIが「8.4で通った」と誤って示す。そのため、test source（{@link MySqlImageSourceScanner} の構造検査）、
 * CIのworkflow・script・pom（上書きと既定以外のimage）、offline simulation の3か所で再混入を止める。
 */
class MySqlTestImagesTest {

    private static final Path TEST_SOURCE_ROOT = Path.of("src/test/java");
    private static final Path IMAGES_SOURCE = TEST_SOURCE_ROOT.resolve("com/ses/test/MySqlTestImages.java");
    private static final Path CI_WORKFLOW = Path.of(".github/workflows/ci.yml");
    private static final String EVIDENCE_STEP = "Verify MySQL tests ran on the default image";

    /**
     * CI の backup-integration job が起動する source/target の MySQL（実 PITR）。backup tool の MySQL 8.4 対応（RB-W）で
     * 既定 image と同じ 8.4.13 にしたため、例外なく既定 image を求める（以前は 8.0.36 の例外があった）。
     */
    private static final Path BACKUP_INTEGRATION_COMPOSE = Path.of("ops/backup/tests/docker-compose.integration.yml");

    /** workflow・script・pom で MySQL image の上書きとみなす書き方。 */
    private static final List<Pattern> OVERRIDES = List.of(
            Pattern.compile(Pattern.quote(MySqlTestImages.IMAGE_PROPERTY)),
            Pattern.compile(Pattern.quote(MySqlTestImages.FRESH_INSTALL_IMAGE_PROPERTY)),
            Pattern.compile("(?m)^\\s*(?:-\\s*)?" + MySqlTestImages.IMAGE_ENV + "\\s*:"),
            Pattern.compile("(?:(?<!\\$)\\{|,)\\s*" + MySqlTestImages.IMAGE_ENV + "\\s*:"),
            Pattern.compile(MySqlTestImages.IMAGE_ENV + "\\s*="),
            Pattern.compile("SetEnvironmentVariable\\s*\\(\\s*[\"']" + MySqlTestImages.IMAGE_ENV));

    /** workflow・script内のMySQL image参照（{@code jdbc:mysql:} などは対象外）。group 1 がtag。 */
    private static final Pattern IMAGE_IN_TEXT =
            Pattern.compile("(?<![\\w./:@-])(?:docker\\.io/)?(?:library/)?mysql:([0-9A-Za-z][\\w.-]*)");
    private static final Pattern REMOVED_OPTION = Pattern.compile("--default[-_]authentication[-_]plugin");

    // ---- test source の構造検査 ----

    @Test
    void test_sourceはMySQL_imageをMySqlTestImages以外から決めない() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        int constructors = 0;
        try (Stream<Path> paths = Files.walk(TEST_SOURCE_ROOT)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                if (source.normalize().equals(IMAGES_SOURCE.normalize())) {
                    continue;
                }
                String text = Files.readString(source, StandardCharsets.UTF_8);
                scanned++;
                constructors += MySqlImageSourceScanner.constructorCount(text);
                for (String violation : MySqlImageSourceScanner.violations(text)) {
                    offenders.add(source + ":" + violation);
                }
            }
        }
        assertTrue(scanned > 100, "test sourceを走査できていません: " + scanned + "件");
        assertTrue(constructors > 100, "MySQLContainer の constructor を検出できていません（走査規則の誤り）: " + constructors + "件");
        assertTrue(offenders.isEmpty(),
                "MySQL imageは MySqlTestImages.image()（本番fresh-installは freshInstallImage()）から取得してください: "
                        + offenders);
    }

    @Test
    void 構造検査はconstructorにMySqlTestImages以外を渡す各書き方を検出する() {
        assertDetected("class A { Object c = new MySQLContainer<>(`mysql:8.0`); }");
        assertDetected("class A { Object c = new MySQLContainer<>(\n        `mysql:8.0`)\n        .withDatabaseName(`x`); }");
        assertDetected("class A { Object c = new MySQLContainer(`registry.invalid/db:1`); }");
        assertDetected("class A { Object c = new MySQLContainer<?>(IMAGE); }");
        assertDetected("class A { Object c = new org.testcontainers.containers.MySQLContainer<>(name()); }");
        assertDetected("class A { Object c = new com.ses.test.MySQLContainer<>(DockerImageName.parse(MySqlTestImages.image())); }");
    }

    @Test
    void 構造検査は無引数constructorを検出する() {
        assertDetected("class A { Object c = new MySQLContainer<>(); }");
        assertDetected("class A { Object c = new org.testcontainers.containers.MySQLContainer(\n    ); }");
    }

    @Test
    void 構造検査は同じfile内でMySqlTestImagesだけを代入した変数に限り許す() {
        assertClean("class A { static final String IMG = MySqlTestImages.freshInstallImage();"
                + " Object c = new MySQLContainer<>(IMG); }");
        assertDetected("class A { static final String IMG = System.getProperty(`x`); Object c = new MySQLContainer<>(IMG); }");
        assertDetected("class A { static String IMG = MySqlTestImages.image(); static { IMG = other(); }"
                + " Object c = new MySQLContainer<>(IMG); }");
    }

    @Test
    void 構造検査はimage文字列の各書き方を検出する() {
        assertDetected("class A { String s = `mysql:8.0`; }");
        assertDetected("class A { String s = `docker.io/library/mysql:8.0`; }");
        assertDetected("class A { String s = `library/mysql:8.0`; }");
        assertDetected("class A { String s = `mysql@sha256:0123`; }");
        assertDetected("class A { String s = `mysql:` + version; }");
        assertDetected("class A { String s = `mysql` + `:8.0`; }");
        assertDetected("class A { String s = `docker.io/library/` + `mysql`; }");
        assertDetected("class A { String s = String.format(`mysql:%s`, v); }");
        assertDetected("class A { String s = String.format(`%s:%s`, `mysql`, v); }");
        assertDetected("class A { String s = `%s:%s`.formatted(`mysql`, v); }");
        assertDetected("class A { String s = ```\n        mysql:8.0\n        ```; }");
    }

    @Test
    void 構造検査はDockerImageNameとimage定数とsubstituteを検出する() {
        assertDetected("class A { Object n = DockerImageName.parse(`mysql`).withTag(`8.0`); }");
        assertDetected("class A { Object n = DockerImageName.parse(`mysql/mysql-server:8.0`); }");
        assertDetected("class A { String s = MySQLContainer.NAME + `:` + MySQLContainer.DEFAULT_TAG; }");
        assertDetected("class A { Object n = DockerImageName.parse(x).asCompatibleSubstituteFor(`mysql`); }");
    }

    @Test
    void 構造検査は集中定義の呼出しとmysqlを含むだけの文字列やcommentを検出しない() {
        assertClean("class A { Object c = new MySQLContainer<>(MySqlTestImages.image()); }");
        assertClean("class A { Object c = new com.ses.test.MySQLContainer<>(\n        com.ses.test.MySqlTestImages.image())\n"
                + "        .withCommand(`--skip-log-bin`); }");
        assertClean("@Tag(`mysql`) class A { String u = `jdbc:mysql://host/db`; String t = `mysql-tests`; }");
        assertClean("class A { // new MySQLContainer<>(`mysql:8.0`)\n  /** {@code new MySQLContainer<>()} */\n"
                + "  String s = `new MySQLContainer<>(x)`; char q = '`'; String t = ```\n    SELECT 1\n    ```; }");
    }

    // ---- 既定値と解決順 ----

    @Test
    void 既定imageは本番と同じMySQL_8_4系の固定tagである() throws IOException {
        assertTrue(MySqlTestImages.DEFAULT_IMAGE.matches("^mysql:8\\.4\\.\\d+$"),
                "既定imageは 8.4 LTS のpatch版まで固定したtagにしてください: " + MySqlTestImages.DEFAULT_IMAGE);
        // CIの証拠stepは sed でこの1行を読む。
        List<String> definitions = Files.readAllLines(IMAGES_SOURCE, StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains("DEFAULT_IMAGE = \""))
                .toList();
        assertEquals(1, definitions.size(), "DEFAULT_IMAGE の定義行はちょうど1行にしてください: " + definitions);
        assertTrue(definitions.get(0).contains("\"" + MySqlTestImages.DEFAULT_IMAGE + "\""), definitions.get(0));
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

    // ---- CI ----

    @Test
    void CIの証拠stepは既定imageを読み既定以外のMySQL_imageがあれば失敗する() throws IOException {
        String ci = read(CI_WORKFLOW);
        int start = ci.indexOf("- name: " + EVIDENCE_STEP);
        assertTrue(start >= 0, "CIのMySQL jobに証拠step（" + EVIDENCE_STEP + "）がありません");
        int next = ci.indexOf("- name:", start + 1);
        String step = ci.substring(start, next < 0 ? ci.length() : next);
        assertTrue(step.contains(IMAGES_SOURCE.toString().replace('\\', '/')),
                "証拠stepは既定imageを MySqlTestImages から読むこと");
        assertTrue(step.contains("docker image inspect"), "証拠stepは既定imageのdigestを記録すること");
        assertTrue(step.contains("exit 1"), "証拠stepは既定以外のimageがあれば失敗すること");
        assertFalse(step.contains("|| true"), "証拠stepは失敗を握りつぶさないこと");
    }

    @Test
    void workflowとscriptとpomはMySQL_imageを上書きしない() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : ciFiles()) {
            String text = read(file);
            for (Pattern override : OVERRIDES) {
                Matcher matcher = override.matcher(text);
                while (matcher.find()) {
                    offenders.add(file + ":" + line(text, matcher.start()) + ": " + matcher.group().strip());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "CIではMySQL imageを上書きしない（8.4の証拠にならなくなる）: " + offenders);
    }

    @Test
    void 上書き検出はproperty_YAML_key_flow_shell_PowerShellの各書き方を拾い既定値展開は拾わない() {
        assertTrue(overridden("mvn -B test -Pmysql-tests -Dses.test.mysql.image=mysql:8.0"));
        assertTrue(overridden("mvn -B test -Dses.flyway.fresh-install.mysql-image=mysql:8.0.36"));
        assertTrue(overridden("    env:\n      SES_TEST_MYSQL_IMAGE: mysql:8.0\n"));
        assertTrue(overridden("    env: { SES_TEST_MYSQL_IMAGE: mysql:8.0 }\n"));
        assertTrue(overridden("export SES_TEST_MYSQL_IMAGE=mysql:8.0\n"));
        assertTrue(overridden("echo \"SES_TEST_MYSQL_IMAGE=mysql:8.0\" >> \"$GITHUB_ENV\"\n"));
        assertTrue(overridden("$env:SES_TEST_MYSQL_IMAGE = 'mysql:8.0'\n"));
        assertTrue(overridden("[Environment]::SetEnvironmentVariable('SES_TEST_MYSQL_IMAGE', 'mysql:8.0')\n"));
        assertFalse(overridden("          if [ -n \"${SES_TEST_MYSQL_IMAGE:-}\" ]; then\n"));
        assertFalse(overridden("            echo \"（${SES_TEST_MYSQL_IMAGE}）\"\n"));
    }

    @Test
    void CIが使うfileは既定以外のMySQL_imageを使わない() throws IOException {
        String defaultTag = defaultTag();
        List<String> offenders = new ArrayList<>();
        for (Path file : ciFiles()) {
            String text = read(file);
            Matcher matcher = IMAGE_IN_TEXT.matcher(text);
            while (matcher.find()) {
                if (!matcher.group(1).equals(defaultTag)) {
                    offenders.add(file + ":" + line(text, matcher.start()) + ": " + matcher.group());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "CIのworkflow・script・backup-integration の compose で既定以外のMySQL imageを使っています: " + offenders);
    }

    @Test
    void backup_integrationのsourceとtargetは既定imageをdigest固定で使う() throws IOException {
        assertTrue(ciFiles().contains(BACKUP_INTEGRATION_COMPOSE),
                "backup-integration の compose を既定以外のimageの検査対象に含めてください");
        String compose = read(BACKUP_INTEGRATION_COMPOSE);
        List<String> images = compose.lines()
                .filter(line -> line.strip().startsWith("image:") && line.contains("mysql"))
                .map(String::strip)
                .toList();
        // source と target は同じ anchor（x-mysql-common）から image を受け取る
        assertFalse(images.isEmpty(), "backup-integration の MySQL image（source/target）が見つかりません");
        for (String image : images) {
            assertTrue(image.matches("^image:\\s*" + Pattern.quote(MySqlTestImages.DEFAULT_IMAGE) + "@sha256:[0-9a-f]{64}$"),
                    "backup-integration の MySQL は既定image（" + MySqlTestImages.DEFAULT_IMAGE + "）を digest 固定で使ってください: "
                            + image);
        }
    }

    @Test
    void image参照の検出はjdbc_URLと既定tagを除く() {
        assertEquals(List.of("8.0.36"), tags("    image: mysql:8.0.36@sha256:0123\n"));
        assertEquals(List.of("8.0"), tags("docker pull docker.io/library/mysql:8.0\n"));
        assertEquals(List.of(), tags("- `NF01_PREFLIGHT_JDBC_URL`: an explicit `jdbc:mysql:` URL\n"));
        assertEquals(List.of(), tags("grep -E '^(docker\\.io/)?(library/)?mysql:' images.txt\n"));
    }

    // ---- offline simulation ----

    @Test
    void offline_simulationも同じ既定imageを使い8_4で削除された起動optionを渡さない() throws IOException {
        String compose = read(Path.of("infra/ecs/offline/docker-compose.yml"));
        assertTrue(compose.contains("image: ${SES_OFFLINE_MYSQL_IMAGE:-" + MySqlTestImages.DEFAULT_IMAGE + "}"),
                "offline simulationのMySQL既定imageがtestの既定値と食い違っています");
        assertFalse(passesRemovedOption(compose),
                "--default-authentication-plugin は MySQL 8.4 で削除されたため指定できません");

        String simulation = read(Path.of("infra/ecs/offline/verify-offline-sim.ps1"));
        assertTrue(simulation.contains("'" + MySqlTestImages.DEFAULT_IMAGE + "'"),
                "verify-offline-sim.ps1 の補助imageがcomposeの既定値と食い違っています");
    }

    @Test
    void 削除済みoptionの検査はblock_flow_文字列の各書き方を拾いcommentは除く() {
        assertTrue(passesRemovedOption("    command:\n      - --default-authentication-plugin=caching_sha2_password\n"));
        assertTrue(passesRemovedOption("    command: [\"--character-set-server=utf8mb4\", \"--default_authentication_plugin=x\"]\n"));
        assertTrue(passesRemovedOption("    command: --default-authentication-plugin=x --log-bin-trust-function-creators=1\n"));
        assertFalse(passesRemovedOption("    # --default-authentication-plugin は 8.4 で削除\n"
                + "    command:\n      - --log-bin-trust-function-creators=1  # 理由: --default-authentication-plugin\n"));
    }

    // ---- helpers ----

    /** sampleでは {@code `} を {@code "} として書く（本classの文字列literalが検査対象にならないようにするため）。 */
    private static String java(String sample) {
        return sample.replace('`', '"');
    }

    private static void assertDetected(String sample) {
        assertFalse(MySqlImageSourceScanner.violations(java(sample)).isEmpty(), "検出されるべき書き方: " + java(sample));
    }

    private static void assertClean(String sample) {
        assertEquals(List.of(), MySqlImageSourceScanner.violations(java(sample)), "検出されるべきでない書き方: " + java(sample));
    }

    private static boolean overridden(String text) {
        return OVERRIDES.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    private static List<String> tags(String text) {
        String defaultTag = defaultTag();
        return IMAGE_IN_TEXT.matcher(text).results()
                .map(result -> result.group(1))
                .filter(tag -> !tag.equals(defaultTag))
                .toList();
    }

    private static boolean passesRemovedOption(String yaml) {
        String withoutComments = yaml.lines()
                .map(line -> line.replaceFirst("(^|\\s)#.*$", ""))
                .collect(Collectors.joining("\n"));
        return REMOVED_OPTION.matcher(withoutComments).find();
    }

    private static String defaultTag() {
        return MySqlTestImages.DEFAULT_IMAGE.substring(MySqlTestImages.DEFAULT_IMAGE.indexOf(':') + 1);
    }

    /** CIが実行するworkflow・script・pom と、backup-integration job が起動する MySQL の compose。 */
    private static List<Path> ciFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : List.of(CI_WORKFLOW.getParent(), Path.of("scripts"))) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile).sorted().forEach(files::add);
            }
        }
        files.add(Path.of("pom.xml"));
        files.add(BACKUP_INTEGRATION_COMPOSE);
        return files;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static int line(String text, int position) {
        return (int) text.substring(0, position).chars().filter(ch -> ch == '\n').count() + 1;
    }
}
