package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B2-6 / REV-B2-P1-003: PasswordEncoder と prod Flyway locations の fail-closed マトリクス。
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
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void 未知プロファイルstagingは起動失敗する() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("staging"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void prodとdev併用は起動失敗する() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod", "dev"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void prodとtest併用は起動失敗する() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test", "prod"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void prodのFlywayにmigration_devが含まれると起動失敗する() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("spring.flyway.locations=classpath:db/migration,classpath:db/migration-dev")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void prodのFlywayにmigration_prodが無いと起動失敗する() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("spring.flyway.locations=classpath:db/migration")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void resolveはprod混在を拒否する() {
        assertThatThrownBy(() -> PasswordEncoderConfig.resolve(new String[]{"prod", "dev"}))
                .isInstanceOf(IllegalStateException.class);
    }
}
