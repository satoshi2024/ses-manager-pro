package com.ses.featureenablement.nf05;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.config.integrationhub.IntegrationHubEnablementDecision;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.config.integrationhub.Nf05EnablementFixtures;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.featureenablement.catalog.SignOffRequirement;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.evaluation.FeatureEnablementEvaluationService;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.service.FeatureEnablementService;
import com.ses.featureenablement.service.FeatureSignOffRegistrar;
import com.ses.featureenablement.state.DatabaseFeatureEnablementStateSource;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.PropertyFeatureEnablementStateSource;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.AuditLogService;
import com.ses.service.integrationhub.IntegrationHubOutboundStartupBarrier;
import com.ses.service.security.LegalEntityReadinessService;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-D02・FE-D04（feature-enablement-console design §9.1〜§9.4、requirements FE-AC-008-02・03・06）: 実 MySQL 8.4 で NF05 outbound の
 * 承認 → 再起動 → 定期の評価 → もう一度の再起動を通す。再起動は、起動の前の読取り（{@link IntegrationHubEnablementBootResolver}。
 * container の DB を JDBC で読む）・その process の barrier の起動時の経路・起動の後の反映（{@link IntegrationHubBootDecisionRecorder}）を
 * 同じ順で組み立てて再現する（context の refresh は試験の context のまま）。
 *
 * <ul>
 *   <li>承認（{@code OPEN_PENDING_RESTART}）→ 再起動で {@code OPEN}（{@code RESTART_APPLIED}）、barrier は隔離から ON。</li>
 *   <li>定期の評価の後も {@code OPEN}、もう一度の再起動でも {@code OPEN} で隔離は走らない（{@code NF05_OUTBOUND_REOPEN} は1件のまま）。</li>
 *   <li>確定の閉鎖で barrier の行が {@code v1|OFF|0|<指紋>}、{@code AUTO_CLOSED} の後の再起動でも purge の property は開。開き直しの
 *       再起動は隔離を経る。</li>
 *   <li>{@code continueRecovery} と閉鎖の並行で deadlock しない（行の lock だけで直列になる）。</li>
 * </ul>
 * commit した行は class 専用の container DB に残る。DB は prod の初回導入と同じ状態にする: Flyway の {@code db/migration} の後に、
 * prod の Flyway の job が適用する {@code db/migration-prod/R__remove_v2_sample_data.sql} を流して V2 の sample（法人が未設定の
 * 顧客・要員・案件・契約）を消す（{@code FlywayProdFreshInstallMySqlTest} の配備決定-1）。sample を残すと NF05 の法人の条件
 * （{@code LEGAL_ENTITY_UNRESOLVED}）で開かない。法人の条件そのものは外さない。
 */
