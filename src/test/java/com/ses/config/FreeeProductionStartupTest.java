package com.ses.config;

import com.ses.SesManagerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * FINAL-FIX: freee連携を有効にした（{@code freee.enabled=true}）prodは、client設定一式（deploy-runbook §1.0 の27〜30）が
 * 無ければ起動しない。無効（prodの既定）なら同じ設定で完全なcontextが起動することは
 * {@code ProductionSecurityConfigurationTest} が確かめる（両者で CI が freee の起動前提を検出する）。
 *
 * <p>起動の失敗は {@code @SpringBootTest} で表せないため、{@code ProductionSecurityConfigurationTest} と同じ設定で
 * アプリ全体を prod profile で起動する。上書きは command line 引数で渡す（{@code application-prod.yml} の既定より優先する）。
 * 共有の H2（testdb）を汚さないよう専用の in-memory DB を使い、ログ設定も書き換えないよう LoggingSystem を止める
 * （fast suite は同じ JVM で順に動く）。</p>
 */
class FreeeProductionStartupTest {

    private static final String LOGGING_SYSTEM_PROPERTY = "org.springframework.boot.logging.LoggingSystem";

    @Test
    void freee連携を有効にしたprodはclient設定一式が無ければ起動しない() {
        List<String> args = productionArgs("jdbc:h2:mem:freee-startup-" + UUID.randomUUID()
                + ";DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
        args.add("--freee.enabled=true");

        Throwable failure = run(args);

        assertThat(failure).as("freee.enabled=true で client 設定が無い prod は起動しない").isNotNull();
        assertThat(causeMessages(failure))
                .as("起動を止めた理由が freee の client 設定であること: %s", failure)
                .anySatisfy(message -> assertThat(message).contains("freee.enabled=true"));
    }

    private static Throwable run(List<String> args) {
        SpringApplication application = new SpringApplicationBuilder(SesManagerApplication.class)
                .profiles("prod")
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .build();
        String previous = System.getProperty(LOGGING_SYSTEM_PROPERTY);
        System.setProperty(LOGGING_SYSTEM_PROPERTY, "none");
        try {
            return catchThrowable(() -> {
                try (ConfigurableApplicationContext started = application.run(args.toArray(String[]::new))) {
                    // 起動してしまった場合は閉じ、呼出し側の断言で失敗させる
                    assertThat(started.isActive()).isTrue();
                }
            });
        } finally {
            if (previous == null) {
                System.clearProperty(LOGGING_SYSTEM_PROPERTY);
            } else {
                System.setProperty(LOGGING_SYSTEM_PROPERTY, previous);
            }
        }
    }

    /** {@code ProductionSecurityConfigurationTest} と同じ prod の試験設定（値は試験専用の固定値）。 */
    private static List<String> productionArgs(String databaseUrl) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.import=optional:classpath:application-test.yml",
                "--spring.datasource.url=" + databaseUrl,
                "--server.port=0",
                "--app.features.nf06.enabled=false",
                "--app.features.nf01-dual-confirmation.enabled=false",
                "--spring.flyway.enabled=false",
                "--spring.flyway.locations=classpath:db/migration,classpath:db/migration-prod",
                "--spring.sql.init.data-locations=classpath:sql/dummy.sql,classpath:sql/production-security-users-h2.sql",
                "--app.security.oidc.break-glass-usernames=admin,breakglass2",
                "--app.security.mfa.encryption-key=test-production-mfa-encryption-key-0001",
                "--app.security.session.hash-key=test-production-session-hash-key-0001",
                "--app.security.trusted-proxies=10.0.0.0/24",
                "--app.security.oidc.issuer-uri=https://idp.invalid/tenant/v2.0",
                "--app.security.oidc.authorization-uri=https://idp.invalid/authorize",
                "--app.security.oidc.token-uri=https://idp.invalid/token",
                "--app.security.oidc.jwk-set-uri=https://idp.invalid/jwks",
                "--app.security.oidc.user-info-uri=https://idp.invalid/userinfo",
                "--app.security.oidc.client-id=test-client",
                "--app.security.oidc.client-secret=test-secret",
                "--app.digital-invoice.provider=none",
                "--app.batch.token-secret=test-batch-token-hmac-secret",
                "--compliance.gate.credential-crypto.current-key-version=v1",
                "--compliance.gate.credential-crypto.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE",
                "--compliance.gate.fingerprint-keys.default.current-key-version=v1",
                "--compliance.gate.fingerprint-keys.default.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE",
                "--certification.number-crypto.current-key-version=v1",
                "--certification.number-crypto.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE",
                "--privacy.retention.enabled=false",
                "--privacy.dsar.enabled=false"));
        return args;
    }

    private static List<String> causeMessages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null && messages.size() < 32; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return messages;
    }
}
