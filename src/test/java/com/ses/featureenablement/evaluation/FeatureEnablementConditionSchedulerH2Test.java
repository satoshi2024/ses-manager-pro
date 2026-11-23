package com.ses.featureenablement.evaluation;

import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionEvaluator;
import com.ses.featureenablement.condition.FeatureConditionRegistry;
import com.ses.featureenablement.condition.FeatureEvaluation;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-A07（design §7.2・§7.4、FE-RQ-005）: 定期の評価と日の始まりの処理を H2 で確かめる。固有の評価器が要る場面は、context の共通の
 * 評価器に試験の評価器を足した registry で評価の部品を組み立てる（overlay も試験ごとに新しくする）。試験の transaction の中で
 * 入れた行だけを読み、終わった後に共有の snapshot を読み直す。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureEnablementConditionSchedulerH2Test {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);

    @Autowired
    private FeatureEnablementEvaluationService evaluation;
    @Autowired
    private FeatureEnablementConditionScheduler scheduler;
    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FeatureEnablementEvaluationWriter writer;
    @Autowired
    private FeatureEnablementNotifier notifier;
    @Autowired
    private FeatureEnablementProperties properties;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureEnablementOverlay overlay;
    @Autowired
    private List<FeatureConditionEvaluator> evaluators;
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

    /** rollback の後に共有の snapshot と overlay を元に戻す（他の試験へ持ち越さない）。 */
    @AfterTransaction
    void restoreSharedState() {
        for (Feature feature : Feature.values()) {
            overlay.open(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        }
        overlay.clearAll(FeatureEnablementOverlay.EVALUATION_UNVERIFIABLE);
        clock.reset();
        holder.reload();
    }

    @Test
    void 継続の条件が成り立たなければ自動閉鎖し他の開放単位は変えず通知する() {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        open(Feature.NF04);
        open(Feature.NF03);
        revokeDirectly("NF04_PRIVACY");

        evaluation.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);

        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF04)).as("要る署名の欠落は世代の lock の直後に先に閉じる")
                .isEqualTo("SIGN_OFF_MISSING");
        assertThat(fixture.state(Feature.NF03)).isEqualTo("OPEN");
        assertThat(fixture.countAudit("FEATURE_AUTO_CLOSED")).isEqualTo(1);
        assertThat(overlay.has(Feature.NF04, FeatureEnablementOverlay.AUTO_CLOSE_PENDING)).as("書けたら外す").isFalse();
        FeatureEnablementH2Fixture.runAfterCommit();
        assertThat(FeatureEnablementH2Fixture.notices()).filteredOn(notice -> notice.eventKey().equals("autoClosed")
                && notice.recipients().contains(requester.userId()) && "NF04".equals(notice.args()[0])).isNotEmpty();
    }

    @Test
    void 評価できない類のcodeはunverifiable_sinceだけを書き評価の間隔の3倍で自動閉鎖する() {
        fixture.confirmedSignOffs(Feature.NF10, SIGNED, EXPIRES);
        open(Feature.NF10);
        AtomicReference<FeatureConditionCheck> result = new AtomicReference<>(
                FeatureConditionCheck.unverifiable("journal.unreachable", FeatureConditionCheck.Category.AUTO));
        FeatureEnablementOverlay localOverlay = new FeatureEnablementOverlay();
        FeatureEnablementEvaluationService local = local(evaluator(Feature.NF10, result), service, localOverlay);

        local.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);

        assertThat(fixture.state(Feature.NF10)).as("評価できないだけでは閉じない").isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT unverifiable_reason FROM t_feature_enablement_state WHERE tenant_id = "
                + "'default' AND feature_key = 'NF10'", String.class)).isEqualTo("journal.unreachable");
        assertThat(holder.get().unit(Feature.NF10).unverifiableSince()).as("全ての instance が snapshot で閉じる").isNotNull();
        assertThat(fixture.countEvents("FEATURE", "EVALUATION_UNVERIFIABLE")).isEqualTo(1);

        clock.advance(Duration.ofMinutes(14));
        local.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        assertThat(fixture.state(Feature.NF10)).isEqualTo("OPEN");
        assertThat(fixture.countEvents("FEATURE", "EVALUATION_UNVERIFIABLE")).as("始まりは書き換えない").isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        local.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        assertThat(fixture.state(Feature.NF10)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF10)).isEqualTo("EVALUATION_UNVERIFIABLE");
        assertThat(jdbc.queryForObject("SELECT unverifiable_since FROM t_feature_enablement_state WHERE tenant_id = "
                + "'default' AND feature_key = 'NF10'", java.sql.Timestamp.class)).isNull();
    }

    /** review rb-fe-a1 P3-9: DB に書けなかった間の memory の始まりがあれば、書けた時はその時刻を始まりにする（自動閉鎖を遅らせない）。 */
    @Test
    void DBに書けなかった間の始まりがあればunverifiable_sinceはその時刻で監査も残す() {
        fixture.confirmedSignOffs(Feature.NF10, SIGNED, EXPIRES);
        open(Feature.NF10);
        java.time.LocalDateTime earlier = java.time.LocalDateTime.of(2026, 11, 23, 9, 50);

        writer.markUnverifiable(Feature.NF10, "journal.unreachable", false, earlier);

        assertThat(jdbc.queryForObject("SELECT unverifiable_since FROM t_feature_enablement_state WHERE tenant_id = "
                + "'default' AND feature_key = 'NF10'", java.time.LocalDateTime.class)).isEqualTo(earlier);
        assertThat(fixture.countAudit("FEATURE_EVALUATION_UNVERIFIABLE")).isEqualTo(1);
        writer.clearUnverifiable(Feature.NF10, false);
        assertThat(fixture.countAudit("FEATURE_EVALUATION_RECOVERED")).isEqualTo(1);
    }

    @Test
    void 評価できない状態は回復で消える() {
        fixture.confirmedSignOffs(Feature.NF10, SIGNED, EXPIRES);
        open(Feature.NF10);
        AtomicReference<FeatureConditionCheck> result = new AtomicReference<>(
                FeatureConditionCheck.unverifiable("journal.timeout", FeatureConditionCheck.Category.AUTO));
        FeatureEnablementEvaluationService local = local(evaluator(Feature.NF10, result), service,
                new FeatureEnablementOverlay());
        local.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        assertThat(holder.get().unit(Feature.NF10).unverifiableSince()).isNotNull();

        result.set(FeatureConditionCheck.satisfied("journal.reachable", FeatureConditionCheck.Category.AUTO, null));
        clock.advance(Duration.ofMinutes(5));
        local.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);

        assertThat(holder.get().unit(Feature.NF10).unverifiableSince()).isNull();
        assertThat(fixture.state(Feature.NF10)).isEqualTo("OPEN");
        assertThat(fixture.countEvents("FEATURE", "EVALUATION_RECOVERED")).isEqualTo(1);
    }

    @Test
    void 自動閉鎖の書込みに失敗した後に条件が戻っても閉じたままで次の周期で書かれる() {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        open(Feature.NF04);
        long privacy = fixture.confirmedId("NF04_PRIVACY");
        revokeDirectly("NF04_PRIVACY");
        FeatureEnablementService failing = mock(FeatureEnablementService.class);
        when(failing.autoClose(eq(Feature.NF04), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("書けない"))
                .thenAnswer(invocation -> service.autoClose(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2)));
        FeatureEnablementOverlay localOverlay = new FeatureEnablementOverlay();
        FeatureEnablementEvaluationService local = local(null, failing, localOverlay);

        local.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        assertThat(localOverlay.has(Feature.NF04, FeatureEnablementOverlay.AUTO_CLOSE_PENDING)).isTrue();
        assertThat(localOverlay.pendingAutoCloseReason(Feature.NF04)).contains("signoff.NF04_PRIVACY.missing");
        assertThat(fixture.state(Feature.NF04)).isEqualTo("OPEN");

        // 条件が戻っても書込み待ちは外れず、次の周期で条件を見ずに書く
        jdbc.update("UPDATE t_feature_sign_off SET status = 'CONFIRMED', ended_by = NULL, ended_at = NULL, end_reason = NULL "
                + "WHERE id = ?", privacy);
        local.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);

        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF04)).isEqualTo("signoff.NF04_PRIVACY.missing");
        assertThat(localOverlay.has(Feature.NF04, FeatureEnablementOverlay.AUTO_CLOSE_PENDING)).isFalse();
    }

    @Test
    void 失効日の0時の後の最初の評価で閉じ日の始まりに署名をEXPIREDにして期限切れの申請を終える() {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        jdbc.update("UPDATE t_feature_sign_off SET expires_on = ? WHERE tenant_id = 'default' AND kind_code = 'NF04_SRE' "
                + "AND status = 'CONFIRMED'", LocalDate.of(2026, 11, 24));
        long sre = fixture.confirmedId("NF04_SRE");
        open(Feature.NF04);
        FeatureEnablementRequest stale = service.requestOpen(Feature.NF03, null, requester);

        clock.set(Instant.parse("2026-11-23T14:59:59Z"));
        evaluation.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        assertThat(fixture.state(Feature.NF04)).as("失効日の前日").isEqualTo("OPEN");

        clock.set(Instant.parse("2026-11-30T15:00:05Z"));
        evaluation.evaluateAtBusinessDayStart();

        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF04)).isEqualTo("SIGN_OFF_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM t_feature_sign_off WHERE id = ?", String.class, sre))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT purge_after FROM t_feature_sign_off WHERE id = ?", LocalDate.class, sre))
                .as("失効はその失効日の3年後").isEqualTo(LocalDate.of(2029, 11, 24));
        assertThat(fixture.requestStatus(stale.getId())).isEqualTo("EXPIRED");
    }

    @Test
    void 失効の接近を日の始まりに管理者へ通知する() {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        jdbc.update("UPDATE t_feature_sign_off SET expires_on = ? WHERE tenant_id = 'default' AND kind_code = 'NF04_SRE' "
                + "AND status = 'CONFIRMED'", LocalDate.of(2026, 12, 3));
        open(Feature.NF04);

        evaluation.evaluateAtBusinessDayStart();
        FeatureEnablementH2Fixture.runAfterCommit();

        assertThat(fixture.state(Feature.NF04)).isEqualTo("OPEN");
        assertThat(FeatureEnablementH2Fixture.notices()).filteredOn(notice -> notice.eventKey().equals("expiryWarning")
                && notice.recipients().contains(approver.userId())).singleElement()
                .satisfies(notice -> assertThat(notice.args()).contains("NF04", "NF04_SRE"));
    }

    @Test
    void 開放の時だけの条件が外れても閉じず監査と通知だけを出す() {
        fixture.confirmedSignOffs(Feature.NF02, SIGNED, EXPIRES);
        fixture.openDirectly(Feature.NF02, requester, approver);
        holder.reload();
        AtomicReference<FeatureConditionCheck> calendar = new AtomicReference<>(FeatureConditionCheck.unsatisfied(
                FeatureEnablementCatalog.CODE_NF02_CALENDAR, FeatureConditionCheck.Category.AUTO, false));
        FeatureEnablementEvaluationService local = local(evaluator(Feature.NF02, calendar), service,
                new FeatureEnablementOverlay());

        local.evaluateAtBusinessDayStart();

        assertThat(fixture.state(Feature.NF02)).as("OPEN_ONLY は閉鎖の根拠にしない").isEqualTo("OPEN");
        assertThat(fixture.countAudit("FEATURE_OPEN_CONDITION_LOST")).isEqualTo(1);

        calendar.set(FeatureConditionCheck.satisfied(FeatureEnablementCatalog.CODE_NF02_CALENDAR,
                FeatureConditionCheck.Category.AUTO, LocalDate.of(2026, 12, 10)));
        clock.advance(Duration.ofDays(1));
        local.evaluateAtBusinessDayStart();

        assertThat(fixture.state(Feature.NF02)).isEqualTo("OPEN");
        assertThat(fixture.countAudit("FEATURE_OPEN_CONDITION_EXPIRING")).isEqualTo(1);
        FeatureEnablementH2Fixture.runAfterCommit();
        assertThat(FeatureEnablementH2Fixture.notices()).filteredOn(notice -> notice.recipients().contains(requester.userId())
                && "NF02".equals(notice.args()[0])).extracting(FeatureEnablementNotifier.Notice::eventKey)
                .containsExactlyInAnyOrder("openConditionLost", "openConditionExpiring");
    }

    @Test
    void 開放の遷移の前提は定期の評価で判定しない() {
        AtomicReference<FeatureConditionCheck> barrier = new AtomicReference<>(FeatureConditionCheck.unsatisfied(
                FeatureEnablementCatalog.CODE_NF05_BARRIER_OFF, FeatureConditionCheck.Category.AUTO, false));
        FeatureConditionRegistry registry = registry(evaluator(Feature.NF05_OUTBOUND, barrier));
        FeatureEvaluationContext.Purpose[] purposes = {FeatureEvaluationContext.Purpose.PERIODIC,
                FeatureEvaluationContext.Purpose.STARTUP, FeatureEvaluationContext.Purpose.CHANGE};
        for (FeatureEvaluationContext.Purpose purpose : purposes) {
            FeatureEvaluation result = registry.evaluate(Feature.NF05_OUTBOUND, new FeatureEvaluationContext("default",
                    LocalDate.of(2026, 11, 23), clock, holder.get(), purpose, Set.of(), false));
            assertThat(result.checks()).as(purpose.name())
                    .noneMatch(check -> check.code().equals(FeatureEnablementCatalog.CODE_NF05_BARRIER_OFF));
        }
        FeatureEvaluation approval = registry.evaluate(Feature.NF05_OUTBOUND, new FeatureEvaluationContext("default",
                LocalDate.of(2026, 11, 23), clock, holder.get(), FeatureEvaluationContext.Purpose.APPROVAL, Set.of(),
                false));
        assertThat(approval.checks()).anyMatch(check -> check.code().equals(FeatureEnablementCatalog.CODE_NF05_BARRIER_OFF));
    }

    @Test
    void 定期の入口はvoidでSchedulerLockを持ち日の始まりは日本時間のcron() throws Exception {
        java.lang.reflect.Method all = FeatureEnablementConditionScheduler.class.getMethod("evaluateAll");
        java.lang.reflect.Method dayStart = FeatureEnablementConditionScheduler.class.getMethod("evaluateAtBusinessDayStart");
        assertThat(all.getReturnType()).isEqualTo(void.class);
        assertThat(dayStart.getReturnType()).isEqualTo(void.class);
        assertThat(all.getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class).name())
                .isEqualTo("featureEnablementEvaluation");
        assertThat(dayStart.getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class).name())
                .isEqualTo("featureEnablementDayStart");
        org.springframework.scheduling.annotation.Scheduled cron =
                dayStart.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(cron.cron()).isEqualTo("5 0 0 * * *");
        assertThat(cron.zone()).isEqualTo("Asia/Tokyo");
        assertThat(all.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class).fixedDelayString())
                .isEqualTo("${app.feature-enablement.evaluation-interval:PT5M}");
        assertThat(scheduler).isNotNull();
    }

    // ---------------------------------------------------------------- helper

    private void open(Feature feature) {
        FeatureEnablementRequest request = service.requestOpen(feature, null, requester);
        assertThat(service.approve(request.getId(), 0, null, approver).approved()).isTrue();
        FeatureEnablementH2Fixture.runAfterCommit();
        holder.reload();
    }

    private void revokeDirectly(String kind) {
        jdbc.update("UPDATE t_feature_sign_off SET status = 'REVOKED', ended_by = ?, ended_at = TIMESTAMP "
                + "'2026-11-23 09:00:00', end_reason = 'TEST' WHERE tenant_id = 'default' AND kind_code = ? AND status = "
                + "'CONFIRMED'", approver.userId(), kind);
    }

    private FeatureConditionRegistry registry(FeatureConditionEvaluator extra) {
        List<FeatureConditionEvaluator> all = new ArrayList<>(evaluators);
        if (extra != null) {
            all.add(extra);
        }
        return new FeatureConditionRegistry(catalog, all);
    }

    @SuppressWarnings("unchecked")
    private FeatureEnablementEvaluationService local(FeatureConditionEvaluator extra, FeatureEnablementService closer,
                                                     FeatureEnablementOverlay localOverlay) {
        ObjectProvider<SystemConfigService> config = mock(ObjectProvider.class);
        ObjectProvider<FeatureEnablementDayStartHook> hooks = mock(ObjectProvider.class);
        when(hooks.orderedStream()).thenAnswer(invocation -> Stream.empty());
        return new FeatureEnablementEvaluationService(properties, holder, registry(extra), catalog, localOverlay, closer,
                writer, notifier, config, hooks, FeatureEnablementTenant.fixed(FeatureEnablementH2Fixture.TENANT), clock);
    }

    private static FeatureConditionEvaluator evaluator(Feature feature, AtomicReference<FeatureConditionCheck> result) {
        return new FeatureConditionEvaluator() {
            @Override
            public Set<Feature> features() {
                return EnumSet.of(feature);
            }

            @Override
            public List<FeatureConditionCheck> evaluate(Feature target, FeatureEvaluationContext context) {
                return List.of(result.get());
            }

            @Override
            public String evaluatorName() {
                return "test-" + feature.name();
            }
        };
    }
}