@SpringBootTest(properties = {
        "app.feature-enablement.source=database",
        "integration.hub.provider.mode=HTTPS",
        "integration.hub.public-api.public-id-key=" + Nf05EnablementFixtures.PUBLIC_ID_KEY,
        "integration.hub.topology.bound-tenant-id=" + Nf05EnablementFixtures.BOUND_TENANT,
        "integration.hub.external-transport.approval.allowed-destinations[0]=hooks.partner-a.example:443",
        "integration.hub.external-transport.truststore-path=" + IntegrationHubEnablementRestartMySqlTest.TRUSTSTORE,
        "integration.hub.external-transport.truststore-password=synthetic-truststore-password",
        "integration.hub.external-transport.truststore-type=PKCS12",
        "integration.hub.crypto.current-key-version=test-key-v1",
        "integration.hub.crypto.keys.test-key-v1=synthetic-test-only-key-material",
        "integration.hub.crypto.credential-hash-pepper=" + Nf05EnablementFixtures.PEPPER})
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class IntegrationHubEnablementRestartMySqlTest {

    static final String TRUSTSTORE = "target/nf05-restart-mysql-truststore.p12";

    static {
        try {
            Path file = Path.of(TRUSTSTORE);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(file, "nf05-restart-mysql-truststore".getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_nf05_restart")
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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FeatureEnablementCatalog catalog;
    @Autowired private IntegrationHubExternalApiProperties contextProperties;
    @Autowired private FeatureEnablementProperties featureProperties;
    @Autowired private FeatureEnablementSnapshotHolder holder;
    @Autowired private FeatureEnablementOverlay overlay;
    @Autowired private PropertyFeatureEnablementStateSource propertySource;
    @Autowired private FeatureEnablementEvaluationService evaluation;
    @Autowired private FeatureEnablementService service;
    @Autowired private com.ses.featureenablement.condition.FeatureConditionRegistry registry;
    @Autowired private com.ses.featureenablement.evaluation.FeatureEnablementEvaluationResults results;
    @Autowired private com.ses.mapper.FeatureEnablementRequestMapper requestMapper;
    @Autowired private com.ses.mapper.FeatureEnablementEventMapper eventMapper;
    @Autowired private com.ses.mapper.FeatureSignOffBundleMapper bundleMapper;
    @Autowired private com.ses.mapper.FeatureSignOffBundleMemberMapper memberMapper;
    @Autowired private com.ses.featureenablement.state.FeatureEnablementTenant tenant;
    @Autowired private ObjectProvider<com.ses.featureenablement.service.FeatureEnablementCloseWarningSource> closeWarningSources;
    @Autowired private IntegrationHubBootDecisionWriter writer;
    @Autowired private LegalEntityReadinessService readiness;
    @Autowired private ApiDeliveryMapper deliveries;
    @Autowired private SystemConfigMapper configs;
    @Autowired private AuditLogService audit;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void 承認から再起動でOPENになり定期の評価と次の再起動でもOPENで隔離が走らず閉鎖の後はpurgeを開き開き直しは隔離を経る()
            throws Exception {
        FeatureEnablementH2Fixture fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        insertOutboundSignOffs();
        reset();
        long reopenBefore = audits("NF05_OUTBOUND_REOPEN");
        long appliedBefore = events("RESTART_APPLIED");
        // 承認の前の OFF の起動（barrier が OFF と NF05_OUTBOUND_OFF を書く）
        Process off = restart();
        assertThat(off.values().get("app.feature-enablement.nf05-boot.outbound.result")).isEqualTo("CLOSED:state.NONE");
        assertThat(barrierRow()).startsWith("v1|OFF|0|");
        assertThat(audits("NF05_OUTBOUND_OFF")).isPositive();
        approvePendingRestart(fixture);

        Process first = restart();
        assertThat(first.values().get("app.feature-enablement.nf05-boot.outbound.result")).isEqualTo("OPENED");
        assertThat(state()).isEqualTo("OPEN");
        assertThat(events("RESTART_APPLIED")).isEqualTo(appliedBefore + 1);
        assertThat(barrierRow()).startsWith("v1|ON|0|");
        assertThat(first.barrier().isReady()).isTrue();
        assertThat(audits("NF05_OUTBOUND_REOPEN")).isEqualTo(reopenBefore + 1);

        evaluation.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        assertThat(state()).as("定期の評価は OFF の記録を要求しない").isEqualTo("OPEN");

        Process second = restart();
        assertThat(second.values().get("app.feature-enablement.nf05-boot.outbound.result")).isEqualTo("OPENED");
        assertThat(state()).isEqualTo("OPEN");
        assertThat(second.barrier().isReady()).as("ON→ON の再起動は隔離しない").isTrue();
        assertThat(audits("NF05_OUTBOUND_REOPEN")).isEqualTo(reopenBefore + 1);

        assertThat(service.autoClose(Feature.NF05_OUTBOUND, "TEST_DEFINITIVE_CLOSE", false)).isTrue();
        assertThat(barrierRow()).as("確定の閉鎖は行を OFF に書き直す").startsWith("v1|OFF|0|");
        holder.reload();
        assertThat(second.runtime().outboundClosure()).isEqualTo(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
        // review rb-fe-d P2-2: 確定の閉鎖の間、未送信の配信は barrier が隔離と同じ DLQ（STARTUP_REVALIDATION_REQUIRED）にし、保存期間の purge と bulk replay の対象になる（P2-N2）
        long unsent = insertPendingDelivery();
        second.barrier().continueRecovery();
        assertThat(jdbc.queryForObject("SELECT CONCAT(status, ':', last_error_code, ':', retention_class) FROM t_api_delivery "
                + "WHERE id = ?", String.class, unsent)).isEqualTo("DLQ:STARTUP_REVALIDATION_REQUIRED:FAILED_DLQ_PAYLOAD_90D");

        Process afterClose = restart();
        assertThat(afterClose.values().get("integration.hub.retention-purge.enabled"))
                .as("AUTO_CLOSED の後の再起動でも purge を作る").isEqualTo("true");
        assertThat(afterClose.values().get("integration.hub.external-transport.enabled")).isEqualTo("false");

        approvePendingRestart(fixture);
        Process reopened = restart();
        assertThat(reopened.values().get("app.feature-enablement.nf05-boot.outbound.result")).isEqualTo("OPENED");
        assertThat(audits("NF05_OUTBOUND_REOPEN")).as("開き直しの起動は隔離を経る").isEqualTo(reopenBefore + 2);
    }

    @Test
    void continueRecoveryと閉鎖の並行でdeadlockしない() throws Exception {
        FeatureEnablementH2Fixture fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        insertOutboundSignOffs();
        reset();
        restart();
        approvePendingRestart(fixture);
        Process open = restart();
        jdbc.update("UPDATE m_system_config SET config_value = ? WHERE config_key = ?",
                Nf05EnablementFixtures.barrier("QUARANTINING", Nf05EnablementFixtures.BOUND_TENANT).replace("|0|", "|999999|"),
                IntegrationHubOutboundStartupBarrier.CONFIG_KEY);
        IntegrationHubOutboundStartupBarrier recovering = barrier(open.properties(), open.runtime());
        recovering.start();

        CompletableFuture<Object> recovery = CompletableFuture.supplyAsync(() -> {
            for (int i = 0; i < 20; i++) {
                recovering.continueRecovery();
            }
            return "done";
        });
        CompletableFuture<Object> close = CompletableFuture.supplyAsync(
                () -> service.autoClose(Feature.NF05_OUTBOUND, "TEST_CONCURRENT_CLOSE", false));
        assertThat(recovery.handle((value, failure) -> failure == null ? value : failure).get(60, TimeUnit.SECONDS))
                .isNotInstanceOf(DeadlockLoserDataAccessException.class).isEqualTo("done");
        assertThat(close.handle((value, failure) -> failure == null ? value : failure).get(60, TimeUnit.SECONDS))
                .isEqualTo(Boolean.TRUE);
        assertThat(barrierRow()).startsWith("v1|OFF|0|");
        holder.reload();
        assertThat(recovering.isReady()).isFalse();
    }

    /**
     * FE-D02・FE-D05 の Demo: prod の profile 相当の構成で、outbound を申請・承認（{@code OPEN_PENDING_RESTART}）→ 再起動 1 回目で起動の
     * log に {@code outbound=OPENED}・{@code NF05_OUTBOUND=OPENED(APPLIED)}（履歴は {@code RESTART_APPLIED}）が出て画面の一覧で
     * {@code OPEN}（実効値も開）になり、もう一度再起動しても {@code OPEN} のまま保たれること、さらに {@code bound-tenant-id} を変えた
     * 起動の後に outbound を申請すると条件の不成立（409・{@code nf05.outbound.approvalDigest.mismatch}、以前の tenant の OFF 行のまま
     * なら {@code barrier.tenantFingerprint.mismatch} も含む）になることを確かめる。
     */
    @Test
    void prodの構成でoutboundを承認し再起動2回のlogと画面OPENおよびbound_tenant_id変更後の申請拒否を確かめる() {
        FeatureEnablementH2Fixture fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        insertOutboundSignOffs();
        reset();
        long appliedBefore = events("RESTART_APPLIED");
        Process off = restart();
        assertThat(off.values().get("app.feature-enablement.nf05-boot.outbound.result")).isEqualTo("CLOSED:state.NONE");

        com.ses.entity.FeatureEnablementRequest openRequest = service.requestOpen(Feature.NF05_OUTBOUND, null, requester);
        assertThat(service.approve(openRequest.getId(), 0, null, approver).approved()).isTrue();
        holder.reload();
        assertThat(unitView(off, requester).state()).isEqualTo("OPEN_PENDING_RESTART");
        assertThat(unitView(off, requester).effectiveOpen()).as("再起動の前は実効値は閉").isFalse();

        Process first = restart(Nf05EnablementFixtures.BOUND_TENANT, true);
        assertThat(first.bootLogs()).anyMatch(line -> line.contains("[機能の開放] NF05 の起動時の判定:")
                && line.contains("outbound=OPENED"));
        assertThat(first.recordedSummary()).contains("NF05_OUTBOUND=OPENED(APPLIED)");
        assertThat(events("RESTART_APPLIED")).isEqualTo(appliedBefore + 1);
        assertThat(unitView(first, requester).state()).isEqualTo("OPEN");
        assertThat(unitView(first, requester).effectiveOpen()).isTrue();

        Process second = restart(Nf05EnablementFixtures.BOUND_TENANT, true);
        assertThat(second.bootLogs()).anyMatch(line -> line.contains("[機能の開放] NF05 の起動時の判定:")
                && line.contains("outbound=OPENED"));
        assertThat(second.recordedSummary()).contains("NF05_OUTBOUND=OPENED");
        assertThat(unitView(second, requester).state()).isEqualTo("OPEN");
        assertThat(unitView(second, requester).effectiveOpen()).isTrue();

        service.close(Feature.NF05_OUTBOUND, "MANUAL_VERIFICATION", requester);
        holder.reload();
        assertThat(barrierRow()).startsWith("v1|OFF|0|");

        String originalBoundTenant = contextProperties.getTopology().getBoundTenantId();
        try {
            contextProperties.getTopology().setBoundTenantId("tenant-changed");
            // 1. 以前の tenant の barrier 行が残っている間は指紋の不一致と承認 digest の不一致の両方で拒否される
            com.ses.common.exception.BusinessException beforeBootRejected = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> service.requestOpen(Feature.NF05_OUTBOUND, null, requester),
                    com.ses.common.exception.BusinessException.class);
            assertThat(beforeBootRejected).isNotNull();
            assertThat(beforeBootRejected.getCode()).isEqualTo(409);
            assertThat(String.valueOf(java.util.List.of(beforeBootRejected.getArgs())))
                    .contains("barrier.tenantFingerprint.mismatch")
                    .contains("nf05.outbound.approvalDigest.mismatch");

            // 2. bound-tenant-id を変えて再起動した後（barrier が新 tenant の OFF を書いた後）も承認 digest の不一致で拒否される
            Process mismatchedBoot = restart("tenant-changed", true);
            assertThat(mismatchedBoot.values().get("app.feature-enablement.nf05-boot.outbound.result"))
                    .isEqualTo("CLOSED:state.MANUALLY_CLOSED");

            com.ses.common.exception.BusinessException afterBootRejected = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> service.requestOpen(Feature.NF05_OUTBOUND, null, requester),
                    com.ses.common.exception.BusinessException.class);
            assertThat(afterBootRejected).isNotNull();
            assertThat(afterBootRejected.getCode()).isEqualTo(409);
            assertThat(afterBootRejected.getMessageKey()).isEqualTo("error.featureEnablement.conditionsUnsatisfied");
            assertThat(String.valueOf(java.util.List.of(afterBootRejected.getArgs())))
                    .contains("nf05.outbound.approvalDigest.mismatch");
        } finally {
            contextProperties.getTopology().setBoundTenantId(originalBoundTenant);
        }
    }

    private com.ses.featureenablement.service.FeatureEnablementQueryService.UnitSummary unitView(
            Process process, FeatureEnablementActor actor) {
        com.ses.featureenablement.service.FeatureEnablementQueryService processQuery =
                new com.ses.featureenablement.service.FeatureEnablementQueryService(catalog, holder, process.switches(),
                        process.stateSource(), registry, results, requestMapper, eventMapper, bundleMapper, memberMapper,
                        tenant, Clock.systemUTC(), closeWarningSources);
        return processQuery.units(actor).stream()
                .filter(unit -> Feature.NF05_OUTBOUND.name().equals(unit.key()))
                .findFirst()
                .orElseThrow();
    }

    /** 再現した1回の起動。 */
    private record Process(Map<String, Object> values, IntegrationHubExternalApiProperties properties,
                           DatabaseFeatureEnablementStateSource stateSource, FeatureSwitches switches,
                           IntegrationHubRuntimeSwitch runtime, IntegrationHubOutboundStartupBarrier barrier,
                           java.util.List<String> bootLogs, String recordedSummary) {
    }

    /** 起動の前の読取り → barrier の起動時の経路 → 起動の後の反映 → ApplicationRunner の順に再現する。 */
    private Process restart() {
        return restart(Nf05EnablementFixtures.BOUND_TENANT, false);
    }

    private Process restart(String boundTenantId, boolean prodProfile) {
        MockEnvironment environment = Nf05EnablementFixtures.environment(Path.of(TRUSTSTORE));
        if (prodProfile) {
            environment.setActiveProfiles("prod");
        }
        environment.setProperty("app.feature-enablement.source", "database");
        environment.setProperty("spring.datasource.url", MYSQL.getJdbcUrl());
        environment.setProperty("spring.datasource.username", MYSQL.getUsername());
        environment.setProperty("spring.datasource.password", MYSQL.getPassword());
        environment.setProperty("integration.hub.provider.mode", "HTTPS");
        environment.setProperty("integration.hub.topology.bound-tenant-id", boundTenantId);
        environment.setProperty("integration.hub.external-transport.approval.allowed-destinations[0]",
                "hooks.partner-a.example:443");
        environment.setProperty("integration.hub.external-transport.truststore-type", "PKCS12");
        java.util.List<String> bootLogs = new java.util.concurrent.CopyOnWriteArrayList<>();
        Log logCollector = (Log) java.lang.reflect.Proxy.newProxyInstance(
                Log.class.getClassLoader(),
                new Class<?>[]{Log.class},
                (proxy, method, args) -> {
                    if (args != null && args.length > 0 && ("info".equals(method.getName()) || "warn".equals(method.getName()))) {
                        bootLogs.add(String.valueOf(args[0]));
                    }
                    return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
                });
        IntegrationHubEnablementBootResolver resolver = new IntegrationHubEnablementBootResolver(logCollector,
                IntegrationHubEnablementBootResolver::connect);
        resolver.postProcessEnvironment(environment, null);
        Map<String, Object> values = ((MapPropertySource) java.util.Objects.requireNonNull(
                environment.getPropertySources().get(IntegrationHubEnablementBootResolver.PROPERTY_SOURCE))).getSource();
        IntegrationHubExternalApiProperties properties = Binder.get(environment)
                .bind("integration.hub", Bindable.of(IntegrationHubExternalApiProperties.class)).get();
        DatabaseFeatureEnablementStateSource stateSource = new DatabaseFeatureEnablementStateSource(holder, overlay,
                featureProperties, Clock.systemUTC());
        FeatureSwitches switches = new FeatureSwitches(stateSource);
        IntegrationHubBootConditions bootConditions = new IntegrationHubBootConditions();
        switches.setRuntimeConditions(java.util.List.of(
                bootConditions.integrationHubInboundBootCondition(properties, environment),
                bootConditions.integrationHubOutboundBootCondition(properties, environment)));
        IntegrationHubRuntimeSwitch runtime = new IntegrationHubRuntimeSwitch(properties, provider(switches), provider(holder),
                provider(overlay), provider(featureProperties));
        IntegrationHubOutboundStartupBarrier barrier = barrier(properties, runtime);
        barrier.start();
        @SuppressWarnings("unchecked")
        ObjectProvider<LegalEntityReadinessService> legalEntity = mock(ObjectProvider.class);
        when(legalEntity.getIfAvailable()).thenReturn(readiness);
        java.util.concurrent.atomic.AtomicReference<String> recordedSummary = new java.util.concurrent.atomic.AtomicReference<>("");
        IntegrationHubBootDecisionRecorder recorder = new IntegrationHubBootDecisionRecorder(environment, featureProperties,
                writer, runtime, properties, legalEntity, overlay, provider(holder)) {
            @Override
            public String record() {
                String summary = super.record();
                recordedSummary.set(summary);
                return summary;
            }
        };
        recorder.start();
        holder.reload();
        barrier.run(new DefaultApplicationArguments());
        return new Process(values, properties, stateSource, switches, runtime, barrier,
                java.util.List.copyOf(bootLogs), recordedSummary.get());
    }

    private IntegrationHubOutboundStartupBarrier barrier(IntegrationHubExternalApiProperties properties,
                                                         IntegrationHubRuntimeSwitch runtime) {
        IntegrationHubOutboundStartupBarrier barrier = new IntegrationHubOutboundStartupBarrier(deliveries, audit,
                transactions, Clock.systemUTC(), configs, properties, provider((MeterRegistry) null));
        barrier.setRuntimeSwitch(runtime);
        return barrier;
    }

    /** 承認した申請と {@code OPEN_PENDING_RESTART} の行（承認の流れそのものは FeatureEnablementServiceH2Test が確かめる）。 */
    private void approvePendingRestart(FeatureEnablementH2Fixture fixture) {
        jdbc.update("DELETE FROM t_feature_enablement_state WHERE tenant_id = 'default' AND feature_key = 'NF05_OUTBOUND'");
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        fixture.openDirectly(Feature.NF05_OUTBOUND, requester, approver);
        jdbc.update("UPDATE t_feature_enablement_state SET state = 'OPEN_PENDING_RESTART' WHERE tenant_id = 'default' "
                + "AND feature_key = 'NF05_OUTBOUND'");
        holder.reload();
    }

    /** NF05 outbound が要する確認済みの署名（DG-05-OUT は試験の context の配備の設定の digest）。 */
    private void insertOutboundSignOffs() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_sign_off WHERE kind_code = 'NF05_DG05_OUT' "
                + "AND status = 'CONFIRMED'", Long.class) > 0) {
            return;
        }
        byte[] truststore = IntegrationHubEnablementDecision.readTruststore(TRUSTSTORE);
        for (SignOffRequirement requirement : catalog.unit(Feature.NF05_OUTBOUND).signOffs()) {
            SignOffKind kind = catalog.kind(requirement.kindCode()).orElseThrow();
            String reference = "REF-" + kind.code();
            String structured = null;
            if ("NF05_DG05_OUT".equals(kind.code())) {
                reference = Nf05EnablementFixtures.OUTBOUND_DECISION;
                structured = FeatureSignOffRegistrar.structuredJson(Map.of("approvalDigest",
                        IntegrationHubEnablementDecision.currentDigest(Feature.NF05_OUTBOUND, contextProperties,
                                reference, truststore)));
            }
            jdbc.update("INSERT INTO t_feature_sign_off (tenant_id, kind_code, reference, signer_role, signed_on, expires_on, "
                            + "structured_values, kind_class, content_sha256, size_bytes, stored_name, status, registered_by, "
                            + "registered_at, confirmed_by, confirmed_at) VALUES ('default', ?, ?, ?, ?, ?, ?, ?, ?, 10, ?, "
                            + "'CONFIRMED', 990001, CURRENT_TIMESTAMP(6), 990002, CURRENT_TIMESTAMP(6))", kind.code(), reference,
                    kind.signerRoles().get(0), LocalDate.of(2026, 11, 1), LocalDate.of(2027, 10, 1), structured,
                    kind.kindClass().name(), "a".repeat(64), "nf05-restart-" + kind.code() + ".pdf");
        }
        holder.reload();
    }

    /** 他の試験の行を消し、承認の前の状態（行なし・barrier は OFF）に戻す（class 専用の DB。試験の順に依らない）。 */
    private void reset() {
        removeV2SampleLikeProductionJob();
        jdbc.update("DELETE FROM t_feature_enablement_state WHERE tenant_id = 'default' AND feature_key = 'NF05_OUTBOUND'");
        jdbc.update("DELETE FROM m_system_config WHERE config_key = ?", IntegrationHubOutboundStartupBarrier.CONFIG_KEY);
        overlay.open(Feature.NF05_OUTBOUND, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        holder.reload();
    }

    /**
     * prod の Flyway の job と同じ script で V2 の sample を消す（prod の初回導入の状態。script は初回導入の直後の時だけ消し、2回目
     * からは何もしない）。1本の接続で流す（script は接続の変数で判定を持ち回る）。消した後に法人の未解決が無いことを前提として確かめる。
     */
    private void removeV2SampleLikeProductionJob() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.support.EncodedResource(new org.springframework.core.io.ClassPathResource(
                            PROD_SAMPLE_REMOVAL_SCRIPT), StandardCharsets.UTF_8));
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m_customer", Long.class))
                .as("prod の初回導入と同じく V2 の sample が無い").isZero();
        assertThat(readiness.isReady()).as("prod の初回導入の状態では法人の未解決の行が無い").isTrue();
    }

    static final String PROD_SAMPLE_REMOVAL_SCRIPT = "db/migration-prod/R__remove_v2_sample_data.sql";

    /** 未送信（PENDING）の配信を1行入れる（client と購読も作る。行の ID を返す）。 */
    private long insertPendingDelivery() {
        String client = "nf05-restart-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO m_api_client(client_id,owner_ref,tenant_id,legal_entity_id,data_scope_json,allowed_cidrs) "
                + "VALUES(?,'PROJECT_OWNER','default',9,'{}','')", client);
        jdbc.update("INSERT INTO m_webhook_subscription(client_id,direction,event_type,endpoint_url,key_id,"
                + "encrypted_signing_secret,crypto_key_version,data_scope_json) VALUES(?,'OUTBOUND','resource.changed',"
                + "'https://web.example.com:443/hook','key-1','synthetic','v1','{}')", client);
        Long subscription = jdbc.queryForObject("SELECT id FROM m_webhook_subscription WHERE client_id=?", Long.class,
                client);
        String event = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO t_api_delivery(event_id,subscription_id,client_id,tenant_id,scope_code,event_type,"
                        + "schema_version,provider_idempotency_key,external_dto_snapshot,payload_hash,primary_resource_type,"
                        + "primary_resource_id,scope_digest,created_at) VALUES(?,?,?,'default','integration.project.read',"
                        + "'resource.changed','v1',?,'{}',?,'project',1,?,CURRENT_TIMESTAMP)", event, subscription, client,
                com.ses.service.integrationhub.IntegrationHubDigest.sha256Hex(event),
                com.ses.service.integrationhub.IntegrationHubDigest.sha256Hex("{}"), "a".repeat(64));
        return jdbc.queryForObject("SELECT id FROM t_api_delivery WHERE event_id=?", Long.class, event);
    }

    private long events(String eventType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_enablement_event WHERE subject_key = 'NF05_OUTBOUND' "
                + "AND event_type = ?", Long.class, eventType);
    }

    private String state() {
        return jdbc.queryForObject("SELECT state FROM t_feature_enablement_state WHERE tenant_id = 'default' "
                + "AND feature_key = 'NF05_OUTBOUND'", String.class);
    }

    private String barrierRow() {
        return jdbc.queryForObject("SELECT config_value FROM m_system_config WHERE config_key = ?", String.class,
                IntegrationHubOutboundStartupBarrier.CONFIG_KEY);
    }

    private long audits(String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE reference_type = 'NF05' AND application_code = ?",
                Long.class, code);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
