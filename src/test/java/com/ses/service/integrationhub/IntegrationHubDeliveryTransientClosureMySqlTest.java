package com.ses.service.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-D03（feature-enablement-console design §9.3、requirements FE-AC-008-07、micro review P3-M2）: 実 MySQL 8.4 の delivery 台帳で、
 * outbound の一時の閉鎖の間に積まれた配信が回復の後に送られ、閉じた時に処理中だった配信が DLQ にならず PENDING へ戻って送られることを
 * 確かめる。PENDING への戻しは lease の token の CAS で、claim で足した {@code attempt_count} を戻す（一時の閉鎖を何度繰り返しても
 * {@code max_attempts} を使い切らない）。transport・署名の鍵・購読は試験の部品に替え、claim・戻し・成功の CAS は実物の mapper と
 * service を通す。commit した行は class 専用の container DB に残る（client ID で区別する）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class IntegrationHubDeliveryTransientClosureMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_nf05_transient_closure")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private ApiDeliveryMapper deliveryMapper;
    @Autowired
    private ApiDeliveryService deliveryService;
    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicReference<IntegrationHubRuntimeSwitch.Closure> closure =
            new AtomicReference<>(IntegrationHubRuntimeSwitch.Closure.NONE);
    private String client;
    private long subscription;
    private IntegrationHubWebhookTransport transport;
    private IntegrationHubWebhookDeliveryWorker worker;

    @BeforeEach
    void setUp() {
        client = "nf05-transient-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO m_api_client(client_id,owner_ref,tenant_id,legal_entity_id,data_scope_json,allowed_cidrs) "
                + "VALUES(?,'PROJECT_OWNER','default',9,'{}','')", client);
        jdbc.update("INSERT INTO m_webhook_subscription(client_id,direction,event_type,endpoint_url,key_id,"
                + "encrypted_signing_secret,crypto_key_version,data_scope_json) VALUES(?,'OUTBOUND','resource.changed',"
                + "'https://web.example.com:443/hook','key-1','synthetic','v1','{\"tenantIds\":[\"default\"]}')", client);
        subscription = jdbc.queryForObject("SELECT id FROM m_webhook_subscription WHERE client_id=?", Long.class, client);

        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getExternalTransport().setEnabled(true);
        properties.getTopology().setBoundTenantId("default");
        WebhookSubscriptionService subscriptions = mock(WebhookSubscriptionService.class);
        when(subscriptions.getActive(subscription)).thenReturn(WebhookSubscription.builder().id(subscription)
                .clientId(client).direction("OUTBOUND").eventType("resource.changed")
                .endpointUrl("https://web.example.com:443/hook").keyId("key-1").signingCredentialVersion(1)
                .encryptedSigningSecret("synthetic").cryptoKeyVersion("v1")
                .dataScopeJson("{\"tenantIds\":[\"default\"]}").status("ACTIVE").build());
        IntegrationHubSecretCryptoService crypto = mock(IntegrationHubSecretCryptoService.class);
        when(crypto.decrypt(any(), anyInt(), any(), any())).thenReturn("synthetic-webhook-secret");
        transport = mock(IntegrationHubWebhookTransport.class);
        when(transport.send(any())).thenReturn(IntegrationHubWebhookTransportResult.success(202, "provider-1"));
        worker = new IntegrationHubWebhookDeliveryWorker(deliveryMapper, deliveryService, subscriptions, crypto, transport,
                new IntegrationHubWebhookSigner(), new IntegrationHubWebhookBackoffPolicy(() -> 0),
                mock(IntegrationHubWebhookDeliveryBindingValidator.class), properties, Clock.systemUTC(), new ObjectMapper());
        IntegrationHubOutboundStartupBarrier barrier = mock(IntegrationHubOutboundStartupBarrier.class);
        when(barrier.isReady()).thenAnswer(invocation -> closure.get() == IntegrationHubRuntimeSwitch.Closure.NONE);
        ReflectionTestUtils.setField(worker, "startupBarrier", barrier);
        IntegrationHubRuntimeSwitch runtime = mock(IntegrationHubRuntimeSwitch.class);
        when(runtime.outboundClosure()).thenAnswer(invocation -> closure.get());
        ReflectionTestUtils.setField(worker, "runtimeSwitch", runtime);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM t_api_delivery WHERE client_id=?", client);
        jdbc.update("DELETE FROM m_webhook_subscription WHERE client_id=?", client);
        jdbc.update("DELETE FROM m_api_client WHERE client_id=?", client);
    }

    @Test
    void 一時の閉鎖の間の配信は回復の後に送られ処理中だった配信もDLQにならず送られる() {
        long inFlight = insertDelivery();
        // 送信の直前に一時の閉鎖になる（claim の時は開いていた）
        when(transport.send(any())).thenAnswer(invocation -> {
            throw new AssertionError("一時の閉鎖の間は送らない");
        });
        IntegrationHubRuntimeSwitch runtime = (IntegrationHubRuntimeSwitch) ReflectionTestUtils.getField(worker, "runtimeSwitch");
        when(runtime.outboundClosure()).thenReturn(IntegrationHubRuntimeSwitch.Closure.NONE,
                IntegrationHubRuntimeSwitch.Closure.TRANSIENT);
        assertThat(worker.dispatchOne(inFlight, utcNow())).isTrue();
        assertThat(status(inFlight)).isEqualTo("PENDING");
        assertThat(attempts(inFlight)).as("claim で足した試行の回数を戻す").isZero();
        assertThat(jdbc.queryForObject("SELECT lease_token FROM t_api_delivery WHERE id=?", String.class, inFlight)).isNull();

        // 閉じている間の業務の変更（producer は積み続ける）と、worker は claim しない
        when(runtime.outboundClosure()).thenAnswer(invocation -> closure.get());
        closure.set(IntegrationHubRuntimeSwitch.Closure.TRANSIENT);
        long duringClosure = insertDelivery();
        assertThat(worker.dispatchDue()).isZero();
        assertThat(status(duringClosure)).isEqualTo("PENDING");

        // 回復の後に両方が送られる（at-least-once）
        when(transport.send(any())).thenReturn(IntegrationHubWebhookTransportResult.success(202, "provider-1"));
        closure.set(IntegrationHubRuntimeSwitch.Closure.NONE);
        worker.dispatchDue();
        assertThat(status(inFlight)).isEqualTo("SUCCEEDED");
        assertThat(status(duringClosure)).isEqualTo("SUCCEEDED");
        assertThat(attempts(inFlight)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_api_delivery WHERE client_id=? AND status='DLQ'",
                Integer.class, client)).isZero();
    }

    @Test
    void 一時の閉鎖を繰り返してもmax_attemptsを使い切らずclaimできるまま() {
        long delivery = insertDelivery();
        IntegrationHubRuntimeSwitch runtime = (IntegrationHubRuntimeSwitch) ReflectionTestUtils.getField(worker, "runtimeSwitch");
        for (int i = 0; i < 12; i++) {
            when(runtime.outboundClosure()).thenReturn(IntegrationHubRuntimeSwitch.Closure.NONE,
                    IntegrationHubRuntimeSwitch.Closure.TRANSIENT);
            assertThat(worker.dispatchOne(delivery, utcNow())).as("戻し " + i).isTrue();
        }
        assertThat(attempts(delivery)).isZero();
        assertThat(status(delivery)).isEqualTo("PENDING");
        // lease の持ち主でなければ戻さない（CAS）
        int version = jdbc.queryForObject("SELECT version FROM t_api_delivery WHERE id=?", Integer.class, delivery);
        assertThat(deliveryMapper.releaseClaim(delivery, version, 1, "not-the-owner", utcNow())).isZero();
    }

    private long insertDelivery() {
        String event = UUID.randomUUID().toString();
        LocalDateTime created = utcNow().truncatedTo(ChronoUnit.SECONDS).minusMinutes(1);
        String correlation = "correlation-" + event.substring(0, 8);
        String body = "{\"eventId\":\"" + event + "\",\"eventType\":\"resource.changed\",\"schemaVersion\":\"v1\","
                + "\"createdAt\":\"" + DateTimeFormatter.ISO_INSTANT.format(created.toInstant(ZoneOffset.UTC)) + "\","
                + "\"publicResourceId\":\"resource-1\",\"correlationId\":\"" + correlation + "\","
                + "\"payload\":{\"status\":\"ACTIVE\"}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO t_api_delivery(event_id,subscription_id,client_id,tenant_id,scope_code,event_type,"
                        + "schema_version,correlation_id,provider_idempotency_key,external_dto_snapshot,payload_hash,"
                        + "primary_resource_type,primary_resource_id,scope_digest,status,attempt_count,max_attempts,"
                        + "delivery_generation,next_attempt_at,created_at,updated_at) "
                        + "VALUES(?,?,?,'default','integration.project.read','resource.changed','v1',?,?,?,?,'project',1,?,"
                        + "'PENDING',0,8,1,?,?,?)", event, subscription, client, correlation,
                IntegrationHubDigest.sha256Hex(event + "|" + subscription + "|1"), body, IntegrationHubDigest.sha256Hex(bytes),
                IntegrationHubWebhookScopeDigest.of(client, "integration.project.read", "default"), created, created, created);
        return jdbc.queryForObject("SELECT id FROM t_api_delivery WHERE event_id=?", Long.class, event);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM t_api_delivery WHERE id=?", String.class, id);
    }

    private int attempts(long id) {
        return jdbc.queryForObject("SELECT attempt_count FROM t_api_delivery WHERE id=?", Integer.class, id);
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
