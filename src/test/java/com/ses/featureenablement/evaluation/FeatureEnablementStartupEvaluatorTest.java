package com.ses.featureenablement.evaluation;

import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureConditionRegistry;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.service.FeatureEnablementNotifier;
import com.ses.featureenablement.service.FeatureEnablementService;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-A07（design §7.1）: 起動時の評価。成り立たない開放単位の自動閉鎖（起動の行為者）、総スイッチが閉なら全てを
 * {@code MASTER_SWITCH_CLOSED}、DB に書けなければ自動閉鎖の書込み待ちで閉じたまま。起動の log の要約は code だけ。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureEnablementStartupEvaluatorTest {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);

    @Autowired
    private FeatureEnablementStartupEvaluator startupEvaluator;
    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FeatureEnablementEvaluationWriter writer;
    @Autowired
    private FeatureEnablementNotifier notifier;
    @Autowired
    private FeatureConditionRegistry registry;
    @Autowired
    private FeatureEnablementProperties properties;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureEnablementOverlay overlay;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;

    private FeatureEnablementH2Fixture fixture;
    private FeatureEnablementActor requester;
    private FeatureEnablementActor approver;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        requester = fixture.admin();
        approver = fixture.admin();
    }

    @AfterTransaction
    void restoreSharedState() {
        properties.setAllowed(true);
        for (Feature feature : Feature.values()) {
            overlay.open(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        }
        overlay.clearAll(FeatureEnablementOverlay.EVALUATION_UNVERIFIABLE);
        holder.reload();
    }

    @Test
    void 起動時の評価で成り立たない開放単位を起動の行為者で自動閉鎖し要約はcodeだけ() {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        open(Feature.NF04);
        open(Feature.NF03);
        jdbc.update("UPDATE t_feature_sign_off SET status = 'REVOKED', ended_by = ?, ended_at = TIMESTAMP "
                + "'2026-11-23 09:00:00', end_reason = 'TEST' WHERE tenant_id = 'default' AND kind_code = 'NF04_PRIVACY' "
                + "AND status = 'CONFIRMED'", approver.userId());

        String summary = startupEvaluator.evaluateAtStartup();

        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.state(Feature.NF03)).isEqualTo("OPEN");
        assertThat(summary).contains("NF04=AUTO_CLOSED(SIGN_OFF_MISSING)").contains("NF03=OPEN")
                .doesNotContain(requester.username());
        assertThat(jdbc.queryForObject("SELECT uri FROM t_audit_log WHERE application_code = 'FEATURE_AUTO_CLOSED' "
                + "ORDER BY id DESC LIMIT 1", String.class)).isEqualTo("system:feature-enablement/startup");
    }

    @Test
    void 総スイッチが閉じていれば開いている全てをMASTER_SWITCH_CLOSEDで閉じ未決の申請を取り消す() {
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF03_CERTIFICATION_PII, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        open(Feature.NF03);
        open(Feature.NF03_CERTIFICATION_PII);
        FeatureEnablementRequest pending = service.requestOpen(Feature.NF04, null, requester);
        properties.setAllowed(false);

        startupEvaluator.evaluateAtStartup();

        assertThat(fixture.state(Feature.NF03)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF03)).isEqualTo("MASTER_SWITCH_CLOSED");
        assertThat(fixture.state(Feature.NF03_CERTIFICATION_PII)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.requestStatus(pending.getId())).isEqualTo("CANCELLED");
        assertThat(fixture.countAudit("FEATURE_MASTER_CLOSED")).as("閉じた回だけ監査").isEqualTo(1);
    }

    @Test
    void DBに書けなければ自動閉鎖の書込み待ちで閉じたままにする() {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        open(Feature.NF04);
        jdbc.update("UPDATE t_feature_sign_off SET status = 'REVOKED', ended_by = ?, ended_at = TIMESTAMP "
                + "'2026-11-23 09:00:00', end_reason = 'TEST' WHERE tenant_id = 'default' AND kind_code = 'NF04_SRE' "
                + "AND status = 'CONFIRMED'", approver.userId());
        FeatureEnablementService unwritable = mock(FeatureEnablementService.class);
        when(unwritable.autoClose(any(), anyString(), anyBoolean())).thenThrow(new IllegalStateException("静止中"));
        FeatureEnablementOverlay localOverlay = new FeatureEnablementOverlay();
        FeatureEnablementEvaluationService local = local(unwritable, localOverlay);

        Map<Feature, String> result = local.evaluateOpenUnits(FeatureEvaluationContext.Purpose.STARTUP, true);

        assertThat(fixture.state(Feature.NF04)).as("DB は開いたまま").isEqualTo("OPEN");
        assertThat(localOverlay.has(Feature.NF04, FeatureEnablementOverlay.AUTO_CLOSE_PENDING)).isTrue();
        assertThat(result.get(Feature.NF04)).isEqualTo("AUTO_CLOSE_PENDING(signoff.NF04_SRE.missing)");
    }

    @Test
    void 起動時の評価はweb_serverより前のphaseで動く() {
        assertThat(startupEvaluator.getPhase()).isLessThan(Integer.MAX_VALUE - 4096);
        assertThat(startupEvaluator.isRunning()).isTrue();
    }

    // ---------------------------------------------------------------- helper

    private void open(Feature feature) {
        FeatureEnablementRequest request = service.requestOpen(feature, null, requester);
        assertThat(service.approve(request.getId(), 0, null, approver).approved()).isTrue();
        FeatureEnablementH2Fixture.runAfterCommit();
        holder.reload();
    }

    @SuppressWarnings("unchecked")
    private FeatureEnablementEvaluationService local(FeatureEnablementService closer, FeatureEnablementOverlay localOverlay) {
        ObjectProvider<SystemConfigService> config = mock(ObjectProvider.class);
        ObjectProvider<FeatureEnablementDayStartHook> hooks = mock(ObjectProvider.class);
        when(hooks.orderedStream()).thenAnswer(invocation -> Stream.empty());
        return new FeatureEnablementEvaluationService(properties, holder, registry, catalog, localOverlay, closer, writer,
                notifier, config, hooks, FeatureEnablementTenant.fixed(FeatureEnablementH2Fixture.TENANT), clock);
    }
}
