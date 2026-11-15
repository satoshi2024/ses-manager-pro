package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.internal.configuration.ConfigUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF07 Journal の専用Flyway job の設定（{@code ops/flyway/flyway-privacy-journal.conf}）をtestから読むためのhelper。
 *
 * <p>jobは {@code mvn -Pprivacy-journal-migrate} でこのfileを {@code flyway.configFiles} として読み、接続情報を
 * 環境変数（{@code FLYWAY_URL} / {@code FLYWAY_USER} / {@code FLYWAY_PASSWORD}）で上書きする。testも同じfileを同じparser
 * （Flyway Maven pluginが使う {@link ConfigUtils#loadConfigurationFile}）で読み、接続情報だけを足して使う
 * （{@link ProductionFlywayJobConfig} と同じ考え方。testの側でFlyway設定を書き直さない）。
 * 実MySQLのtest（別package）からも使うためpublicにする。
 */
public final class PrivacyJournalFlywayJobConfig {

    public static final Path CONFIG_FILE = Path.of("ops", "flyway", "flyway-privacy-journal.conf");

    public static final String LOCATION = "classpath:db/privacy-journal";

    public static final String HISTORY_TABLE = "flyway_schema_history_privacy_journal";

    private PrivacyJournalFlywayJobConfig() {
    }

    /** 設定fileのkey/value（Flyway Maven pluginと同じparser）。 */
    public static Map<String, String> load() {
        File file = CONFIG_FILE.toFile();
        assertTrue(file.isFile(), "Journal用Flyway jobの設定fileが存在すること: " + CONFIG_FILE);
        return new LinkedHashMap<>(ConfigUtils.loadConfigurationFile(file, "UTF-8", true));
    }

    public static List<String> locations(Map<String, String> conf) {
        return Arrays.stream(conf.getOrDefault("flyway.locations", "").split(","))
                .map(String::trim)
                .filter(location -> !location.isEmpty())
                .toList();
    }

    /** jobと同じ設定に、環境変数で渡す接続情報だけを足したFlyway設定。 */
    public static FluentConfiguration configure(String url, String user, String password) {
        return Flyway.configure()
                .configuration(load())
                .dataSource(url, user, password);
    }
}
