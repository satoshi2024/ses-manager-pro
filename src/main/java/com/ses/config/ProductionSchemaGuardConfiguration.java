package com.ses.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.List;

/**
 * 本番のFlyway起動方針（配備決定P-3: 全停止＋専用Flyway job。アプリinstanceはmigrationを実行しない）。
 *
 * <ul>
 *   <li>{@code application-prod.yml} は {@code spring.flyway.enabled=false}。誤って有効化された場合
 *       （環境変数 {@code SPRING_FLYWAY_ENABLED=true} 等）も、{@link #productionStartupMigrationRefusal()} が
 *       Spring BootのFlyway初期化でmigrateを拒否し、1件も適用せずに起動を止める。</li>
 *   <li>{@link ProductionSchemaVersionGuard} が、jobの適用結果とartifactのmigrationが一致するかを起動時に照合する。</li>
 * </ul>
 * dev/test profileには登録しない（従来どおり起動時migrate、またはH2 schemaの直接投入）。
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionSchemaGuardConfiguration {

    /** Spring Bootの既定と同じ（未設定時）。prodは application-prod.yml で db/migration-prod を加える。 */
    private static final List<String> DEFAULT_LOCATIONS = List.of("classpath:db/migration");

    @Bean
    public FlywayMigrationStrategy productionStartupMigrationRefusal() {
        return flyway -> {
            throw new IllegalStateException("[schema guard] prodではアプリ起動時のFlyway migrateを禁止しています"
                    + "（spring.flyway.enabled=false のまま起動し、migrationは専用Flyway job "
                    + "mvn -Pprod-migrate で実行してください）。migrationは1件も実行していません");
        };
    }

    @Bean
    public ProductionSchemaVersionGuard productionSchemaVersionGuard(DataSource dataSource, Environment environment) {
        Binder binder = Binder.get(environment);
        List<String> locations = binder.bind("spring.flyway.locations", Bindable.listOf(String.class))
                .orElse(DEFAULT_LOCATIONS)
                .stream()
                .map(String::trim)
                .filter(location -> !location.isEmpty())
                .toList();
        boolean outOfOrder = binder.bind("spring.flyway.out-of-order", Boolean.class).orElse(false);
        return new ProductionSchemaVersionGuard(dataSource, locations.isEmpty() ? DEFAULT_LOCATIONS : locations,
                outOfOrder);
    }
}
