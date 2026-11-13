package com.ses.migration;

import com.ses.config.LegacyDatabaseFlywayCallback;
import com.ses.config.ProductionAdminInitialPasswordFlywayCallback;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本番の専用Flyway job（配備決定P-3/部署-2）の設定と、prodのアプリ起動設定をDocker無しで固定する。
 *
 * <ul>
 *   <li>job設定（{@code ops/flyway/flyway-prod.conf}）: classpath location・callback・baseline等。</li>
 *   <li>POM: flyway pluginのDB driver依存と、{@code prod-migrate} profileがjob設定だけを読むこと。</li>
 *   <li>prodのアプリ: {@code spring.flyway.enabled=false}（起動時migrate禁止）とguardのlocationsがjobと同じこと。</li>
 *   <li>prod専用script: 初期管理者hashのplaceholderとV2 sample削除の安全条件。</li>
 * </ul>
 * 実MySQLへの適用結果は {@link FlywayProdFreshInstallMySqlTest} が同じjob設定で検証する。
 */
class ProductionFlywayJobConfigurationTest {

    private static final String PROD_LOCATION = "classpath:db/migration-prod";
    private static final Path SAMPLE_DATA_SCRIPT =
            Path.of("src/main/resources/db/migration-prod/R__remove_v2_sample_data.sql");

