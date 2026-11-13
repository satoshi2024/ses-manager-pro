package com.ses.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本番の起動時schema guard（{@link ProductionSchemaVersionGuard}）と、prodでの起動時migrate拒否
 * （{@link ProductionSchemaGuardConfiguration}）をDocker・MySQL無しで検証する。
 *
 * <p>判定規則は合成した状態で、Flywayの状態解決はH2上の最小migration（{@code schema-guard-test/}）で確認する。
 * 実MySQLへのfresh install後にguardが通ることは {@code FlywayProdFreshInstallMySqlTest}、
 * 期待versionが {@code LatestMigrationVersion.LATEST} と一致することは {@code MigrationScriptIntegrityTest} が見る。
 */
class ProductionSchemaVersionGuardTest {

    private static final String BASE = "classpath:schema-guard-test/base";
    private static final String NEXT = "classpath:schema-guard-test/next";

    // ---------------------------------------------------------------- 判定規則（合成状態）

    @Test
    void 全versionedが成功しartifactの最新と一致すれば起動してよい() {
        ProductionSchemaVersionGuard.Result result = ProductionSchemaVersionGuard.evaluate(List.of(
                entry("1", MigrationState.SUCCESS),
                entry("74.3", MigrationState.SUCCESS),
                entry("186", MigrationState.SUCCESS),
                // placeholderを置換しないためchecksumが合わないRepeatableは照合しない
                entry(null, MigrationState.OUTDATED),
                entry(null, MigrationState.PENDING),
                entry(null, MigrationState.SUCCESS)));

        assertThat(result.ok()).as(result.problems().toString()).isTrue();
        assertThat(result.expectedVersion()).isEqualTo("186");
        assertThat(result.appliedVersion()).isEqualTo("186");
    }

    @Test
    void 未適用のversionedがあれば起動しない() {
        ProductionSchemaVersionGuard.Result result = ProductionSchemaVersionGuard.evaluate(List.of(
                entry("185", MigrationState.SUCCESS),
                entry("186", MigrationState.PENDING)));

        assertThat(result.ok()).isFalse();
        assertThat(result.problems()).anyMatch(problem -> problem.contains("未適用") && problem.contains("V186"));
        assertThat(result.message()).contains("V186").contains("V185").contains("deploy-runbook.md");
    }

    @Test
    void 失敗したmigrationが残っていれば起動しない() {
        assertThat(ProductionSchemaVersionGuard.evaluate(List.of(
                entry("185", MigrationState.SUCCESS),
                entry("186", MigrationState.FAILED))).problems())
                .anyMatch(problem -> problem.contains("失敗") && problem.contains("V186"));
        // Repeatableの失敗（例: 初期管理者hashの検査）も止める
        assertThat(ProductionSchemaVersionGuard.evaluate(List.of(
                entry("186", MigrationState.SUCCESS),
                entry(null, MigrationState.FAILED))).ok()).isFalse();
    }

    @Test
    void artifactに無いmigrationが適用済みなら起動しない() {
        ProductionSchemaVersionGuard.Result future = ProductionSchemaVersionGuard.evaluate(List.of(
                entry("186", MigrationState.SUCCESS),
                entry("187", MigrationState.FUTURE_SUCCESS)));
        assertThat(future.ok()).isFalse();
        assertThat(future.problems()).anyMatch(problem -> problem.contains("artifactに無い"));
        assertThat(future.appliedVersion()).isEqualTo("187");

        assertThat(ProductionSchemaVersionGuard.evaluate(List.of(
                entry("185", MigrationState.MISSING_SUCCESS),
                entry("186", MigrationState.SUCCESS))).ok()).isFalse();
    }

    @Test
    void 適用履歴が無ければjob未実行として起動しない() {
        ProductionSchemaVersionGuard.Result result = ProductionSchemaVersionGuard.evaluate(List.of(
                entry("1", MigrationState.PENDING),
                entry("186", MigrationState.PENDING)));

        assertThat(result.ok()).isFalse();
        assertThat(result.problems().get(0)).contains("適用履歴がありません");
        assertThat(result.appliedVersion()).isNull();
    }

