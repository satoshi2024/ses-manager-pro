package com.ses.backup.quiesce;

import com.ses.config.ProductionSchemaVersionGuard;
import com.ses.config.ProductionSecurityConfigurationValidator;
import com.ses.test.ReadOnlyEnforcingH2Driver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-015（BWQ-AC-013-03・design.md §6.12・L-13）: prod の profile で backup の書込み静止を有効にした（ARMED）アプリ全体の
 * context が起動すること。prod の登録の前（UNARMED）は permit の無い業務の書込みと uploads の file の変更が拒否されるため、bean の
 * 初期化の間に書く bean があれば起動が止まる。{@code LocalDocumentStorage} の constructor の directory の作成は file の変更として
 * 扱わない（assert に当たらない）。起動時の処理（{@code ApplicationRunner}）は startup permit の下で動く。
 *
 * <p>設定は {@code ProductionSecurityConfigurationTest} と同じ prod の試験設定（H2・外部依存は mock）に
 * {@code app.backup-quiesce.enabled=true} を足したもの。静止応答が信号表へ書き thread を動かすため、class 専用の H2 と
 * {@code @DirtiesContext} にする（{@code BackupQuiesceTestContextInventoryTest}）。業務の DB の読取り専用は、試験の driver
 * （{@link ReadOnlyEnforcingH2Driver}）が読取り専用の接続の DML を拒否して確かめる（既定は Connector/J の client 側の拒否の形、
 * server の 1792 にも切り替えられる。DDL と locking read は含まない。L-13）。bean の生成の間の書込みは
 * {@code InitializationWriteGuardTest} も静的に拒否する（2c review P3-5・fix E）。
 */
@SpringBootTest(properties = {
        "spring.config.import=optional:classpath:application-test.yml",
        "app.features.nf06.enabled=false",
        "app.features.nf01-dual-confirmation.enabled=false",
        "spring.flyway.enabled=false",
        "spring.flyway.locations=classpath:db/migration,classpath:db/migration-prod",
        "spring.sql.init.data-locations=classpath:sql/dummy.sql,classpath:sql/production-security-users-h2.sql",
        "app.security.oidc.break-glass-usernames=admin,breakglass2",
        "app.security.mfa.encryption-key=test-production-mfa-encryption-key-0001",
        "app.security.session.hash-key=test-production-session-hash-key-0001",
        "app.security.trusted-proxies=10.0.0.0/24",
        "app.security.oidc.issuer-uri=https://idp.invalid/tenant/v2.0",
        "app.security.oidc.authorization-uri=https://idp.invalid/authorize",
        "app.security.oidc.token-uri=https://idp.invalid/token",
        "app.security.oidc.jwk-set-uri=https://idp.invalid/jwks",
        "app.security.oidc.user-info-uri=https://idp.invalid/userinfo",
        "app.security.oidc.client-id=test-client",
        "app.security.oidc.client-secret=test-secret",
        "app.digital-invoice.provider=none",
        "app.batch.token-secret=test-batch-token-hmac-secret",
        "compliance.gate.credential-crypto.current-key-version=v1",
        "compliance.gate.credential-crypto.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE",
        "compliance.gate.fingerprint-keys.default.current-key-version=v1",
        "compliance.gate.fingerprint-keys.default.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE",
        "certification.number-crypto.current-key-version=v1",
        "certification.number-crypto.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE",
        "privacy.retention.enabled=false",
        "privacy.dsar.enabled=false",
        "app.backup-quiesce.enabled=true",
        "app.upload.base-path=./target/bq-prod-context-uploads",
        "spring.datasource.url=jdbc:h2:mem:bq_prod_context_start;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        // 2b review P3-3・fix E: 読取り専用の接続の DML を拒否する試験の driver（H2 は setReadOnly を何もしない。既定は
        // Connector/J の client 側の拒否の形）
        "spring.datasource.driver-class-name=com.ses.test.ReadOnlyEnforcingH2Driver"})
