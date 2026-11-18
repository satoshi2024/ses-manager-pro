package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF07 Journal インスタンスの専用Flyway job（NF07-A1J、journal-witness-grant-design.md §2.5）の設定をDocker無しで固定する
 * （{@link ProductionFlywayJobConfigurationTest} と同型）。
 *
 * <ul>
 *   <li>job設定（{@code ops/flyway/flyway-privacy-journal.conf}）: locationは {@code classpath:db/privacy-journal} だけ、
 *       履歴表は {@code flyway_schema_history_privacy_journal}、applicationのcallbackを持たない、secretを含まない。</li>
 *   <li>POM: {@code privacy-journal-migrate} profileがjob設定だけを読み、plugin依存をbaseから継承する。
 *       application DBのjob（{@code prod-migrate}）とは設定fileを共有しない。</li>
 *   <li>application側: applicationのjob・起動時guard・dev/testの起動時migrateがJournalのlocationを含まない。</li>
 * </ul>
 */
class PrivacyJournalFlywayJobConfigurationTest {

    private static final String PROFILE_ID = "privacy-journal-migrate";
    private static final String CONFIG_FILE_REFERENCE = "${project.basedir}/ops/flyway/flyway-privacy-journal.conf";

    /** job設定fileが持ってよいkey。増やすときは本testとJournalの実MySQL testの再現範囲を見直す。 */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "flyway.locations",
            "flyway.failOnMissingLocations",
            "flyway.table",
            "flyway.baselineOnMigrate",
            "flyway.outOfOrder",
            "flyway.cleanDisabled");

    @Test
    void job設定はJournalのlocationと既知のkeyだけを持ちsecretを含まない() throws Exception {
        Map<String, String> conf = PrivacyJournalFlywayJobConfig.load();

        assertThat(new TreeSet<>(conf.keySet()))
                .as("接続情報（secret）は環境変数で渡し、設定fileへ書かないこと。callbackも書かないこと")
                .isEqualTo(new TreeSet<>(KNOWN_KEYS));
        assertThat(PrivacyJournalFlywayJobConfig.locations(conf))
                .as("Journal用locationだけで、applicationのdb/migration・db/migration-prodを含めない")
                .containsExactly(PrivacyJournalFlywayJobConfig.LOCATION);
        assertThat(conf.get("flyway.table")).isEqualTo(PrivacyJournalFlywayJobConfig.HISTORY_TABLE);
        assertThat(conf.get("flyway.failOnMissingLocations")).isEqualTo("true");
        assertThat(conf.get("flyway.baselineOnMigrate")).as("空のJournal schemaへV1から適用する").isEqualTo("false");
        assertThat(conf.get("flyway.outOfOrder")).isEqualTo("false");
        assertThat(conf.get("flyway.cleanDisabled")).isEqualTo("true");

        String raw = Files.readString(PrivacyJournalFlywayJobConfig.CONFIG_FILE, StandardCharsets.UTF_8);
        String settings = withoutComments(raw).toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("jdbc:", "flyway.url", "flyway.user", "flyway.password",
                "flyway.placeholders", "flyway.callbacks", "db/migration")) {
            assertThat(settings).as("設定行に %s を書かないこと", forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    void Flywayへ渡した設定はJournalの履歴表とlocationだけでapplicationのcallbackを持たない() {
        FluentConfiguration configuration = Flyway.configure().configuration(PrivacyJournalFlywayJobConfig.load());

        assertThat(configuration.getCallbacks())
                .as("applicationのcallback（初期管理者hash検査・legacy DB補償）をJournalのjobで動かさない")
                .isEmpty();
        assertThat(Arrays.stream(configuration.getLocations()).map(Location::getDescriptor).toList())
                .containsExactly(PrivacyJournalFlywayJobConfig.LOCATION);
        assertThat(configuration.getTable()).isEqualTo(PrivacyJournalFlywayJobConfig.HISTORY_TABLE);
        assertThat(configuration.isCleanDisabled()).isTrue();
        assertThat(configuration.isBaselineOnMigrate()).isFalse();
        assertThat(configuration.isOutOfOrder()).isFalse();
    }

    @Test
    void Journal用migrationはclasspathのlocationから解決できる() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(PrivacyJournalFlywayJobConfig.LOCATION + "/*.sql");

        assertThat(Arrays.stream(resources).map(Resource::getFilename).toList())
                .as("compile後のtarget/classesからjobが解決できること")
                .contains("V1__privacy_journal_core.sql");
    }

    @Test
    void applicationのjobと起動時guardと起動時migrateはJournalのlocationを含まない() {
        assertThat(ProductionFlywayJobConfig.locations(ProductionFlywayJobConfig.load()))
                .as("application DBのjobはJournalのDDLを適用しない")
                .noneMatch(location -> location.contains("privacy-journal"));
        assertThat(ProductionFlywayJobConfig.load().get("flyway.table"))
                .as("application DBのjobは既定の履歴表を使う").isNull();

        Properties base = yaml("application.yml");
        Properties prod = new Properties();
        prod.putAll(base);
        prod.putAll(yaml("application-prod.yml"));
        for (Properties properties : List.of(base, prod)) {
            Object locations = properties.get("spring.flyway.locations");
            assertThat(locations == null ? "" : String.valueOf(locations))
                    .as("dev/testの起動時migrateとprodの起動時schema guardはJournalのDDLを見ない")
                    .doesNotContain("privacy-journal");
        }
    }

    @Test
    void pomのprivacy_journal_migrate_profileはJournalのjob設定だけを読みplugin依存を継承する() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("pom.xml"));
        Element project = pom.getDocumentElement();

        Element journalProfile = profile(project, PROFILE_ID);
        assertThat(journalProfile).as("%s profileがあること", PROFILE_ID).isNotNull();
        Element plugin = flywayPlugin(child(child(journalProfile, "build"), "plugins"));
        assertThat(plugin).as("profileにflyway-maven-pluginがあること").isNotNull();
        Element configuration = child(plugin, "configuration");
        assertThat(configuration.getAttribute("combine.self"))
                .as("local開発向けconfiguration（filesystem location・接続情報のproperty）を引き継がないこと")
                .isEqualTo("override");
        List<String> configured = new ArrayList<>();
        for (Element element : children(configuration, null)) {
            configured.add(element.getTagName());
        }
        assertThat(configured).as("接続情報はPOMを経由させず環境変数で渡すこと").containsExactly("configFiles");
        assertThat(configFiles(configuration)).containsExactly(CONFIG_FILE_REFERENCE);
        assertThat(children(plugin, "dependencies"))
                .as("plugin依存（flyway-mysql・protobuf除外済みのdriver）はbase pluginから継承すること")
                .isEmpty();

        Element prodProfile = profile(project, "prod-migrate");
        assertThat(prodProfile).isNotNull();
        assertThat(configFiles(child(flywayPlugin(child(child(prodProfile, "build"), "plugins")), "configuration")))
                .as("application DBのjobはJournalの設定fileを読まない")
                .containsExactly("${project.basedir}/ops/flyway/flyway-prod.conf");
    }

    // ---------------------------------------------------------------- helper

    private static Properties yaml(String name) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(name));
        Properties properties = factory.getObject();
        assertThat(properties).as(name).isNotNull().isNotEmpty();
        return properties;
    }

    private static String withoutComments(String text) {
        return text.lines()
                .filter(line -> !line.trim().startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static List<String> configFiles(Element configuration) {
        List<String> files = new ArrayList<>();
        for (Element configFile : children(child(configuration, "configFiles"), "configFile")) {
            files.add(configFile.getTextContent().trim());
        }
        return files;
    }

    private static Element profile(Element project, String id) {
        for (Element candidate : children(child(project, "profiles"), "profile")) {
            if (id.equals(text(candidate, "id"))) {
                return candidate;
            }
        }
        return null;
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
