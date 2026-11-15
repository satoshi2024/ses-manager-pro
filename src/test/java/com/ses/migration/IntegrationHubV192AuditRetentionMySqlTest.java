package com.ses.migration;

import com.ses.mapper.ApiDeliveryMapper;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.MySQLContainer;
import org.apache.ibatis.annotations.Update;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** V192の実MySQL変換と、production mapperのclaim SQLを二接続で検証する。 */
@Tag("mysql")
@Testcontainers
class IntegrationHubV192AuditRetentionMySqlTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("nf05_v192").withUsername("root").withPassword("ses");
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("188").load().migrate();
        jdbc.update("""
                INSERT INTO t_external_api_audit
                (pre_auth_principal,post_auth_principal,correlation_id,method,route_template,
                 authentication_decision,scope_decision,data_scope_decision,command_decision,rate_decision,
                 status,result_code,success_flag,created_at)
                VALUES ('UNAUTHENTICATED','NONE','v192-historical','GET','/external-api/v1/projects',
                        'NOT_REACHED','NOT_REACHED','NOT_REACHED','NOT_REACHED','NOT_REACHED',
                        401,'AUTHENTICATION_FAILED',0,'2020-02-29 09:00:00')
                """);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    }

    @Test
    void 既存JST時刻をUTCへ変換し一年期限とindexおよび新kindを適用する() {
        var created = jdbc.queryForObject("SELECT created_at FROM t_external_api_audit WHERE correlation_id='v192-historical'",
                java.sql.Timestamp.class).toLocalDateTime();
        var expires = jdbc.queryForObject("SELECT retention_expires_at FROM t_external_api_audit WHERE correlation_id='v192-historical'",
                java.sql.Timestamp.class).toLocalDateTime();
        assertThat(created).isEqualTo(LocalDateTime.of(2020, 2, 29, 0, 0));
        assertThat(expires).isEqualTo(LocalDateTime.of(2021, 2, 28, 0, 0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name='t_external_api_audit' AND index_name='idx_external_audit_retention'", Integer.class)).isEqualTo(2);
        jdbc.update("INSERT INTO t_api_purge_checkpoint(record_kind,retention_class,updated_at) "
                + "VALUES('EXTERNAL_AUDIT','AUDIT_METADATA_1Y',UTC_TIMESTAMP())");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE version='192' AND success=1",
                String.class)).isEqualTo("192");
    }

    @Test
    void 小数秒Clockの実producerはDATETIME0往復後も送信bindingと現行ownershipを満たす() throws Exception {
        String client = "precision-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO t_engineer(full_name,employment_type,status,tenant_id,legal_entity_id) "
                + "VALUES(?,'正社員','Bench','v192-tenant',9)", client);
        long engineer = jdbc.queryForObject("SELECT id FROM t_engineer WHERE full_name=?", Long.class, client);
        String scope = "{\"tenantIds\":[\"v192-tenant\"],\"legalEntityIds\":[\"9\"],\"engineerIds\":[\"" + engineer + "\"]}";
        jdbc.update("INSERT INTO m_api_client(client_id,owner_ref,tenant_id,legal_entity_id,data_scope_json,allowed_cidrs) "
                + "VALUES(?,'PROJECT_OWNER','v192-tenant',9,?,'')", client, scope);
        long clientId = jdbc.queryForObject("SELECT id FROM m_api_client WHERE client_id=?", Long.class, client);
        jdbc.update("INSERT INTO m_api_client_scope(api_client_id,scope_code,operation_code,data_scope_json) "
                + "VALUES(?,'integration.availability.read','integration.webhook.deliver',?)", clientId, scope);
        jdbc.update("INSERT INTO m_webhook_subscription(client_id,direction,event_type,endpoint_url,key_id,encrypted_signing_secret,"
                + "crypto_key_version,data_scope_json,signing_credential_version) "
                + "VALUES(?,'OUTBOUND','resource.changed','https://web.example:443/hook','key-1','synthetic','v1',?,1)", client, scope);
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        var sqlFactory = factory.getObject();
        for (Class<?> mapper : List.of(ApiDeliveryMapper.class, com.ses.mapper.ApiClientMapper.class,
                com.ses.mapper.ApiClientScopeMapper.class, com.ses.mapper.WebhookSubscriptionMapper.class,
                com.ses.mapper.ExternalApiReadMapper.class, com.ses.mapper.IntegrationHubWebhookResourceScopeMapper.class))
            sqlFactory.getConfiguration().addMapper(mapper);
        var session = new org.mybatis.spring.SqlSessionTemplate(sqlFactory);
        var clientMapper = session.getMapper(com.ses.mapper.ApiClientMapper.class);
        var deliveryMapper = session.getMapper(ApiDeliveryMapper.class);
        var ownership = session.getMapper(com.ses.mapper.IntegrationHubWebhookResourceScopeMapper.class);
        var properties = new com.ses.config.integrationhub.IntegrationHubExternalApiProperties();
        properties.getExternalTransport().setEnabled(true); properties.getTopology().setBoundTenantId("v192-tenant");
        properties.getPublicApi().setPublicIdKey("synthetic-mysql-precision-public-id-key-at-least-32-bytes");
        var codec = new com.ses.config.integrationhub.ExternalApiPublicIdCodec(properties);
        var binding = new com.ses.service.integrationhub.IntegrationHubWebhookDeliveryBindingValidator(clientMapper, codec);
        var service = new com.ses.service.integrationhub.impl.ApiDeliveryServiceImpl(deliveryMapper, binding);
        var fractionalClock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-15T01:23:45.987654Z"), java.time.ZoneOffset.UTC);
        var producer = new com.ses.service.integrationhub.IntegrationHubResourceChangedProducer(
                session.getMapper(com.ses.mapper.WebhookSubscriptionMapper.class), clientMapper,
                session.getMapper(com.ses.mapper.ApiClientScopeMapper.class), session.getMapper(com.ses.mapper.ExternalApiReadMapper.class),
                codec, properties, service, new com.fasterxml.jackson.databind.ObjectMapper(), fractionalClock, ownership);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        com.ses.service.accounting.AccountingTenantContextHolder.setTenantId("v192-tenant");
        try { transactions.executeWithoutResult(status -> producer.changed("engineer-availability", engineer)); }
        finally { com.ses.service.accounting.AccountingTenantContextHolder.clear(); }
        var rows = deliveryMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.integrationhub.ApiDelivery>()
                .eq("client_id", client));
        assertThat(rows).hasSize(1);
        var delivery = rows.get(0);
        assertThat(delivery.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 1, 23, 45));
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(delivery.getExternalDtoSnapshot()).get("createdAt").asText())
                .isEqualTo("2026-11-15T01:23:45Z");
        binding.requireForSend(delivery, com.ses.service.integrationhub.ExternalDtoSnapshot.of(delivery.getExternalDtoSnapshot()),
                delivery.getCreatedAt());
        assertThat(ownership.isCurrentOwnership("engineer-availability", engineer, "v192-tenant", 9L)).isTrue();
        jdbc.update("UPDATE t_engineer SET tenant_id='moved-tenant' WHERE id=?", engineer);
        assertThat(ownership.isCurrentOwnership("engineer-availability", engineer, "v192-tenant", 9L)).isFalse();
    }

    @Test
    void 永続OFFからの再開だけを隔離しON再起動とreplay期限起点のSQLを検証する() throws Exception {
        String client="restart-"+UUID.randomUUID();
        jdbc.update("INSERT INTO m_api_client(client_id,owner_ref,tenant_id,legal_entity_id,data_scope_json,allowed_cidrs) VALUES(?,'PROJECT_OWNER','v192-tenant',9,'{}','')",client);
        jdbc.update("INSERT INTO m_webhook_subscription(client_id,direction,event_type,endpoint_url,key_id,encrypted_signing_secret,crypto_key_version,data_scope_json) "
                + "VALUES(?,'OUTBOUND','resource.changed','https://web.example.com:443/hook','key-1','synthetic','v1','{}')",client);
        long subscription=jdbc.queryForObject("SELECT id FROM m_webhook_subscription WHERE client_id=?",Long.class,client);
        String event=UUID.randomUUID().toString(); String hash="a".repeat(64);
        jdbc.update("INSERT INTO t_api_delivery(event_id,subscription_id,client_id,scope_code,tenant_id,event_type,schema_version,provider_idempotency_key,external_dto_snapshot,payload_hash,scope_digest,created_at) "
                + "VALUES(?,?,?,'integration.project.read','v192-tenant','resource.changed','v1',?,'{}',?,?,'2026-11-01 00:00:00')",event,subscription,client,event,hash,hash);
        long original=jdbc.queryForObject("SELECT id FROM t_api_delivery WHERE event_id=?",Long.class,event);
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        var sqlFactory=factory.getObject();
        for(Class<?> mapper:List.of(ApiDeliveryMapper.class,com.ses.mapper.SystemConfigMapper.class,com.ses.mapper.AuditLogMapper.class)) sqlFactory.getConfiguration().addMapper(mapper);
        var session=new org.mybatis.spring.SqlSessionTemplate(sqlFactory);
        var mapper=session.getMapper(ApiDeliveryMapper.class); var configs=session.getMapper(com.ses.mapper.SystemConfigMapper.class);
        var audit=new com.ses.service.impl.AuditLogServiceImpl(session.getMapper(com.ses.mapper.AuditLogMapper.class));
        var manager=new DataSourceTransactionManager(source); var clock=java.time.Clock.fixed(java.time.Instant.parse("2026-11-15T00:00:00Z"),java.time.ZoneOffset.UTC);
        var properties=new com.ses.config.integrationhub.IntegrationHubExternalApiProperties(); properties.getTopology().setBoundTenantId("v192-tenant");
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        String key=com.ses.service.integrationhub.IntegrationHubOutboundStartupBarrier.CONFIG_KEY;
        try {
            var off=new com.ses.service.integrationhub.IntegrationHubOutboundStartupBarrier(mapper,audit,manager,clock,configs,properties,meters); off.start();
            assertThat(configs.selectById(key).getConfigValue()).startsWith("v1|OFF|");
            properties.getExternalTransport().setEnabled(true);
            var on=new com.ses.service.integrationhub.IntegrationHubOutboundStartupBarrier(mapper,audit,manager,clock,configs,properties,meters);
            on.start(); assertThat(on.isReady()).isFalse(); on.run(new org.springframework.boot.DefaultApplicationArguments());
            assertThat(on.isReady()).isTrue(); assertThat(mapper.selectById(original).getStatus()).isEqualTo("DLQ");
            jdbc.update("UPDATE t_api_delivery SET status='RETRYABLE' WHERE id=?",original);
            on.stop(); on.start(); on.run(new org.springframework.boot.DefaultApplicationArguments());
            assertThat(on.isReady()).isTrue(); assertThat(mapper.selectById(original).getStatus()).isEqualTo("RETRYABLE");
            jdbc.update("INSERT INTO t_api_delivery_replay_audit(delivery_id,event_id,replay_generation,operator_ref,reason_code,scope_digest,payload_hash,created_at,retention_class,retention_expires_at) "
                    + "VALUES(?,?,2,'sys-user:42','ENVIRONMENT_FIXED',?,?,'2026-11-15 00:00:00','AUDIT_METADATA_1Y','2027-11-15 00:00:00')",original,event,hash,hash);
            assertThat(mapper.selectReplayWindowStart(event,2,subscription,client)).isEqualTo(LocalDateTime.of(2026,11,15,0,0));
            assertThat(mapper.selectReplayWindowStart(event,2,subscription,"another-client")).isNull();
        } finally {
            jdbc.update("DELETE FROM m_system_config WHERE config_key=?",key);
        }
    }

    @Test
    void 同一点の明示lockを待った二つのclaimは一方だけ成功しattemptを一回増やす() throws Exception {
        String client = "v192-" + UUID.randomUUID();
        jdbc.update("INSERT INTO m_api_client(client_id,owner_ref,tenant_id,legal_entity_id,data_scope_json,allowed_cidrs) "
                + "VALUES(?, 'PROJECT_OWNER','v192-tenant',9,'{}','')", client);
        jdbc.update("INSERT INTO m_webhook_subscription(client_id,direction,event_type,endpoint_url,key_id,"
                + "encrypted_signing_secret,crypto_key_version,data_scope_json) "
                + "VALUES(?,'OUTBOUND','resource.changed','https://web.example:443/hook','key-1','synthetic','v1','{}')", client);
        long subscription = jdbc.queryForObject("SELECT id FROM m_webhook_subscription WHERE client_id=?", Long.class, client);
        jdbc.update("INSERT INTO t_api_delivery(event_id,subscription_id,delivery_generation,client_id,scope_code,"
                + "tenant_id,scope_digest,event_type,schema_version,provider_idempotency_key,external_dto_snapshot,"
                + "payload_hash,status,next_attempt_at) VALUES(?,?,1,?,'project','v192-tenant',?,'resource.changed',"
                + "'v1',?,'{}',?,'PENDING','2026-11-14 00:00:00')",
                client, subscription, client, "a".repeat(64), "b".repeat(64), "c".repeat(64));
        long id = jdbc.queryForObject("SELECT id FROM t_api_delivery WHERE event_id=?", Long.class, client);
        String sql = String.join(" ", ApiDeliveryMapper.class.getMethod("claim", Long.class, Integer.class,
                String.class, LocalDateTime.class, LocalDateTime.class).getAnnotation(Update.class).value())
                .replaceAll("#\\{([A-Za-z]+)\\}", ":$1");
        var executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT id FROM t_api_delivery WHERE id=? FOR UPDATE")) {
                lock.setLong(1, id);
                try (var row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            long connectionId;
            try (var statement = blocker.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                assertThat(result.next()).isTrue(); connectionId = result.getLong(1);
            }
            var anchor = InnoDbRecordLockWaits.requireSinglePointLock(jdbc, connectionId, "t_api_delivery",
                    "PRIMARY", InnoDbRecordLockWaits.primaryKey(id), "NF05 claim");
            var start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int number = 0; number < 2; number++) {
                String lease = "v192-lease-" + number;
                futures.add(executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(transaction ->
                            new NamedParameterJdbcTemplate(source).update(sql, Map.of("id", id, "version", 0,
                                    "leaseToken", lease, "leaseExpiresAt", LocalDateTime.of(2026, 11, 14, 0, 1),
                                    "now", LocalDateTime.of(2026, 11, 14, 0, 0))));
                }));
            }
            start.countDown();
            InnoDbRecordLockWaits.awaitBlockerAnchoredWait(jdbc, anchor, null,
                    InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES,
                    10000, () -> assertThat(futures).allMatch(future -> !future.isDone()), "NF05 claim");
            blocker.commit();
            assertThat(futures.get(0).get(15, TimeUnit.SECONDS) + futures.get(1).get(15, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM t_api_delivery WHERE id=?", Integer.class, id)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
