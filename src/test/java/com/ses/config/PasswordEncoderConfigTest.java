package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B2-6 / REV-B2.1-P1-002: PasswordEncoder と prod Flyway locations の fail-closed。
 */
@SuppressWarnings("deprecation")
class PasswordEncoderConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PasswordEncoderConfig.class);

    @Test
    void prodはBCryptで起動できる() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("spring.flyway.locations=classpath:db/migration,classpath:db/migration-prod")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PasswordEncoder.class))
                            .isInstanceOf(BCryptPasswordEncoder.class);
                });
    }

    @Test
    void prodはapplication_prod_ymlのFlywaylocationsで起動できる() {
        // locations をテスト側で上書きせず、本物の application-prod.yml を ConfigData で読む
        new ApplicationContextRunner()
                .withUserConfiguration(PasswordEncoderConfig.class)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    String locations = context.getEnvironment().getProperty("spring.flyway.locations");
                    Set<String> parsed = PasswordEncoderConfig.parseFlywayLocations(locations);
                    assertThat(parsed).contains(PasswordEncoderConfig.FLYWAY_LOCATION_PROD);
                    assertThat(parsed).doesNotContain(PasswordEncoderConfig.FLYWAY_LOCATION_DEV);
                    assertThat(context.getBean(PasswordEncoder.class))
                            .isInstanceOf(BCryptPasswordEncoder.class);
                });
    }

    @Test
    void prodでFlywaylocations欠損は起動失敗() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void prodでFlywaylocationsが空文字なら起動失敗() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("spring.flyway.locations=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void prodでFlywaylocationsが空白のみなら起動失敗() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("spring.flyway.locations=   ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void substringのmigration_prod偽陽性を許さない() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("spring.flyway.locations=classpath:db/migration,classpath:db/migration-prod-extra")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void parseは正確な集合を返す() {
        Set<String> parsed = PasswordEncoderConfig.parseFlywayLocations(
                "classpath:db/migration, classpath:db/migration-prod");
        assertThat(parsed).containsExactlyInAnyOrder(
                "classpath:db/migration", "classpath:db/migration-prod");
        assertThat(parsed.contains("classpath:db/migration-prod")).isTrue();
        assertThat(parsed.contains("classpath:db/migration-dev")).isFalse();
        // substring では含まれるが正確一致ではない
        assertThat(parsed.contains("classpath:db/migration-prod-extra")).isFalse();
    }

    @Test
    void 正確なmigration_devは拒否される() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("spring.flyway.locations=classpath:db/migration,classpath:db/migration-dev,classpath:db/migration-prod")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void migration_devishは正確一致ではないがmigration_prodが無ければ失敗() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("spring.flyway.locations=classpath:db/migration,classpath:db/migration-devish")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void migration_devishでもmigration_prodがあれば起動できる() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("spring.flyway.locations=classpath:db/migration-devish,classpath:db/migration-prod")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void dev明示はNoOpで起動できる() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PasswordEncoder.class))
                            .isInstanceOf(NoOpPasswordEncoder.class);
                });
    }

    @Test
    void test明示はNoOpで起動できる() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PasswordEncoder.class))
                            .isInstanceOf(NoOpPasswordEncoder.class);
                });
    }

    @Test
    void プロファイル未指定は起動失敗する() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void prodもどきのproductionは起動失敗する() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("production"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 未知プロファイルstagingは起動失敗する() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("staging"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void prodとdev併用は起動失敗する() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod", "dev"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void prodとtest併用は起動失敗する() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test", "prod"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void resolveはprod混在を拒否する() {
        assertThatThrownBy(() -> PasswordEncoderConfig.resolve(new String[]{"prod", "dev"}))
                .isInstanceOf(IllegalStateException.class);
    }
}
