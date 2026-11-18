package com.ses.migration;

import com.ses.config.ProductionAdminInitialPasswordFlywayCallback;
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
 * 本番の専用Flyway jobの設定（{@code ops/flyway/flyway-prod.conf}）をtestから読むためのhelper。
 *
 * <p>jobは {@code mvn -Pprod-migrate} でこのfileを {@code flyway.configFiles} として読み、接続情報と
 * 初期管理者パスワードhashを環境変数（{@code FLYWAY_*}）で上書きする。testも同じfileを同じparser
 * （Flyway Maven pluginが使う {@link ConfigUtils#loadConfigurationFile}）で読み、同じkeyへ値を入れて
 * {@link FluentConfiguration#configuration(Map)} に渡す。testの側でFlyway設定を書き直さないことで、
 * jobとtestの等価性を構造的に保つ（W1-B3 review §9'）。
 */
final class ProductionFlywayJobConfig {

    static final Path CONFIG_FILE = Path.of("ops", "flyway", "flyway-prod.conf");

    /** 環境変数 {@code FLYWAY_PLACEHOLDERS_ADMIN_INITIAL_PASSWORD_HASH} が写されるFlywayの設定key。 */
    static final String ADMIN_HASH_KEY =
            "flyway.placeholders." + ProductionAdminInitialPasswordFlywayCallback.PLACEHOLDER;

    private ProductionFlywayJobConfig() {
    }

    /** 設定fileのkey/value（Flyway Maven pluginと同じparser）。 */
    static Map<String, String> load() {
        File file = CONFIG_FILE.toFile();
        assertTrue(file.isFile(), "本番Flyway jobの設定fileが存在すること: " + CONFIG_FILE);
        return new LinkedHashMap<>(ConfigUtils.loadConfigurationFile(file, "UTF-8", true));
    }

    static List<String> locations(Map<String, String> conf) {
        return Arrays.stream(conf.getOrDefault("flyway.locations", "").split(","))
                .map(String::trim)
                .filter(location -> !location.isEmpty())
                .toList();
    }

    /**
     * jobと同じ設定に、環境変数で渡す値（接続情報・初期管理者hash）だけを足したFlyway設定。
     *
     * @param adminHash 初期管理者パスワードhash。{@code null} なら placeholder を設定しない（環境変数未設定）
     */
    static FluentConfiguration configure(String url, String user, String password, String adminHash) {
        Map<String, String> conf = load();
        if (adminHash != null) {
            conf.put(ADMIN_HASH_KEY, adminHash);
        }
        return Flyway.configure()
                .configuration(conf)
                .dataSource(url, user, password);
    }
}
