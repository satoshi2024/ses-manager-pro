package com.ses.featureenablement.evaluation;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.CommonFeatureConditionEvaluator;
import com.ses.featureenablement.condition.FeatureConditionEvaluator;
import com.ses.featureenablement.condition.FeatureConditionRegistry;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.condition.WorkCalendarConditionEvaluator;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.service.FeatureEnablementApprovalResult;
import com.ses.featureenablement.service.FeatureEnablementNotifier;
import com.ses.featureenablement.service.FeatureEnablementService;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.WorkCalendarMapper;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-A11（feature-enablement-console design §7.4・§8.1・D-9・J-16）: NF02 の全社共通カレンダーは開放の時だけの条件。暦が無ければ NF02 の
 * 申請・承認は条件の不成立（409・申請は未決のまま）で、開いた後に暦が切れても NF02 と子は開いたまま。暦の有効期間の終わりの 30 日前
 * から毎日、監査 {@code FEATURE_OPEN_CONDITION_EXPIRING}・管理者への通知を出し、切れた後も毎日 {@code FEATURE_OPEN_CONDITION_LOST} を
 * 出す。本物の評価器（{@code WorkCalendarConditionEvaluator}）を使い、暦の表は試験の transaction の中で空にしてから入れる。
 * 暦が無い間の SLA の計算（WARN {@code missing_calendar}・土日だけを休日）は {@code ServiceSlaCalculatorTest} が変わらず確かめる。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureEnablementNf02CalendarH2Test {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);

    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FeatureEnablementEvaluationService evaluation;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureEnablementOverlay overlay;
    @Autowired
    private FeatureSwitches switches;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;
    @Autowired
    private FeatureEnablementProperties properties;
    @Autowired
    private FeatureEnablementEvaluationWriter writer;
    @Autowired
    private FeatureEnablementNotifier notifier;
    @Autowired
    private List<FeatureConditionEvaluator> evaluators;

    private FeatureEnablementH2Fixture fixture;
    private FeatureEnablementActor requester;
    private FeatureEnablementActor approver;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        requester = fixture.admin();
        approver = fixture.admin();
        jdbc.update("DELETE FROM m_work_calendar_day");
        jdbc.update("DELETE FROM m_work_calendar");
        fixture.confirmedSignOffs(Feature.NF02, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF02_CUSTOMER_PORTAL, SIGNED, EXPIRES);
    }

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
    void 暦が無ければNF02の申請と承認は条件の不成立で申請は未決のまま() {
        BusinessException refused = catchThrowableOfType(() -> service.requestOpen(Feature.NF02, null, requester),
                BusinessException.class);
        assertThat(refused.getCode()).isEqualTo(409);
        assertThat(refused.getMessageKey()).isEqualTo("error.featureEnablement.conditionsUnsatisfied");
        assertThat(String.valueOf(refused.getArgs()[0])).contains(FeatureEnablementCatalog.CODE_NF02_CALENDAR);

        long calendarId = calendar("2026-04-01", "2027-03-31");
        FeatureEnablementRequest request = service.requestOpen(Feature.NF02, null, requester);
        jdbc.update("UPDATE m_work_calendar SET status = '無効' WHERE id = ?", calendarId);

        FeatureEnablementApprovalResult result = service.approve(request.getId(), 0, null, approver);

        assertThat(result.outcome()).isEqualTo(FeatureEnablementApprovalResult.Outcome.CONDITIONS_UNSATISFIED);
        assertThat(result.codes()).contains(FeatureEnablementCatalog.CODE_NF02_CALENDAR);
        assertThat(fixture.requestStatus(request.getId())).isEqualTo("PENDING");
        assertThat(fixture.state(Feature.NF02)).isEqualTo("CLOSED");
    }

    @Test
    void 開いた後に暦が切れてもNF02と子は開いたままで切れた後は毎日警告だけを出す() {
        calendar("2026-04-01", "2026-12-31");
        open(Feature.NF02);
        open(Feature.NF02_CUSTOMER_PORTAL);
        assertThat(switches.isEnabled(Feature.NF02_CUSTOMER_PORTAL)).isTrue();

        clock.advance(Duration.ofDays(40));
        evaluation.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        evaluation.evaluateAtBusinessDayStart();
        clock.advance(Duration.ofDays(1));
        evaluation.evaluateAtBusinessDayStart();

        assertThat(fixture.state(Feature.NF02)).as("D-9: 暦は開放の時だけの条件").isEqualTo("OPEN");
        assertThat(fixture.state(Feature.NF02_CUSTOMER_PORTAL)).isEqualTo("OPEN");
        assertThat(holder.get().unit(Feature.NF02).unverifiableSince()).as("評価できない状態にもしない").isNull();
        assertThat(switches.isEnabled(Feature.NF02)).isTrue();
        assertThat(switches.isEnabled(Feature.NF02_CUSTOMER_PORTAL)).isTrue();
        assertThat(fixture.countAudit("FEATURE_OPEN_CONDITION_LOST")).as("切れた後も毎日1回").isEqualTo(2);
        FeatureEnablementH2Fixture.runAfterCommit();
        assertThat(FeatureEnablementH2Fixture.notices()).filteredOn(notice -> notice.recipients().contains(requester.userId())
                        && "NF02".equals(notice.args()[0])).extracting(FeatureEnablementNotifier.Notice::eventKey)
                .contains("openConditionLost");
    }

    @Test
    void 暦の終わりの30日前から毎日監査と通知を出し開いたままにする() {
        calendar("2026-04-01", "2026-12-20");
        open(Feature.NF02);

        evaluation.evaluateAtBusinessDayStart();
        clock.advance(Duration.ofDays(1));
        evaluation.evaluateAtBusinessDayStart();

        assertThat(fixture.state(Feature.NF02)).isEqualTo("OPEN");
        assertThat(fixture.countAudit("FEATURE_OPEN_CONDITION_EXPIRING")).as("30日前から毎日1回").isEqualTo(2);
        assertThat(fixture.countAudit("FEATURE_OPEN_CONDITION_LOST")).isZero();
        FeatureEnablementH2Fixture.runAfterCommit();
        assertThat(FeatureEnablementH2Fixture.notices()).filteredOn(notice -> notice.recipients().contains(approver.userId())
                        && "NF02".equals(notice.args()[0])).extracting(FeatureEnablementNotifier.Notice::eventKey)
                .contains("openConditionExpiring");
    }

    @Test
    void 暦の終わりまで30日より先なら警告しない() {
        calendar("2026-04-01", "2027-03-31");
        open(Feature.NF02);

        evaluation.evaluateAtBusinessDayStart();

        assertThat(fixture.countAudit("FEATURE_OPEN_CONDITION_EXPIRING")).isZero();
        assertThat(fixture.countAudit("FEATURE_OPEN_CONDITION_LOST")).isZero();
    }

    /**
     * A2 の復審 P3-2: 暦を照会できない（評価できない）日は「外れた」とは別の監査と通知を出し、NF02 は開いたまま。照会の失敗は本物の
     * 評価器に失敗する mapper を渡して起こす（共有の評価 service と overlay は使わない）。
     */
    @Test
    void 暦を照会できない日は外れたとは別に評価できないという警告だけを出し開いたままにする() {
        calendar("2026-04-01", "2027-03-31");
        open(Feature.NF02);
        WorkCalendarMapper failing = mock(WorkCalendarMapper.class);
        when(failing.selectCompanyWideNotEndedBefore(any())).thenThrow(new IllegalStateException("calendar"));
        FeatureEnablementEvaluationService local = local(new WorkCalendarConditionEvaluator(failing));

        local.evaluateAtBusinessDayStart();

        assertThat(fixture.state(Feature.NF02)).isEqualTo("OPEN");
        assertThat(holder.get().unit(Feature.NF02).unverifiableSince()).as("開放の時だけの条件は閉鎖の根拠にしない").isNull();
        assertThat(fixture.countAudit("FEATURE_OPEN_CONDITION_UNVERIFIABLE")).isEqualTo(1);
        assertThat(fixture.countAudit("FEATURE_OPEN_CONDITION_LOST")).as("外れたとは数えない").isZero();
        assertThat(fixture.countAudit("FEATURE_OPEN_CONDITION_EXPIRING")).isZero();
        FeatureEnablementH2Fixture.runAfterCommit();
        assertThat(FeatureEnablementH2Fixture.notices()).filteredOn(notice -> notice.recipients().contains(requester.userId())
                        && "NF02".equals(notice.args()[0])).extracting(FeatureEnablementNotifier.Notice::eventKey)
                .contains("openConditionUnverifiable").doesNotContain("openConditionLost");
    }

    /** NF02 の本物の暦の評価器だけを差し替えた評価 service（共通の評価器は残す。overlay は共有しない）。 */
    @SuppressWarnings("unchecked")
    private FeatureEnablementEvaluationService local(WorkCalendarConditionEvaluator calendarEvaluator) {
        List<FeatureConditionEvaluator> all = new ArrayList<>(evaluators);
        all.removeIf(evaluator -> evaluator instanceof WorkCalendarConditionEvaluator);
        all.add(calendarEvaluator);
        assertThat(all).anyMatch(CommonFeatureConditionEvaluator.class::isInstance);
        ObjectProvider<SystemConfigService> config = mock(ObjectProvider.class);
        ObjectProvider<FeatureEnablementDayStartHook> hooks = mock(ObjectProvider.class);
        when(hooks.orderedStream()).thenAnswer(invocation -> Stream.empty());
        return new FeatureEnablementEvaluationService(properties, holder, new FeatureConditionRegistry(catalog, all),
                catalog, new FeatureEnablementOverlay(), service, writer, notifier, config, hooks,
                FeatureEnablementTenant.fixed(FeatureEnablementH2Fixture.TENANT), clock);
    }

    private void open(Feature feature) {
        FeatureEnablementRequest request = service.requestOpen(feature, null, requester);
        assertThat(service.approve(request.getId(), 0, null, approver).approved()).isTrue();
        FeatureEnablementH2Fixture.runAfterCommit();
        holder.reload();
    }

    private long calendar(String validFrom, String validTo) {
        String name = "FE-NF02-" + System.nanoTime();
        jdbc.update("INSERT INTO m_work_calendar (legal_entity_id, organization_id, engineer_id, name, valid_from, valid_to, "
                        + "status, version, deleted_flag) VALUES (NULL, NULL, NULL, ?, ?, ?, '有効', 0, 0)", name,
                java.sql.Date.valueOf(validFrom), java.sql.Date.valueOf(validTo));
        return jdbc.queryForObject("SELECT id FROM m_work_calendar WHERE name = ?", Long.class, name);
    }
}
