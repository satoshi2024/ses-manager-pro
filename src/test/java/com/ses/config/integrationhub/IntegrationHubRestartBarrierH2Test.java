package com.ses.config.integrationhub;

import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.AuditLogService;
import com.ses.service.integrationhub.IntegrationHubDigest;
import com.ses.service.integrationhub.IntegrationHubOutboundStartupBarrier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 状態行・CAS・required auditを実DBで往復し、起動失敗をNF05内へ閉じ込める。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:g3s_restart;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL")
@ActiveProfiles("test")
@Transactional
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class IntegrationHubRestartBarrierH2Test {
    @Autowired JdbcTemplate jdbc;
    @Autowired ApiDeliveryMapper deliveries;
    @Autowired SystemConfigMapper configs;
    @Autowired PlatformTransactionManager manager;
    @SpyBean AuditLogService audit;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);
    private TransactionTemplate tx;
    private String client;
    private long subscription;
    private final MeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach void setup() {
        TestTransaction.end(); tx = new TransactionTemplate(manager);
        client = "g3s-" + UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM m_system_config WHERE config_key=?", IntegrationHubOutboundStartupBarrier.CONFIG_KEY);
            jdbc.update("INSERT INTO m_api_client(client_id,owner_ref,tenant_id,legal_entity_id,data_scope_json,allowed_cidrs) "
                    + "VALUES(?,'PROJECT_OWNER','restart-tenant',9,'{}','')", client);
            jdbc.update("INSERT INTO m_webhook_subscription(client_id,direction,event_type,endpoint_url,key_id,encrypted_signing_secret,crypto_key_version,data_scope_json) "
                    + "VALUES(?,'OUTBOUND','resource.changed','https://web.example.com:443/hook','key-1','synthetic','v1','{}')", client);
            subscription = jdbc.queryForObject("SELECT id FROM m_webhook_subscription WHERE client_id=?", Long.class, client);
        });
    }
    @AfterEach void cleanup() {
        reset(audit);
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM t_audit_log WHERE uri='/integration-hub/outbound-state'");
            jdbc.update("DELETE FROM t_api_delivery WHERE client_id=?", client);
            jdbc.update("DELETE FROM m_webhook_subscription WHERE client_id=?", client);
            jdbc.update("DELETE FROM m_api_client WHERE client_id=?", client);
            jdbc.update("DELETE FROM m_system_config WHERE config_key=?", IntegrationHubOutboundStartupBarrier.CONFIG_KEY);
        });
        registry.clear();
    }
    @Test void 未観測の初回ONは送らずOFFの永続成功を必要とする() {
        addRows(1); var unknown = barrier(true); unknown.start(); unknown.run(new DefaultApplicationArguments());
        assertThat(unknown.isReady()).isFalse(); assertThat(state()).isNull(); assertThat(count("PENDING")).isEqualTo(1);
        var off = barrier(false); off.start(); assertThat(state()).startsWith("v1|OFF|");
        var on = barrier(true); on.start(); on.run(new DefaultApplicationArguments());
        assertThat(on.isReady()).isTrue(); assertThat(count("DLQ")).isEqualTo(1);
    }
    @Test void ONからONの再起動は正常なPENDINGとRETRYABLEを隔離しない() {
        addRows(2); tx.executeWithoutResult(s -> jdbc.update("UPDATE t_api_delivery SET status='RETRYABLE' WHERE client_id=?", client));
        setState("ON", 0); var restarted = barrier(true); restarted.start(); restarted.run(new DefaultApplicationArguments());
        assertThat(restarted.isReady()).isTrue(); assertThat(count("RETRYABLE")).isEqualTo(2); assertThat(count("DLQ")).isZero();
        restarted.stop(); restarted.start(); restarted.run(new DefaultApplicationArguments());
        assertThat(restarted.isReady()).isTrue(); assertThat(count("RETRYABLE")).isEqualTo(2);
    }
    @Test void OFF再開の境界はcrash後も維持し有効leaseを奪わず後続の新行を隔離しない() {
        barrier(false).start(); addRows(2);
        Long leased = jdbc.queryForObject("SELECT MAX(id) FROM t_api_delivery WHERE client_id=?", Long.class, client);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE t_api_delivery SET status='CLAIMED',lease_token='synthetic',lease_expires_at='2026-11-15 00:01:00' WHERE id=?", leased));
        var on = barrier(true); on.start(); String fixed = state(); addRows(1); on.run(new DefaultApplicationArguments());
        assertThat(on.isReady()).isFalse(); assertThat(count("DLQ")).isEqualTo(1); assertThat(count("CLAIMED")).isEqualTo(1);
        var restarted = barrier(true); restarted.start(); assertThat(state()).isEqualTo(fixed);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE t_api_delivery SET lease_expires_at='2026-11-14 23:59:59' WHERE id=?", leased));
        restarted.continueRecovery(); assertThat(restarted.isReady()).isTrue();
        assertThat(count("DLQ")).isEqualTo(2); assertThat(count("PENDING")).isEqualTo(1);
        // 完了後の周期は古いIDを再走査せず、正常な新行を残す。
        restarted.continueRecovery(); assertThat(count("PENDING")).isEqualTo(1);
    }
    @Test void 四千九十七件でも起動を失敗させず一周期128件ずつ完了へ進む() {
        barrier(false).start(); addRows(4097); var on = barrier(true); on.start();
        assertThatCode(() -> on.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThat(on.isReady()).isFalse(); assertThat(count("DLQ")).isEqualTo(128);
        for (int i=0; i<40 && !on.isReady(); i++) on.continueRecovery();
        assertThat(on.isReady()).isTrue(); assertThat(count("DLQ")).isEqualTo(4097);
    }
    @Test void 隔離監査が失敗した周期はrollbackしてschedulerの次回で回復する() {
        barrier(false).start(); addRows(1); var on = barrier(true); on.start();
        doAnswer(call -> { var entry = (com.ses.entity.AuditLog) call.getArgument(0);
            if ("NF05_STARTUP_QUARANTINE".equals(entry.getApplicationCode())) throw new IllegalStateException("synthetic audit failure");
            return call.callRealMethod(); }).when(audit).recordDomainEventRequired(any());
        assertThatCode(() -> on.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThat(on.isReady()).isFalse(); assertThat(count("PENDING")).isEqualTo(1); assertThat(state()).contains("QUARANTINING");
        reset(audit); on.continueRecovery(); assertThat(on.isReady()).isTrue(); assertThat(count("DLQ")).isEqualTo(1);
    }
    @Test void DB障害でもSpringの起動を妨げずOFF確認を後で再試行する() {
        var broken = mock(SystemConfigMapper.class);
        when(broken.selectByIdForUpdate(anyString())).thenThrow(new IllegalStateException("synthetic DB failure"));
        var props = properties(false); ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        when(meters.getIfAvailable()).thenReturn(registry);
        new ApplicationContextRunner().withBean(IntegrationHubOutboundStartupBarrier.class,
                () -> new IntegrationHubOutboundStartupBarrier(deliveries, audit, manager, clock, broken, props, meters))
                .run(context -> { assertThat(context).hasNotFailed(); var bean = context.getBean(IntegrationHubOutboundStartupBarrier.class);
                    assertThat(bean.isRunning()).isTrue(); assertThat(bean.isReady()).isFalse();
                    bean.run(new DefaultApplicationArguments());
                    assertThat(registry.get("integration_hub_startup_quarantine_pending").gauge().value()).isEqualTo(1); });
        assertThat(state()).isNull(); var off = barrier(false); off.start(); assertThat(state()).startsWith("v1|OFF|");
    }
    @Autowired com.ses.service.integrationhub.ApiRetentionPurgeService purge;

    /**
     * review rb-fe-d P2-2: DB の開放状態が確定の閉鎖の間、未送信（PENDING・RETRYABLE）の配信は barrier が DLQ KILL_SWITCH にし、保存期間を
     * 過ぎると purge される（NF05 を開き直さなくても外部へ送るための写しを残し続けない）。一時の閉鎖（DB は OPEN）では変えない。
     */
    @Test void 確定の閉鎖の間は未送信をDLQのKILL_SWITCHにし保存期間の後にpurgeし一時の閉鎖では変えない() {
        addRows(2); tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE t_api_delivery SET status='RETRYABLE', last_error_code='HTTP_RETRYABLE' WHERE id=(SELECT MAX(id) FROM t_api_delivery WHERE client_id=?)",
                client));
        var runtime = mock(com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.class);
        when(runtime.outboundClosure()).thenReturn(com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure.TRANSIENT);
        var closed = barrier(true); closed.setRuntimeSwitch(runtime); closed.start();
        closed.continueRecovery();
        assertThat(count("PENDING") + count("RETRYABLE")).as("一時の閉鎖では変えない").isEqualTo(2);

        when(runtime.outboundClosure()).thenReturn(com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
        when(runtime.definitivelyClosedInDatabase(com.ses.config.feature.Feature.NF05_OUTBOUND)).thenReturn(true);
        closed.continueRecovery();
        assertThat(count("DLQ")).isEqualTo(2);
        Integer killed = tx.execute(s -> jdbc.queryForObject("SELECT COUNT(*) FROM t_api_delivery WHERE client_id=? AND "
                + "last_error_code='KILL_SWITCH' AND retention_class='FAILED_DLQ_PAYLOAD_90D' AND retention_expires_at IS NOT NULL",
                Integer.class, client));
        assertThat(killed).isEqualTo(2);
        Integer audited = tx.execute(s -> jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE application_code="
                + "'NF05_OUTBOUND_CLOSED_DLQ' AND reference_type='NF05'", Integer.class));
        assertThat(audited).isEqualTo(1);

        LocalDateTime afterRetention = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(91);
        purge.purgeExpired("DELIVERY", "FAILED_DLQ_PAYLOAD_90D", afterRetention, 100);
        assertThat(count("DLQ")).as("保存期間の後に purge される").isZero();
    }

    private IntegrationHubExternalApiProperties properties(boolean enabled) {
        var props = new IntegrationHubExternalApiProperties(); props.getExternalTransport().setEnabled(enabled);
        props.getTopology().setBoundTenantId("restart-tenant"); return props;
    }
    private IntegrationHubOutboundStartupBarrier barrier(boolean enabled) {
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class); when(meters.getIfAvailable()).thenReturn(registry);
        return new IntegrationHubOutboundStartupBarrier(deliveries, audit, manager, clock, configs, properties(enabled), meters);
    }
    private void setState(String phase, long water) {
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO m_system_config(config_key,config_value) VALUES(?,?)", IntegrationHubOutboundStartupBarrier.CONFIG_KEY,
                "v1|"+phase+"|"+water+"|"+IntegrationHubDigest.sha256Hex("restart-tenant")));
    }
    private String state() { return tx.execute(s -> { var row = configs.selectById(IntegrationHubOutboundStartupBarrier.CONFIG_KEY); return row == null ? null : row.getConfigValue(); }); }
    private int count(String status) { return tx.execute(s -> jdbc.queryForObject("SELECT COUNT(*) FROM t_api_delivery WHERE client_id=? AND status=?", Integer.class, client, status)); }
    private void addRows(int size) {
        List<Object[]> batch = new ArrayList<>();
        for(int i=0;i<size;i++) { String event=UUID.randomUUID().toString(); batch.add(new Object[]{event, subscription, client,
                IntegrationHubDigest.sha256Hex(event), IntegrationHubDigest.sha256Hex("{}")}); }
        tx.executeWithoutResult(s -> jdbc.batchUpdate("INSERT INTO t_api_delivery(event_id,subscription_id,client_id,tenant_id,scope_code,event_type,schema_version,provider_idempotency_key,external_dto_snapshot,payload_hash,primary_resource_type,primary_resource_id,scope_digest,created_at) "
                + "VALUES(?,?,?,'restart-tenant','integration.project.read','resource.changed','v1',?,'{}',?,'project',1,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','2026-11-14 00:00:00')", batch));
    }
}
