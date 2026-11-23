package com.ses.featureenablement.nf05;

import com.ses.config.integrationhub.IntegrationHubEnablementDecision;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.config.integrationhub.Nf05EnablementFixtures;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * FE-D02（feature-enablement-console design §9.1）: NF05 の起動の前の読取り。JDBC の接続は試験用に H2（MySQL 互換）へ差し替え、V203 の
 * 表と既存の列だけを置いた schema で、状態・署名・総スイッチ・barrier の行・OFF の監査の組合せから property の値を確かめる。
 */
class IntegrationHubEnablementBootResolverTest {

    @TempDir
    Path directory;

    private String database;
    private Log log;
    private IntegrationHubEnablementBootResolver resolver;
    private IntegrationHubExternalApiProperties fixture;
    private byte[] truststore;

    @BeforeEach
    void setUp() throws Exception {
        database = "jdbc:h2:mem:nf05boot_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        log = mock(Log.class);
        resolver = new IntegrationHubEnablementBootResolver(log, (url, user, password) -> DriverManager.getConnection(database));
        Path file = Nf05EnablementFixtures.truststore(directory);
        fixture = Nf05EnablementFixtures.properties(file);
        truststore = IntegrationHubEnablementDecision.readTruststore(file.toString());
        try (Connection connection = DriverManager.getConnection(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t_feature_enablement_state (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100), "
                    + "feature_key VARCHAR(64), state VARCHAR(30), version INT, opened_at TIMESTAMP NULL)");
            statement.execute("CREATE TABLE t_feature_sign_off (id BIGINT PRIMARY KEY, tenant_id VARCHAR(100), kind_code VARCHAR(64), "
                    + "reference VARCHAR(128), signer_role VARCHAR(64), signed_on DATE, expires_on DATE, structured_values TEXT, "
                    + "kind_class VARCHAR(10), status VARCHAR(20), version INT)");
            statement.execute("CREATE TABLE m_system_config (config_key VARCHAR(100) PRIMARY KEY, config_value TEXT)");
            statement.execute("CREATE TABLE t_audit_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, reference_type VARCHAR(50), "
                    + "reference_id BIGINT, application_code VARCHAR(100))");
            for (String table : IntegrationHubEnablementBootResolver.RETENTION_TABLES) {
                statement.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY)");
            }
        }
    }

    @Test
    void 順序はConfigDataの後で対象でない起動は何も書かない() {
        assertThat(resolver.getOrder()).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        resolver.postProcessEnvironment(test, null);
        assertThat(test.getPropertySources().contains(IntegrationHubEnablementBootResolver.PROPERTY_SOURCE)).isFalse();
    }

    @Test
    void MySQL以外のURLと表の欠けはREAD_FAILEDで両方向を閉じpurgeを開く() throws Exception {
        MockEnvironment h2 = environment();
        h2.setProperty("spring.datasource.url", "jdbc:h2:mem:other");
        Map<String, Object> values = resolver.resolve(h2);
        assertReadFailed(values);

        signOffs("OPEN");
        try (Connection connection = DriverManager.getConnection(database); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE t_feature_sign_off");
        }
        assertReadFailed(resolver.resolve(environment()));
    }

    @Test
    void OPENの両方向は指紋だけで開き決定の値と状態と版とpurgeを書く() throws Exception {
        state("NF05_INBOUND", "OPEN", 3);
        state("NF05_OUTBOUND", "OPEN", 5);
        barrier("ON");
        signOffs("OPEN");
        MockEnvironment environment = environment();
        resolver.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().iterator().next().getName())
                .isEqualTo(IntegrationHubEnablementBootResolver.PROPERTY_SOURCE);
        assertThat(environment.getProperty("integration.hub.public-api.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("integration.hub.external-transport.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("integration.hub.retention-purge.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("app.feature-enablement.nf05-boot.inbound.result")).isEqualTo("OPENED");
        assertThat(environment.getProperty("app.feature-enablement.nf05-boot.outbound.state")).isEqualTo("OPEN");
        assertThat(environment.getProperty("app.feature-enablement.nf05-boot.outbound.version")).isEqualTo("5");
        assertThat(environment.getProperty("app.feature-enablement.nf05-boot.inbound.decision-id"))
                .isEqualTo(Nf05EnablementFixtures.INBOUND_DECISION);
        ArgumentCaptor<Object> messages = ArgumentCaptor.forClass(Object.class);
        verify(log, atLeastOnce()).info(messages.capture());
        String digest = environment.getProperty("app.feature-enablement.nf05-boot.inbound.approval-digest");
        assertThat(digest).matches("[0-9a-f]{64}");
        assertThat(messages.getAllValues()).allSatisfy(message -> assertThat(String.valueOf(message))
                .doesNotContain(digest).doesNotContain(Nf05EnablementFixtures.INBOUND_DECISION)
                .doesNotContain(Nf05EnablementFixtures.BOUND_TENANT));
    }

    @Test
    void OPEN_PENDING_RESTARTはNF05の参照のOFFの監査とOFFの行を要する() throws Exception {
        state("NF05_OUTBOUND", "OPEN_PENDING_RESTART", 2);
        barrier("OFF");
        signOffs("OPEN_PENDING_RESTART");
        audit("OTHER", "NF05_OUTBOUND_OFF");
        assertThat(resolver.resolve(environment()).get("app.feature-enablement.nf05-boot.outbound.result"))
                .as("reference_type が NF05 でない監査は数えない").isEqualTo("CLOSED:barrier.offRecorded");
        audit("NF05", "NF05_OUTBOUND_OFF");
        Map<String, Object> values = resolver.resolve(environment());
        assertThat(values.get("app.feature-enablement.nf05-boot.outbound.result")).isEqualTo("OPENED");
        assertThat(values.get("integration.hub.external-transport.enabled")).isEqualTo("true");
        assertThat(values.get("integration.hub.public-api.enabled")).as("閉でも false を書く").isEqualTo("false");
        assertThat(values.get("app.feature-enablement.nf05-boot.inbound.result")).isEqualTo("CLOSED:state.NONE");

        barrier("ON");
        assertThat(resolver.resolve(environment()).get("app.feature-enablement.nf05-boot.outbound.result"))
                .isEqualTo("CLOSED:barrier.offRecorded");
    }

    @Test
    void 総スイッチが閉なら開いた行も閉じ行と開いた記録が無ければpurgeを閉じる() throws Exception {
        state("NF05_INBOUND", "OPEN", 3);
        barrier("ON");
        signOffs("OPEN");
        MockEnvironment closed = environment();
        closed.setProperty("app.feature-enablement.allowed", "false");
        assertThat(resolver.resolve(closed).get("app.feature-enablement.nf05-boot.inbound.result"))
                .isEqualTo("CLOSED:MASTER_SWITCH_CLOSED");

        try (Connection connection = DriverManager.getConnection(database); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE t_feature_enablement_state SET state = 'CLOSED', opened_at = NULL");
        }
        assertThat(resolver.resolve(environment()).get("integration.hub.retention-purge.enabled")).isEqualTo("false");
        try (Connection connection = DriverManager.getConnection(database); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO t_api_nonce_replay (id) VALUES (1)");
        }
        assertThat(resolver.resolve(environment()).get("integration.hub.retention-purge.enabled"))
                .as("保存期間の対象の表に行があれば開く").isEqualTo("true");
    }

    /** review rb-fe-d P3-9・P3-13: 失敗の log は SQLState と vendor の code を出し、URL・account・password・例外の本文を出さない。 */
    @Test
    void 読取りの失敗のlogはSQLStateとvendorのcodeだけでURLとpasswordを出さない() {
        IntegrationHubEnablementBootResolver failing = new IntegrationHubEnablementBootResolver(log, (url, user, password) -> {
            throw new SQLException("Access denied for user 'fixture-user' to jdbc:mysql://secret-host.invalid/ses "
                    + "password=synthetic-secret", "28000", 1045);
        });
        MockEnvironment environment = environment();
        environment.setProperty("spring.datasource.password", "synthetic-secret");
        assertReadFailed(failing.resolve(environment));
        ArgumentCaptor<Object> warned = ArgumentCaptor.forClass(Object.class);
        verify(log).warn(warned.capture());
        assertThat(String.valueOf(warned.getValue())).contains("sqlState=28000").contains("vendorCode=1045")
                .doesNotContain("secret-host").doesNotContain("synthetic-secret").doesNotContain("fixture-user")
                .doesNotContain("jdbc:mysql");
    }

    private void assertReadFailed(Map<String, Object> values) {
        assertThat(values.get("integration.hub.public-api.enabled")).isEqualTo("false");
        assertThat(values.get("integration.hub.external-transport.enabled")).isEqualTo("false");
        assertThat(values.get("integration.hub.retention-purge.enabled")).isEqualTo("true");
        assertThat(values.get("app.feature-enablement.nf05-boot.inbound.result")).isEqualTo("READ_FAILED");
        assertThat(values.get("app.feature-enablement.nf05-boot.outbound.result")).isEqualTo("READ_FAILED");
    }

    /** 合成の配備の設定（fixture と同じ値を property にする）と DB の源の prod。 */
    private MockEnvironment environment() {
        MockEnvironment environment = Nf05EnablementFixtures.environment(Path.of(fixture.getExternalTransport()
                .getTruststorePath()));
        environment.setProperty("app.feature-enablement.source", "database");
        environment.setProperty("spring.datasource.url", "jdbc:mysql://fixture.invalid/ses");
        environment.setProperty("spring.datasource.username", "fixture");
        environment.setProperty("integration.hub.provider.mode", "HTTPS");
        environment.setProperty("integration.hub.topology.bound-tenant-id", Nf05EnablementFixtures.BOUND_TENANT);
        environment.setProperty("integration.hub.public-api.approval.allowed-client-cidrs[0]", "203.0.113.0/24");
        environment.setProperty("integration.hub.security.trusted-proxies[0]", "10.0.0.0/24");
        environment.setProperty("integration.hub.external-transport.approval.allowed-destinations[0]",
                "hooks.partner-a.example:443");
        environment.setProperty("integration.hub.external-transport.truststore-type", "PKCS12");
        return environment;
    }

    private void state(String feature, String state, int version) throws SQLException {
        try (Connection connection = DriverManager.getConnection(database); PreparedStatement statement =
                connection.prepareStatement("INSERT INTO t_feature_enablement_state (tenant_id, feature_key, state, version, "
                        + "opened_at) VALUES ('default', ?, ?, ?, CURRENT_TIMESTAMP)")) {
            statement.setString(1, feature);
            statement.setString(2, state);
            statement.setInt(3, version);
            statement.executeUpdate();
        }
    }

    private void barrier(String phase) throws SQLException {
        try (Connection connection = DriverManager.getConnection(database); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM m_system_config");
            statement.execute("INSERT INTO m_system_config (config_key, config_value) VALUES ('"
                    + IntegrationHubEnablementDecision.BARRIER_CONFIG_KEY + "', '"
                    + Nf05EnablementFixtures.barrier(phase, Nf05EnablementFixtures.BOUND_TENANT) + "')");
        }
    }

    private void audit(String referenceType, String code) throws SQLException {
        try (Connection connection = DriverManager.getConnection(database); PreparedStatement statement =
                connection.prepareStatement("INSERT INTO t_audit_log (reference_type, reference_id, application_code) "
                        + "VALUES (?, 0, ?)")) {
            statement.setString(1, referenceType);
            statement.setString(2, code);
            statement.executeUpdate();
        }
    }

    /** 全ての種別の確認済みの署名（日付は今日（JST）の前後。決定の値は fixture の設定の digest）。 */
    private void signOffs(String ignoredState) throws SQLException {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        try (Connection connection = DriverManager.getConnection(database); PreparedStatement statement =
                connection.prepareStatement("INSERT INTO t_feature_sign_off (id, tenant_id, kind_code, reference, signer_role, "
                        + "signed_on, expires_on, structured_values, kind_class, status, version) "
                        + "VALUES (?, 'default', ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', 0)")) {
            for (FeatureEnablementSnapshot.ConfirmedSignOff signOff
                    : Nf05EnablementFixtures.signOffs(fixture, truststore).values()) {
                statement.setLong(1, signOff.id());
                statement.setString(2, signOff.kindCode());
                statement.setString(3, signOff.reference());
                statement.setString(4, signOff.signerRole());
                statement.setObject(5, today.minusDays(10));
                statement.setObject(6, today.plusDays(100));
                statement.setString(7, signOff.structuredValues());
                statement.setString(8, signOff.kindClass());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