    @Test
    void 起動失敗messageは問題の列挙を上限で打ち切る() {
        List<ProductionSchemaVersionGuard.Entry> entries = new java.util.ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            entries.add(entry(String.valueOf(i), MigrationState.PENDING));
        }
        String message = ProductionSchemaVersionGuard.evaluate(entries).message();

        assertThat(message).contains("ほか");
        assertThat(message).doesNotContain("V30（");
    }

    // ---------------------------------------------------------------- Flywayの状態解決（H2）

    @Test
    void H2上で適用済みと一致すれば最新versionを返しplaceholder付きRepeatableは照合しない() {
        DataSource dataSource = h2();
        migrate(dataSource, BASE);

        String latest = new ProductionSchemaVersionGuard(dataSource, List.of(BASE), false).verify();

        assertThat(latest).isEqualTo("1");
    }

    @Test
    void H2上でartifactの新しいmigrationが未適用なら起動を止める() {
        DataSource dataSource = h2();
        migrate(dataSource, BASE);

        assertThatThrownBy(() -> new ProductionSchemaVersionGuard(dataSource, List.of(BASE, NEXT), false).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未適用")
                .hasMessageContaining("V2");
    }

    @Test
    void H2上でDBがartifactより新しければ起動を止める() {
        DataSource dataSource = h2();
        migrate(dataSource, BASE, NEXT);

        assertThatThrownBy(() -> new ProductionSchemaVersionGuard(dataSource, List.of(BASE), false).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artifactに無い");
    }

    @Test
    void H2上で空DBならjob未実行として起動を止める() {
        assertThatThrownBy(() -> new ProductionSchemaVersionGuard(h2(), List.of(BASE), false).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("適用履歴がありません");
    }

    // ---------------------------------------------------------------- prod profileでの登録

    @Test
    void prod_profileでは未migrateのDBに対して起動を止める() {
        DataSource dataSource = h2();
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues("spring.flyway.locations=" + BASE)
                .withUserConfiguration(ProductionSchemaGuardConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    // SmartInitializingSingletonの例外は包まれずに届くが、包まれても判定できるようstack traceで見る
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("[schema guard]")
                            .hasStackTraceContaining("適用履歴がありません");
                });
    }

    @Test
    void prod_profileでは適用済みDBなら起動しmigrate戦略は常に拒否する() {
        DataSource dataSource = h2();
        migrate(dataSource, BASE);
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues("spring.flyway.locations=" + BASE)
                .withUserConfiguration(ProductionSchemaGuardConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProductionSchemaVersionGuard.class);
                    FlywayMigrationStrategy strategy = context.getBean(FlywayMigrationStrategy.class);
                    // 誤って spring.flyway.enabled=true で起動しても、Spring BootはこのstrategyへFlywayを渡す。
                    assertThatThrownBy(() -> strategy.migrate(null))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("起動時のFlyway migrateを禁止");
                });
    }

    @Test
    void prod以外のprofileではguardもmigrate拒否も登録しない() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withBean(DataSource.class, ProductionSchemaVersionGuardTest::h2)
                .withUserConfiguration(ProductionSchemaGuardConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ProductionSchemaVersionGuard.class);
                    assertThat(context).doesNotHaveBean(FlywayMigrationStrategy.class);
                });
    }

    // ---------------------------------------------------------------- helper

    private static ProductionSchemaVersionGuard.Entry entry(String version, MigrationState state) {
        return new ProductionSchemaVersionGuard.Entry(version, state,
                version == null ? "R__example.sql" : "V" + version.replace('.', '_') + "__example.sql");
    }

    private static DataSource h2() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:schema_guard_" + UUID.randomUUID().toString().replace("-", "")
                        + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
    }

    private static void migrate(DataSource dataSource, String... locations) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .placeholders(Map.of("guard_secret", "guard-placeholder-value"))
                .load()
                .migrate();
    }
}
