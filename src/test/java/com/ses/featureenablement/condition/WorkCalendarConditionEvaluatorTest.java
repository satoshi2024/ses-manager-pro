package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementTestSnapshots;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.mapper.WorkCalendarMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-A11（feature-enablement-console design §8.1・D-9）: NF02 の全社共通カレンダーの条件を H2 の {@code m_work_calendar} で確かめる。
 * 本日を含む全社共通の有効な暦だけが成立し（{@code ServiceSlaCalculator.findCalendarForScope} の4段目と同じ条件）、暦なし・期間外・
 * 要員／組織／法人つきの暦だけ・無効の暦・論理削除した暦では不成立。check は全て開放の時だけの条件（{@code Scope.OPEN_ONLY}）で、
 * 照会の失敗も開放の時だけの条件の評価できない code になる。試験の transaction の中で暦の表を空にしてから入れ、rollback で戻す。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkCalendarConditionEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");

    @Autowired
    private WorkCalendarMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;

    private WorkCalendarConditionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        // 暦は全件を見る条件のため、この試験の transaction の中で表を空にしてから入れる（rollback で戻る）
        jdbc.update("DELETE FROM m_work_calendar_day");
        jdbc.update("DELETE FROM m_work_calendar");
        evaluator = new WorkCalendarConditionEvaluator(mapper);
    }

    @Test
    void 暦なしと期間外の暦では不成立で開放の時だけの条件() {
        assertUnsatisfied();

        calendar(null, null, null, "2025-04-01", "2026-03-31", "有効", 0);
        calendar(null, null, null, "2027-04-01", "2028-03-31", "有効", 0);

        assertUnsatisfied();
    }

    @Test
    void 要員と組織と法人つきの暦だけと無効の暦と論理削除した暦では不成立() {
        calendar(null, null, 10L, "2026-04-01", "2027-03-31", "有効", 0);
        calendar(null, 20L, null, "2026-04-01", "2027-03-31", "有効", 0);
        calendar(30L, null, null, "2026-04-01", "2027-03-31", "有効", 0);
        calendar(null, null, null, "2026-04-01", "2027-03-31", "無効", 0);
        calendar(null, null, null, "2026-04-01", "2027-03-31", "有効", 1);

        assertUnsatisfied();
    }

    @Test
    void 本日を含む全社共通の有効な暦で成立し期限は切れ目なく続く暦の終わりの翌日() {
        calendar(null, null, null, "2026-04-01", "2027-03-31", "有効", 0);

        FeatureConditionCheck check = single();
        assertThat(check.status()).isEqualTo(FeatureConditionCheck.Status.SATISFIED);
        assertThat(check.code()).isEqualTo(FeatureEnablementCatalog.CODE_NF02_CALENDAR);
        assertThat(check.scope()).isEqualTo(FeatureConditionCheck.Scope.OPEN_ONLY);
        assertThat(check.validUntil()).as("有効期間の最後の日の翌日").isEqualTo(LocalDate.of(2027, 4, 1));

        calendar(null, null, null, "2027-04-01", "2028-03-31", "ACTIVE", 0);
        assertThat(single().validUntil()).as("次年度の暦が続いていれば、その終わりまで警告しない")
                .isEqualTo(LocalDate.of(2028, 4, 1));

        calendar(null, null, null, "2028-04-01", null, "有効", 0);
        assertThat(single().validUntil()).as("終わりの無い暦が続けば期限なし").isNull();
    }

    @Test
    void 切れ目のある次の暦は期限を延ばさない() {
        calendar(null, null, null, "2026-04-01", "2026-12-31", "有効", 0);
        calendar(null, null, null, "2027-01-05", "2027-12-31", "有効", 0);

        assertThat(single().validUntil()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void 照会に失敗しても例外を投げず開放の時だけの条件の評価できないcodeを返す() {
        WorkCalendarMapper failing = mock(WorkCalendarMapper.class);
        when(failing.selectCompanyWideNotEndedBefore(any())).thenThrow(new IllegalStateException("db"));

        List<FeatureConditionCheck> checks = new WorkCalendarConditionEvaluator(failing).evaluate(Feature.NF02,
                context(FeatureEvaluationContext.Purpose.PERIODIC));

        assertThat(checks).singleElement().satisfies(check -> {
            assertThat(check.code()).isEqualTo(FeatureEnablementCatalog.CODE_NF02_CALENDAR_UNVERIFIABLE);
            assertThat(check.status()).isEqualTo(FeatureConditionCheck.Status.UNVERIFIABLE);
            assertThat(check.scope()).isEqualTo(FeatureConditionCheck.Scope.OPEN_ONLY);
        });
        assertThat(FeatureEnablementCatalog.isUnverifiableCode(checks.get(0).code())).isTrue();
    }

    /** registry を通しても scope は目録の宣言どおり開放の時だけ: 申請では開放を妨げ、定期では閉鎖の根拠にしない（D-9）。 */
    @Test
    void registryを通すと申請では開放を妨げ定期では閉鎖の根拠にならない() {
        WorkCalendarMapper failing = mock(WorkCalendarMapper.class);
        when(failing.selectCompanyWideNotEndedBefore(any())).thenThrow(new IllegalStateException("db"));
        for (WorkCalendarConditionEvaluator candidate : List.of(evaluator, new WorkCalendarConditionEvaluator(failing))) {
            FeatureConditionRegistry registry = new FeatureConditionRegistry(FeatureEnablementCatalog.standard(),
                    List.of(candidate));
            FeatureEvaluation request = registry.evaluate(Feature.NF02, context(FeatureEvaluationContext.Purpose.REQUEST));
            FeatureEvaluation periodic = registry.evaluate(Feature.NF02, context(FeatureEvaluationContext.Purpose.PERIODIC));

            assertThat(request.blocking()).extracting(FeatureConditionCheck::code)
                    .containsAnyOf(FeatureEnablementCatalog.CODE_NF02_CALENDAR,
                            FeatureEnablementCatalog.CODE_NF02_CALENDAR_UNVERIFIABLE);
            assertThat(periodic.closingUnsatisfied()).extracting(FeatureConditionCheck::code)
                    .doesNotContain(FeatureEnablementCatalog.CODE_NF02_CALENDAR);
            assertThat(periodic.closingUnverifiable()).extracting(FeatureConditionCheck::code)
                    .doesNotContain(FeatureEnablementCatalog.CODE_NF02_CALENDAR_UNVERIFIABLE)
                    .doesNotContain(FeatureConditionRegistry.CODE_EVALUATOR_MISSING);
            assertThat(periodic.openOnlyFailures()).isNotEmpty();
        }
    }

    private void assertUnsatisfied() {
        FeatureConditionCheck check = single();
        assertThat(check.code()).isEqualTo(FeatureEnablementCatalog.CODE_NF02_CALENDAR);
        assertThat(check.status()).isEqualTo(FeatureConditionCheck.Status.UNSATISFIED);
        assertThat(check.scope()).isEqualTo(FeatureConditionCheck.Scope.OPEN_ONLY);
        assertThat(check.detailCodes()).containsExactly(WorkCalendarConditionEvaluator.DETAIL_MISSING);
    }

    private FeatureConditionCheck single() {
        List<FeatureConditionCheck> checks = evaluator.evaluate(Feature.NF02, context(FeatureEvaluationContext.Purpose.REQUEST));
        assertThat(checks).hasSize(1);
        assertThat(evaluator.features()).isEqualTo(Set.of(Feature.NF02));
        return checks.get(0);
    }

    private static FeatureEvaluationContext context(FeatureEvaluationContext.Purpose purpose) {
        return new FeatureEvaluationContext("default", TODAY, Clock.fixed(NOW, ZoneOffset.UTC),
                FeatureEnablementTestSnapshots.builder(NOW).build(), purpose, Set.of(Feature.NF02), true);
    }

    private void calendar(Long legalEntityId, Long organizationId, Long engineerId, String validFrom, String validTo,
                          String status, int deleted) {
        jdbc.update("INSERT INTO m_work_calendar (legal_entity_id, organization_id, engineer_id, name, valid_from, valid_to, "
                        + "status, version, deleted_flag) VALUES (?, ?, ?, 'FE試験の暦', ?, ?, ?, 0, ?)", legalEntityId,
                organizationId, engineerId, java.sql.Date.valueOf(validFrom),
                validTo == null ? null : java.sql.Date.valueOf(validTo), status, deleted);
    }
}
