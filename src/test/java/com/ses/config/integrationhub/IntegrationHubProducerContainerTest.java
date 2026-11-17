package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.LoginUser;
import com.ses.entity.Engineer;
import com.ses.entity.SysUser;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.service.EngineerService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.integrationhub.*;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.*;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** productionのauto-proxy creatorを通す。手動ProxyFactoryやproducer mockは使わない。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:g3r_producer_container;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "integration.hub.external-transport.enabled=yes", "integration.hub.provider.mode=MOCK",
        "integration.hub.topology.bound-tenant-id=default",
        "integration.hub.public-api.public-id-key=synthetic-container-public-id-key-at-least-32-bytes",
        "integration.hub.crypto.current-key-version=v1",
        "integration.hub.crypto.keys.v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "integration.hub.crypto.credential-hash-pepper=c3ludGhldGljLW5mMDUtdGVzdC1vbmx5LXBlcHBlciE"})
@ActiveProfiles("test")
@Transactional
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class IntegrationHubProducerContainerTest {
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired IntegrationHubOutboundStartupBarrier originalBarrier;
    @Autowired com.ses.service.AuditLogService audit;
    @Autowired Clock clock;
    @Autowired EngineerService engineers;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApiDeliveryMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired IntegrationHubWebhookDeliveryBindingValidator binding;
    @Autowired IntegrationHubWebhookDeliveryWorker worker;
    @Autowired IntegrationHubResourceChangedProducer producer;
    @Autowired IntegrationHubSecretCryptoService crypto;
    @Autowired @Qualifier("integrationHubResourceProducerAdvisor") Advisor advisor;
    @SpyBean ApiDeliveryService deliveries;
    // 法人選択UIのfixtureを本試験の責務外にし、業務service/producer/ledger/transactionは全て実物を使う。
    @MockBean LegalEntityContextService legalContext;
    private String client;
    private long engineer;
    private TransactionTemplate transactions;

    @TestConfiguration
    static class Time {
        @Bean @Primary Clock fractionalClock() {
            return Clock.fixed(Instant.parse("2026-11-15T01:23:45.654321Z"), ZoneOffset.UTC);
        }
    }

    @BeforeEach void setUp() {
        TestTransaction.end();
        transactions = new TransactionTemplate(manager);
        when(legalContext.requireCurrentLegalEntityId()).thenReturn(9L);
        when(legalContext.requireCurrentDate()).thenReturn(LocalDate.of(2026, 11, 15));
        AccountingTenantContextHolder.setTenantId("default");
        var user = SysUser.builder().username("synthetic-admin").role("管理者").status(1).build();
        user.setId(99001L); user.setTenantId("default");
        var principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "unused", principal.getAuthorities()));
        client = "g3r-" + UUID.randomUUID().toString().replace("-", "");
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM m_system_config WHERE config_key=?", IntegrationHubOutboundStartupBarrier.CONFIG_KEY);
            jdbc.update("INSERT INTO m_system_config(config_key,config_value) VALUES(?,?)", IntegrationHubOutboundStartupBarrier.CONFIG_KEY,
                    "v1|ON|0|" + IntegrationHubDigest.sha256Hex("default"));
            jdbc.update("INSERT INTO m_organization_unit(legal_entity_id,code,name,type,valid_from) "
                    + "VALUES(9,?,?,'チーム','2020-01-01')", client, client);
            engineer = insert("INSERT INTO t_engineer(full_name,employment_type,status,tenant_id,legal_entity_id,version) "
                    + "VALUES(?,'正社員','Bench','default',9,0)", client);
            String scope = "{\"tenantIds\":[\"default\"],\"legalEntityIds\":[\"9\"],\"engineerIds\":[\"" + engineer + "\"]}";
            long id = insert("INSERT INTO m_api_client(client_id,owner_ref,tenant_id,legal_entity_id,data_scope_json,allowed_cidrs) "
                    + "VALUES(?,'PROJECT_OWNER','default',9,?,'')", client, scope);
            jdbc.update("INSERT INTO m_api_client_scope(api_client_id,scope_code,operation_code,data_scope_json) "
                    + "VALUES(?,'integration.availability.read','integration.webhook.deliver',?)", id, scope);
            String secret = crypto.encrypt(client, 1, "webhook-signing", "synthetic-webhook-secret");
            jdbc.update("INSERT INTO t_credential_version(api_client_id,credential_version,key_id,encrypted_secret,secret_hash,"
                    + "crypto_key_version,issued_at,expires_at) VALUES(?,1,'key-1',?,?,'v1','2026-01-01 00:00:00','2027-01-01 00:00:00')",
                    id, crypto.encrypt(client, 1, "credential", "synthetic-webhook-secret"),
                    crypto.credentialHash(client, 1, "synthetic-webhook-secret"));
            jdbc.update("INSERT INTO m_webhook_subscription(client_id,direction,event_type,endpoint_url,key_id,"
                    + "encrypted_signing_secret,crypto_key_version,data_scope_json,signing_credential_version) "
                    + "VALUES(?,'OUTBOUND','resource.changed','https://web.example.com:443/hook','key-1',?,'v1',?,1)", client, secret, scope);
        });
        originalBarrier.start();
    }

    @AfterEach void cleanup() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM t_audit_log WHERE human_user_id=99001 OR (reference_type='NF05' AND reference_id IN "
                    + "(SELECT id FROM t_api_delivery WHERE client_id=?))", client);
            jdbc.update("DELETE FROM t_api_delivery_replay_audit WHERE event_id IN (SELECT event_id FROM t_api_delivery WHERE client_id=?)", client);
            jdbc.update("DELETE FROM t_api_delivery WHERE client_id=?", client);
            jdbc.update("DELETE FROM m_api_client_scope WHERE api_client_id IN (SELECT id FROM m_api_client WHERE client_id=?)", client);
            jdbc.update("DELETE FROM m_webhook_subscription WHERE client_id=?", client);
            jdbc.update("DELETE FROM t_credential_version WHERE api_client_id IN (SELECT id FROM m_api_client WHERE client_id=?)", client);
            jdbc.update("DELETE FROM m_api_client WHERE client_id=?", client);
            jdbc.update("DELETE FROM t_engineer_accounting_history WHERE engineer_id=?", engineer);
            jdbc.update("DELETE FROM t_engineer WHERE id=?", engineer);
            jdbc.update("DELETE FROM m_organization_unit WHERE code=?", client);
        });
        AccountingTenantContextHolder.clear(); SecurityContextHolder.clearContext();
    }

    @Test void 実容器のadvisorが業務更新とdeliveryを同時にcommitする() {
        assertThat(AopUtils.isAopProxy(engineers)).isTrue();
        assertThat(((Advised) engineers).getAdvisors()).contains(advisor);
        transactions.executeWithoutResult(status -> assertThat(engineers.updateWithStatusGuard(patch())).isTrue());
        transactions.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT status FROM t_engineer WHERE id=?", String.class, engineer)).isEqualTo("提案中");
            assertThat(ownDeliveries()).hasSize(1);
        });
    }

    @Test void enqueue永続化失敗後は新transactionから業務とdeliveryのrollbackを確認する() {
        doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic-ledger-failure"))
                .when(deliveries).enqueue(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyString(),
                        anyString(), anyLong(), anyString(), anyString(), anyString(), any(), any());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> engineers.updateWithStatusGuard(patch())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("synthetic-ledger-failure");
        verify(deliveries).enqueue(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString(), anyString(), anyString(), any(), any());
        transactions.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT status FROM t_engineer WHERE id=?", String.class, engineer)).isEqualTo("Bench");
            assertThat(ownDeliveries()).isEmpty();
        });
    }

    @Test void 小数秒clockでもDB往復後のenvelope検証とmock送信が成功する() throws Exception {
        transactions.executeWithoutResult(status -> engineers.updateWithStatusGuard(patch()));
        ApiDelivery row = transactions.execute(status -> ownDeliveries().get(0));
        assertThat(row.getCreatedAt().getNano()).isZero();
        assertThat(new ObjectMapper().readTree(row.getExternalDtoSnapshot()).get("createdAt").asText())
                .isEqualTo("2026-11-15T01:23:45Z");
        binding.requireForSend(row, ExternalDtoSnapshot.of(row.getExternalDtoSnapshot()), row.getCreatedAt());
        assertThat(worker.dispatchOne(row.getId(), row.getCreatedAt())).isTrue();
        transactions.executeWithoutResult(status -> assertThat(mapper.selectById(row.getId()).getStatus()).isEqualTo("SUCCEEDED"));
    }

    @Test void 明示tenant付きbackground更新も実serviceからenqueueする() {
        java.util.concurrent.CompletableFuture.runAsync(() -> AccountingTenantContextHolder.runWithTenant("default", ZoneOffset.UTC,
                () -> transactions.executeWithoutResult(status -> engineers.updateWithStatusGuard(patch())))).join();
        transactions.executeWithoutResult(status -> assertThat(ownDeliveries()).hasSize(1));
    }

    @Test void 管理controllerはCSRFと管理者を要求しbody付きDELETEを処理する() throws Exception {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String body = "{\"clientId\":\"" + client + "\",\"legalEntityId\":9,\"version\":1,\"keyId\":\"key-1\"}";
        var url = "/api/integration-hub/credentials";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))
                .contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("sales").roles("営業"))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
        transactions.executeWithoutResult(status -> assertThat(jdbc.queryForObject("SELECT status FROM t_credential_version "
                + "WHERE api_client_id=(SELECT id FROM m_api_client WHERE client_id=?)", String.class, client)).isEqualTo("REVOKED"));
    }

    @Test void OFFからONへの再起動は未来dueと旧leaseを隔離し新しいIDだけを送る() {
        for (int version = 0; version < 3; version++) {
            Engineer patch = patch(); patch.setVersion(version);
            transactions.executeWithoutResult(status -> engineers.updateWithStatusGuard(patch));
        }
        var old = transactions.execute(status -> ownDeliveries().stream().sorted(java.util.Comparator.comparing(ApiDelivery::getId)).toList());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0);
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE t_api_delivery SET next_attempt_at=? WHERE id=?", now.plusYears(1), old.get(0).getId());
            jdbc.update("UPDATE t_api_delivery SET status='RETRYABLE' WHERE id=?", old.get(1).getId());
            jdbc.update("UPDATE t_api_delivery SET status='CLAIMED',lease_token='old-lease',lease_expires_at=? WHERE id=?",
                    now.plusMinutes(1), old.get(2).getId());
        });
        transactions.executeWithoutResult(status -> jdbc.update("UPDATE m_system_config SET config_value=? WHERE config_key=?",
                "v1|OFF|0|" + IntegrationHubDigest.sha256Hex("default"), IntegrationHubOutboundStartupBarrier.CONFIG_KEY));
        var restarted = context.getAutowireCapableBeanFactory().createBean(IntegrationHubOutboundStartupBarrier.class);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "startupBarrier", restarted);
        try {
            assertThat(worker.dispatchDue()).isZero();
            restarted.start();
            restarted.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
            transactions.executeWithoutResult(status -> {
                assertThat(mapper.selectById(old.get(0).getId()).getStatus()).isEqualTo("DLQ");
                assertThat(mapper.selectById(old.get(1).getId()).getStatus()).isEqualTo("DLQ");
                assertThat(mapper.selectById(old.get(2).getId()).getStatus()).isEqualTo("CLAIMED");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE application_code='NF05_STARTUP_QUARANTINE' "
                        + "AND reference_id IN (SELECT id FROM t_api_delivery WHERE client_id=?)", Integer.class, client)).isGreaterThan(0);
            });
            assertThat(worker.dispatchOne(old.get(2).getId(), now)).isFalse();
            transactions.executeWithoutResult(status -> jdbc.update("UPDATE t_api_delivery SET lease_expires_at=? WHERE id=?",
                    now.minusSeconds(1), old.get(2).getId()));
            worker.dispatchDue();
            transactions.executeWithoutResult(status -> assertThat(mapper.selectById(old.get(2).getId()).getStatus()).isEqualTo("DLQ"));
            Engineer freshPatch = patch(); freshPatch.setVersion(3);
            transactions.executeWithoutResult(status -> engineers.updateWithStatusGuard(freshPatch));
            var fresh = transactions.execute(status -> ownDeliveries().stream().max(java.util.Comparator.comparing(ApiDelivery::getId)).orElseThrow());
            assertThat(restarted.isHistorical(fresh.getId())).isFalse();
            assertThat(worker.dispatchOne(fresh.getId(), now)).isTrue();
            transactions.executeWithoutResult(status -> assertThat(mapper.selectById(fresh.getId()).getStatus()).isEqualTo("SUCCEEDED"));
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(worker, "startupBarrier", originalBarrier); }
    }

    @Test void runtime設定は一覧に出ず直接編集を拒否して通常設定は保存できる() throws Exception {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/system-configs")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(IntegrationHubOutboundStartupBarrier.CONFIG_KEY))));
        String hidden = "[{\"configKey\":\"" + IntegrationHubOutboundStartupBarrier.CONFIG_KEY + "\",\"configValue\":\"v1|ON|0|forged\"}]";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/system-configs")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content(hidden))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(400));
        var before = jdbc.queryForObject("SELECT config_value FROM m_system_config WHERE config_key='company.name'", String.class);
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/system-configs")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType("application/json").content("[{\"configKey\":\"company.name\",\"configValue\":\"synthetic-normal-setting\"}]"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
        } finally { transactions.executeWithoutResult(tx -> jdbc.update("UPDATE m_system_config SET config_value=? WHERE config_key='company.name'", before)); }
    }

    @Test void ONのproducerは実proxy経由のtransaction外呼出しを拒否する() {
        assertThat(AopUtils.isAopProxy(producer)).isTrue();
        assertThatThrownBy(() -> producer.changed("engineer-availability", engineer))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        List<ApiDelivery> current=transactions.execute(status -> ownDeliveries());
        assertThat(current).isEmpty();
    }

    @Test void bulkはCSRFとroleと条件を検査し繰返し要求でも未処理の最新generationへ進む() throws Exception {
        for (int version=0; version<3; version++) { Engineer row=patch(); row.setVersion(version);
            transactions.executeWithoutResult(status -> engineers.updateWithStatusGuard(row)); }
        var rows=transactions.execute(status -> ownDeliveries()); long subscription=rows.get(0).getSubscriptionId();
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE t_api_delivery SET status='DLQ',last_error_code='STARTUP_REVALIDATION_REQUIRED' WHERE client_id=?",client);
            jdbc.update("INSERT INTO m_api_client_scope(api_client_id,scope_code,operation_code,data_scope_json) "
                    + "SELECT id,'integration.availability.read','integration.webhook.replay',data_scope_json FROM m_api_client WHERE client_id=?",client);
        });
        var auth=SecurityContextHolder.getContext().getAuthentication();
        String url="/api/integration-hub/deliveries/bulk-replay";
        String prefix="{\"tenantId\":\"default\",\"subscriptionId\":"+subscription+",\"legalEntityId\":9,\"from\":\"2026-11-14T00:00:00Z\",\"until\":\"2026-11-16T00:00:00Z\",\"failureClass\":\"STARTUP_REVALIDATION_REQUIRED\",\"reasonCode\":\"ENVIRONMENT_FIXED\",\"limit\":";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))
                .contentType("application/json").content(prefix+"1}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("sales").roles("営業"))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content(prefix+"1}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        for (String invalid:List.of(prefix+"21}",(prefix+"1}").replace("default","other-tenant")))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType("application/json").content(invalid))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(400));
        for (int expected:List.of(1,2,0))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType("application/json").content(prefix+expectedLimit(expected)+"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.succeeded").value(expected))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.rejected").value(0))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.completionAuditRecorded").value(true));
        transactions.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_api_delivery WHERE client_id=? AND delivery_generation=2",Integer.class,client)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_api_delivery_replay_audit WHERE event_id IN (SELECT event_id FROM t_api_delivery WHERE client_id=?)",Integer.class,client)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE human_user_id=99001 AND application_code='NF05_BULK_REPLAY_REQUEST'",Integer.class)).isEqualTo(3);
            for (var replay:ownDeliveries()) if(replay.getDeliveryGeneration()==2)
                assertThat(mapper.selectReplayWindowStart(replay.getEventId(),2,replay.getSubscriptionId(),client))
                        .isEqualTo(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).withNano(0));
        });
    }
    private int expectedLimit(int expected) { return expected==1?1:2; }

    private Engineer patch() {
        Engineer row = new Engineer(); row.setId(engineer); row.setVersion(0); row.setStatus("提案中"); return row;
    }
    private List<ApiDelivery> ownDeliveries() {
        return mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApiDelivery>().eq("client_id", client));
    }
    private long insert(String sql, Object... arguments) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            return statement;
        }, holder);
        return holder.getKey().longValue();
    }
}
