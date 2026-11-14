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