    /** job設定fileが持ってよいkey。増やすときは本testと {@link FlywayProdFreshInstallMySqlTest} の再現範囲を見直す。 */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "flyway.locations",
            "flyway.failOnMissingLocations",
            "flyway.callbacks",
            "flyway.outOfOrder",
            "flyway.baselineOnMigrate",
            "flyway.baselineVersion",
            "flyway.baselineDescription",
            "flyway.cleanDisabled");

    @Test
    void job設定はclasspath_locationと既知のkeyだけを持ちsecretを含まない() {
        Map<String, String> conf = ProductionFlywayJobConfig.load();

        assertThat(new TreeSet<>(conf.keySet()))
                .as("接続情報・placeholder（secret）は環境変数で渡し、設定fileへ書かないこと")
                .isEqualTo(new TreeSet<>(KNOWN_KEYS));
        assertThat(ProductionFlywayJobConfig.locations(conf))
                .as("Java migration（V74_3）とcallback classを解決するためclasspath locationであること")
                .containsExactly("classpath:db/migration", PROD_LOCATION);
        assertThat(conf.get("flyway.failOnMissingLocations")).isEqualTo("true");
        assertThat(conf.get("flyway.outOfOrder")).isEqualTo("false");
        assertThat(conf.get("flyway.baselineOnMigrate")).isEqualTo("true");
        assertThat(conf.get("flyway.baselineVersion")).isEqualTo("9");
        assertThat(conf.get("flyway.baselineDescription")).isEqualTo("手動SQL(001-009)適用済みベースライン");
        assertThat(conf.get("flyway.cleanDisabled")).isEqualTo("true");
    }

    @Test
    void callbackはclass名から読み込めて初期管理者hashの検査が先頭にある() {
        Callback[] callbacks = Flyway.configure()
                .configuration(ProductionFlywayJobConfig.load())
                .getCallbacks();

        assertThat(Arrays.stream(callbacks).map(callback -> callback.getClass().getName()).toList())
                .containsExactly(ProductionAdminInitialPasswordFlywayCallback.class.getName(),
                        LegacyDatabaseFlywayCallback.class.getName());
    }

    /**
     * 旧来のprod（Spring Bootの起動時migrate）はmain codeの {@link Callback} beanを全て登録していた。
     * jobでも同じcallbackが動くよう、bean化されたcallbackがjob設定から漏れていないことを固定する。
     */
    @Test
    void main_codeのFlyway_Callback_beanはjob設定へ全て登録されている() throws Exception {
        Set<String> configured = new TreeSet<>(Arrays.asList(
                ProductionFlywayJobConfig.load().get("flyway.callbacks").split("\\s*,\\s*")));

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        String root = LegacyDatabaseFlywayCallback.class.getProtectionDomain().getCodeSource().getLocation().toString();
        String pattern = (root.endsWith("/") ? root : root + "/") + "com/ses/**/*.class";
        Set<String> callbackBeans = new TreeSet<>();
        for (Resource resource : resolver.getResources(pattern)) {
            String className = factory.getMetadataReader(resource).getClassMetadata().getClassName();
            Class<?> type;
            try {
                type = Class.forName(className, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                continue; // optional依存が無いclassはbeanにもならない
            }
            if (Callback.class.isAssignableFrom(type) && !type.isInterface()
                    && AnnotatedElementUtils.hasAnnotation(type, Component.class)) {
                callbackBeans.add(className);
            }
        }

        assertThat(callbackBeans).isNotEmpty().contains(LegacyDatabaseFlywayCallback.class.getName());
        assertThat(configured).containsAll(callbackBeans);
    }

    @Test
    void prodのアプリは起動時migrateせずguardのlocationsはjobと同じ() {
        Properties base = yaml("application.yml");
        Properties prod = new Properties();
        prod.putAll(base);
        prod.putAll(yaml("application-prod.yml"));
        Map<String, String> conf = ProductionFlywayJobConfig.load();

        assertThat(value(prod, "spring.flyway.enabled"))
                .as("配備決定P-3: prodのアプリinstanceはmigrateしない")
                .isEqualTo("false");
        assertThat(value(base, "spring.flyway.enabled"))
                .as("dev/testの起動時migrate（application.yml）は従来どおり")
                .isEqualTo("true");
        assertThat(splitLocations(value(prod, "spring.flyway.locations")))
                .as("起動時schema guardはjobと同じlocationsでartifactのmigrationを解決する")
                .isEqualTo(ProductionFlywayJobConfig.locations(conf));
        assertThat(value(prod, "spring.flyway.out-of-order")).isEqualTo(conf.get("flyway.outOfOrder"));
        assertThat(value(yaml("application-test.yml"), "spring.flyway.enabled"))
                .as("testはH2 schemaを直接投入しFlywayを使わない（従来どおり）")
                .isEqualTo("false");
    }

    /** YAMLのbool/数値はStringではないため、getPropertyではなく文字列化して比較する。 */
    private static String value(Properties properties, String key) {
        Object value = properties.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @Test
    void pomのflyway_pluginはDB_driverを持ちprod_migrate_profileだけがjob設定を読む() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("pom.xml"));
        Element project = pom.getDocumentElement();

        Element basePlugin = flywayPlugin(child(child(project, "build"), "plugins"));
        assertThat(basePlugin).as("build/pluginsにflyway-maven-pluginがあること").isNotNull();
        List<String> dependencies = new ArrayList<>();
        for (Element dependency : children(child(basePlugin, "dependencies"), "dependency")) {
            dependencies.add(text(dependency, "groupId") + ":" + text(dependency, "artifactId"));
        }
        assertThat(dependencies)
                .as("Flyway 10はDB対応pluginを自身のclassloaderから探すため、plugin依存に置くこと")
                .contains("org.flywaydb:flyway-mysql", "com.mysql:mysql-connector-j");
        assertThat(child(basePlugin, "configuration").getTextContent())
                .as("local開発向けの既定configurationから本番locationを実行できないこと")
                .doesNotContain("migration-prod");

        Element profile = null;
        for (Element candidate : children(child(project, "profiles"), "profile")) {
            if ("prod-migrate".equals(text(candidate, "id"))) {
                profile = candidate;
            }
        }
        assertThat(profile).as("prod-migrate profileがあること").isNotNull();
        Element profilePlugin = flywayPlugin(child(child(profile, "build"), "plugins"));
        Element configuration = child(profilePlugin, "configuration");
        assertThat(configuration.getAttribute("combine.self"))
                .as("local開発向けconfiguration（filesystem location等）を引き継がないこと")
                .isEqualTo("override");
        List<String> configFiles = new ArrayList<>();
        for (Element configFile : children(child(configuration, "configFiles"), "configFile")) {
            configFiles.add(configFile.getTextContent().trim());
        }
        assertThat(configFiles).containsExactly("${project.basedir}/ops/flyway/flyway-prod.conf");
        List<String> configured = new ArrayList<>();
        for (Element element : children(configuration, null)) {
            configured.add(element.getTagName());
        }
        assertThat(configured)
                .as("接続情報・placeholderはPOMを経由させず環境変数で渡すこと")
                .containsExactly("configFiles");
    }

    @Test
    void V2_sample削除はRepeatableで一度だけ_新規導入直後だけ_DMLだけで実行する() throws Exception {
        String sql = Files.readString(SAMPLE_DATA_SCRIPT, StandardCharsets.UTF_8);
        String statements = withoutComments(sql).toUpperCase(java.util.Locale.ROOT);

        assertThat(sql).contains("WHERE script = 'R__remove_v2_sample_data.sql' AND success = 1");
        assertThat(sql).contains("THEN 'FRESH'");
        for (String forbidden : List.of("CREATE ", "ALTER ", "DROP ", "TRUNCATE ", "DELIMITER", "PROCEDURE")) {
            assertThat(statements)
                    .as("1つのtransactionで適用されるようDMLだけで書くこと（暗黙commitを起こさない）: %s", forbidden)
                    .doesNotContain(forbidden);
        }
        // 全DELETEが判定結果（FRESH）で限定されていること
        List<String> deletes = Arrays.stream(statements.split(";"))
                .map(String::trim)
                .filter(statement -> statement.startsWith("DELETE"))
                .toList();
        assertThat(deletes).hasSize(10);
        assertThat(deletes).allMatch(statement -> statement.contains("@SES_V2_SAMPLE_STATE = 'FRESH'"));
        assertThat(sql).doesNotContain("${");
    }

    // ---------------------------------------------------------------- helper

    private static Properties yaml(String name) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(name));
        Properties properties = factory.getObject();
        assertThat(properties).as(name).isNotNull().isNotEmpty();
        return properties;
    }

    private static List<String> splitLocations(String locations) {
        return Arrays.stream(String.valueOf(locations).split(","))
                .map(String::trim)
                .filter(location -> !location.isEmpty())
                .toList();
    }

    private static String withoutComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Element flywayPlugin(Element plugins) {
        for (Element plugin : children(plugins, "plugin")) {
            if ("flyway-maven-plugin".equals(text(plugin, "artifactId"))) {
                return plugin;
            }
        }
        return null;
    }

    private static Element child(Element parent, String name) {
        List<Element> found = children(parent, name);
        assertThat(found).as("<%s> under <%s>", name, parent == null ? "null" : parent.getTagName()).hasSize(1);
        return found.get(0);
    }

    /** 直下の子要素（{@code name} が {@code null} なら全て）。 */
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && (name == null || name.equals(element.getTagName()))) {
                result.add(element);
            }
        }
        return result;
    }

    private static String text(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0).getTextContent().trim();
    }
}