@org.springframework.context.annotation.Import(BackupQuiesceProductionContextStartTest.SqlInitExemption.class)
@ActiveProfiles("prod")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackupQuiesceProductionContextStartTest {

    /**
     * 試験の準備の SQL（{@code spring.sql.init}）だけを読取り専用の検査から外す。本番は Flyway の job で schema を作り、起動では
     * 流さない。初期化の間の他の書込み（bean の生成・{@code @Bean}・SmartInitializingSingleton・interface・lambda の経由を含む）は
     * 登録の前（UNARMED）の読取り専用の接続で拒否され、起動が止まるか {@link ReadOnlyEnforcingH2Driver#rejectedStatements()}
     * に残る。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class SqlInitExemption {
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanPostProcessor sqlInitReadOnlyExemption() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof org.springframework.boot.sql.init.AbstractScriptDatabaseInitializer) {
                        ReadOnlyEnforcingH2Driver.exemptCurrentThread(true);
                    }
                    return bean;
                }

                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof org.springframework.boot.sql.init.AbstractScriptDatabaseInitializer) {
                        ReadOnlyEnforcingH2Driver.exemptCurrentThread(false);
                    }
                    return bean;
                }
            };
        }
    }

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    // DB の fixture に依る起動時検査は単体試験へ分ける（ProductionSecurityConfigurationTest と同じ）
    @MockBean
    private ProductionSecurityConfigurationValidator productionSecurityConfigurationValidator;

    @MockBean
    private ProductionSchemaVersionGuard productionSchemaVersionGuard;

    @Autowired
    private BackupQuiesceLifecycleAnchor anchor;

    @Autowired
    private DataSource dataSource;

    // BWQ-007: enabled=true の prod の context は起動時検査を置き換えずに通す（H2 の URL は Connector/J の検査の対象外）
    @Autowired
    private BackupQuiesceProductionValidator productionValidator;

    @Test
    void prodのprofileで静止を有効にしたcontextが起動しbean初期化のdirectoryの作成は拒否されない() {
        assertThat(productionValidator.isValidated()).as("実の起動時検査が登録の前に通った").isTrue();
        BackupWriteGate gate = anchor.gate();
        assertThat(gate.mode()).isEqualTo(BackupWriteGate.Mode.ARMED);
        assertThat(gate.isProdProfile()).isTrue();
        assertThat(gate.state()).as("登録の後、有効な要求が無ければ開く").isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(gate.oldestPermitAgeNanos(BackupWritePermit.Kind.STARTUP))
                .as("起動の完了（ApplicationReadyEvent）で startup permit を返した").isEmpty();

        Path documents = Path.of("./target/bq-prod-context-uploads", "documents");
        assertThat(Files.isDirectory(documents.resolve("quarantine"))).isTrue();
        assertThat(Files.isDirectory(documents.resolve("published"))).isTrue();

        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE instance_id = ?", Integer.class,
                anchor.instanceId())).as("静止応答が instance を登録した").isEqualTo(1);
    }

    /**
     * 2b review P3-3: 登録の前（UNARMED）の初期化の間に、業務の接続で DML を実行した bean が無い（握りつぶした書込みも含む）。
     * 検査の driver が効いていること（読取り専用の接続の UPDATE が拒否され、静止の拒否と判定されること）も確かめ、試験が
     * 空振りしないようにする。fix E: 既定の形は Connector/J の client 側の拒否（S1009。Statement と PreparedStatement で文言が
     * 違う）で、server の 1792 の形にも切り替えて確かめる。
     */
    @Test
    void 初期化の間に読取り専用の業務の接続へ書いたbeanが無く試験のdriverはclient側とserver側の拒否を返す() throws Exception {
        assertThat(ReadOnlyEnforcingH2Driver.rejectedStatements())
                .as("prod の起動（UNARMED の間）に読取り専用の接続で実行した DML").isEmpty();

        String update = "UPDATE sys_backup_quiesce_instance SET app_commit = app_commit WHERE instance_id = 'none'";
        try (java.sql.Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (java.sql.Statement statement = connection.createStatement();
                 java.sql.PreparedStatement prepared = connection.prepareStatement(update)) {
                java.sql.SQLException client = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                        () -> statement.executeUpdate(update));
                assertThat(client.getSQLState()).isEqualTo("S1009");
                assertThat(client.getErrorCode()).isZero();
                assertThat(BackupQuiesceReadOnlyViolation.matches(client)).as(client.getMessage()).isTrue();
                java.sql.SQLException clientPrepared = org.junit.jupiter.api.Assertions.assertThrows(
                        java.sql.SQLException.class, prepared::execute);
                assertThat(clientPrepared.getSQLState()).isEqualTo("S1009");
                assertThat(BackupQuiesceReadOnlyViolation.matches(clientPrepared)).as(clientPrepared.getMessage()).isTrue();

                ReadOnlyEnforcingH2Driver.useForm(com.ses.test.MySqlReadOnlyRejections.Form.MYSQL_SERVER);
                java.sql.SQLException server = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                        () -> statement.executeUpdate(update));
                assertThat(server.getErrorCode()).isEqualTo(1792);
                assertThat(BackupQuiesceReadOnlyViolation.matches(server)).isTrue();
            } finally {
                ReadOnlyEnforcingH2Driver.useForm(null);
                connection.setReadOnly(false);
            }
        } finally {
            ReadOnlyEnforcingH2Driver.clearRejectedStatements();
        }
    }
}
