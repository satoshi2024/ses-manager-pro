package com.ses.backup.quiesce;

import com.ses.config.ProductionSchemaVersionGuard;
import com.ses.config.ProductionSecurityConfigurationValidator;
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
 * {@code @DirtiesContext} にする（{@code BackupQuiesceTestContextInventoryTest}）。H2 の {@code setReadOnly} は何もしないため、
 * 業務の DB の読取り専用は確かめない（{@code InitializationWriteGuardTest} が bean の生成の間の書込みを静的に拒否する）。
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
        "spring.datasource.url=jdbc:h2:mem:bq_prod_context_start;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"})
@ActiveProfiles("prod")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackupQuiesceProductionContextStartTest {

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

    @Test
    void prodのprofileで静止を有効にしたcontextが起動しbean初期化のdirectoryの作成は拒否されない() {
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
}
