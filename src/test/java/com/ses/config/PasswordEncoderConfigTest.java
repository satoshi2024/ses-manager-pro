package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2-6 (ACC-SEC-P1-007 / REV-P1-006): PasswordEncoder の fail-closed プロファイル解決マトリクス。
 *
 * <ul>
 *   <li>prod → BCrypt で起動できる。</li>
 *   <li>明示 dev / test → NoOp（平文）で起動できる。</li>
 *   <li>プロファイル未指定 → 起動失敗（平文へサイレントフォールバックしない）。</li>
 *   <li>prod/dev/test 以外の不安全な値（"production" もどき・"staging" 等）→ 起動失敗。</li>
 *   <li>prod と dev/test の併用 → prod を優先し BCrypt（平文へ落ちない）。</li>
 * </ul>
 */
@SuppressWarnings("deprecation") // NoOpPasswordEncoder はテストでの型判定に用いる。
class PasswordEncoderConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PasswordEncoderConfig.class);

    @Test
    void prodはBCryptで起動できる() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
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
    void devとprod併用はprod優先でBCrypt() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod", "dev"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PasswordEncoder.class))
                            .isInstanceOf(BCryptPasswordEncoder.class);
                });
    }

    @Test
    void testとprod併用もprod優先でBCrypt() {
        // ProductionSecurityConfigurationTest が @ActiveProfiles({"test","prod"}) を使うため、
        // その組み合わせで平文へ落ちない（BCryptになる）ことを固定する。
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test", "prod"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PasswordEncoder.class))
                            .isInstanceOf(BCryptPasswordEncoder.class);
                });
    }
}
