package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementTestSnapshots;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A03: registry が共通の評価器と固有の評価器の結果を並べ、scope を目録の宣言で決め直し、開放の時だけの条件は申請・承認でだけ
 * 開閉を左右し（起動時・定期では閉鎖の根拠にならない。D-9）、開放の遷移の前提は定期で評価されず（review P1-1）、評価できない類の
 * code と評価器の例外を {@code UNVERIFIABLE} にし（review P2-1）、固有の評価器の無い開放単位を {@code evaluator.missing} にする。
 */
class FeatureConditionRegistryTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);

    private final FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();

    @Test
    void 開放の時だけの条件の不成立は申請と承認では開閉を左右し起動時と定期では閉鎖の根拠にならない() {
        FeatureConditionRegistry registry = registry(stub(Feature.NF02,
                FeatureConditionCheck.unsatisfied(FeatureEnablementCatalog.CODE_NF02_CALENDAR,
                        FeatureConditionCheck.Category.AUTO, false)));
        FeatureEnablementSnapshot snapshot = validNf02();

        for (FeatureEvaluationContext.Purpose purpose : List.of(FeatureEvaluationContext.Purpose.REQUEST,
                FeatureEvaluationContext.Purpose.APPROVAL)) {
            FeatureEvaluation evaluation = registry.evaluate(Feature.NF02, context(snapshot, purpose));
            assertThat(evaluation.openingAllowed()).as(purpose.name()).isFalse();
            assertThat(evaluation.blocking()).extracting(FeatureConditionCheck::code)
                    .containsExactly(FeatureEnablementCatalog.CODE_NF02_CALENDAR);
        }
        for (FeatureEvaluationContext.Purpose purpose : List.of(FeatureEvaluationContext.Purpose.STARTUP,
                FeatureEvaluationContext.Purpose.PERIODIC, FeatureEvaluationContext.Purpose.CHANGE)) {
            FeatureEvaluation evaluation = registry.evaluate(Feature.NF02, context(snapshot, purpose));
            assertThat(evaluation.closingUnsatisfied()).as(purpose.name()).isEmpty();
            assertThat(evaluation.closingUnverifiable()).as(purpose.name()).isEmpty();
            assertThat(evaluation.openOnlyFailures()).as("警告だけ: " + purpose).extracting(FeatureConditionCheck::code)
                    .containsExactly(FeatureEnablementCatalog.CODE_NF02_CALENDAR);
        }
    }

    @Test
    void 開放の遷移の前提は申請と承認と再起動待ちの反映でだけ判定し定期では評価しない() {
        FeatureConditionRegistry registry = registry(stub(Feature.NF05_OUTBOUND,
                FeatureConditionCheck.unsatisfied(FeatureEnablementCatalog.CODE_NF05_BARRIER_OFF,
                        FeatureConditionCheck.Category.AUTO, false)));
        FeatureEnablementSnapshot snapshot = FeatureEnablementTestSnapshots.builder(NOW).open(Feature.NF05_OUTBOUND).build();

        for (FeatureEvaluationContext.Purpose purpose : List.of(FeatureEvaluationContext.Purpose.REQUEST,
                FeatureEvaluationContext.Purpose.APPROVAL, FeatureEvaluationContext.Purpose.BOOT_PENDING)) {
            assertThat(registry.evaluate(Feature.NF05_OUTBOUND, context(snapshot, purpose)).checks())
                    .as(purpose.name()).extracting(FeatureConditionCheck::code)
                    .contains(FeatureEnablementCatalog.CODE_NF05_BARRIER_OFF);
        }
        for (FeatureEvaluationContext.Purpose purpose : List.of(FeatureEvaluationContext.Purpose.STARTUP,
                FeatureEvaluationContext.Purpose.PERIODIC, FeatureEvaluationContext.Purpose.CHANGE,
                FeatureEvaluationContext.Purpose.DISPLAY)) {
            FeatureEvaluation evaluation = registry.evaluate(Feature.NF05_OUTBOUND, context(snapshot, purpose));
            assertThat(evaluation.checks()).as(purpose.name()).extracting(FeatureConditionCheck::code)
                    .doesNotContain(FeatureEnablementCatalog.CODE_NF05_BARRIER_OFF);
        }
    }

    @Test
    void 目録が宣言していないscopeは継続の条件に直し評価できない類のcodeはUNVERIFIABLEに直す() {
        FeatureConditionRegistry registry = registry(stub(Feature.NF02,
                new FeatureConditionCheck("calendar.other", FeatureConditionCheck.Category.AUTO,
                        FeatureConditionCheck.Scope.OPEN_ONLY, FeatureConditionCheck.Status.UNSATISFIED, false, null, List.of()),
                FeatureConditionCheck.unsatisfied("journal.unreachable", FeatureConditionCheck.Category.AUTO, false),
                FeatureConditionCheck.unsatisfied("privacyAdmins.unverifiable", FeatureConditionCheck.Category.AUTO, false)));

        FeatureEvaluation evaluation = registry.evaluate(Feature.NF02,
                context(validNf02(), FeatureEvaluationContext.Purpose.PERIODIC));

        assertThat(evaluation.closingUnsatisfied()).extracting(FeatureConditionCheck::code)
                .containsExactly("calendar.other");
        assertThat(evaluation.closingUnverifiable()).extracting(FeatureConditionCheck::code)
                .containsExactly("journal.unreachable", "privacyAdmins.unverifiable");
    }

    @Test
    void 評価器の例外はその評価器の全てのcheckをUNVERIFIABLEにし固有の評価器が無ければevaluator_missing() {
        FeatureConditionEvaluator failing = new FeatureConditionEvaluator() {
            @Override
            public Set<Feature> features() {
                return EnumSet.of(Feature.NF02);
            }

            @Override
            public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
                throw new IllegalStateException("db");
            }

            @Override
            public String evaluatorName() {
                return "FailingEvaluator";
            }
        };
        FeatureEvaluation failed = registry(failing).evaluate(Feature.NF02,
                context(validNf02(), FeatureEvaluationContext.Purpose.PERIODIC));
        assertThat(failed.closingUnverifiable()).extracting(FeatureConditionCheck::code)
                .containsExactly("evaluator.unverifiable[FailingEvaluator]");

        FeatureEvaluation missing = registry().evaluate(Feature.NF06,
                context(FeatureEnablementTestSnapshots.builder(NOW).open(Feature.NF06).build(),
                        FeatureEvaluationContext.Purpose.REQUEST));
        assertThat(missing.openingAllowed()).isFalse();
        assertThat(missing.blocking()).extracting(FeatureConditionCheck::code).contains(FeatureConditionRegistry.CODE_EVALUATOR_MISSING);

        // 固有の評価器を要しない開放単位は共通の評価器だけで判定する
        FeatureEvaluation nf03 = registry().evaluate(Feature.NF03, context(FeatureEnablementTestSnapshots.builder(NOW)
                .signOff("NF03_BUSINESS_OWNER", 1, 0, TODAY.minusDays(1), TODAY.plusDays(30)).build(),
                FeatureEvaluationContext.Purpose.REQUEST));
        assertThat(nf03.openingAllowed()).as(nf03.summary()).isTrue();
    }

    @Test
    void 共通の評価器は総スイッチと親と署名と鍵を並べる() {
        MockEnvironment environment = new MockEnvironment().withProperty("app.import-center.row-reference.hmac-key", "k");
        FeatureConditionRegistry registry = new FeatureConditionRegistry(catalog, commonEvaluators(environment));
        FeatureEnablementSnapshot closedMaster = FeatureEnablementTestSnapshots.builder(NOW).master(false).build();

        FeatureEvaluation child = registry.evaluate(Feature.NF03_CERTIFICATION_PII,
                context(closedMaster, FeatureEvaluationContext.Purpose.REQUEST));
        assertThat(child.blocking()).extracting(FeatureConditionCheck::code).contains("master.closed",
                "parent.NF03.closed", "signoff.NF03_DG07.missing", "signoff.NF03_EVIDENCE_PRIVACY.missing");

        FeatureEvaluation parentInChange = registry.evaluate(Feature.NF03_CERTIFICATION_PII, new FeatureEvaluationContext(
                "default", TODAY, CLOCK, FeatureEnablementTestSnapshots.builder(NOW).build(),
                FeatureEvaluationContext.Purpose.APPROVAL, Set.of(Feature.NF03), false));
        assertThat(parentInChange.checks()).extracting(FeatureConditionCheck::code).contains("parent.NF03.open");

        FeatureEvaluation nf06 = registry.evaluate(Feature.NF06,
                context(FeatureEnablementTestSnapshots.builder(NOW).build(), FeatureEvaluationContext.Purpose.REQUEST));
        assertThat(nf06.checks()).extracting(FeatureConditionCheck::code).contains("secret.IMPORT_ROW_REFERENCE_HMAC_KEY.present");
        FeatureEvaluation nf06Missing = new FeatureConditionRegistry(catalog, commonEvaluators(new MockEnvironment()))
                .evaluate(Feature.NF06, context(FeatureEnablementTestSnapshots.builder(NOW).build(),
                        FeatureEvaluationContext.Purpose.REQUEST));
        assertThat(nf06Missing.blocking()).extracting(FeatureConditionCheck::code)
                .contains("secret.IMPORT_ROW_REFERENCE_HMAC_KEY.missing");
        // 鍵の値を code・要約に出さない
        assertThat(nf06.summary()).doesNotContain("=k;").doesNotContain("=k");
    }

    // ---------------------------------------------------------------- helper

    private FeatureEnablementSnapshot validNf02() {
        return FeatureEnablementTestSnapshots.builder(NOW).open(Feature.NF02)
                .signOff("NF02_DG02", 1, 0, TODAY.minusDays(1), TODAY.plusDays(30)).build();
    }

    /**
     * review rb-fe-c P2-6: 申請・承認は世代の lock の前に、対象の開放単位の固有の評価器だけの外部への検査を新しくする。例外は続ける
     * （lock の中の評価が最後の結果で判定する）。
     */
    @Test
    void 外部への検査は対象の開放単位の評価器だけを呼び例外でも続ける() {
        List<Feature> refreshed = new ArrayList<>();
        FeatureConditionEvaluator nf07 = new FeatureConditionEvaluator() {
            @Override
            public Set<Feature> features() {
                return EnumSet.of(Feature.NF07_RETENTION, Feature.NF07_DSAR);
            }

            @Override
            public List<FeatureConditionCheck> evaluate(Feature target, FeatureEvaluationContext context) {
                return List.of();
            }

            @Override
            public void refreshRemote(Feature feature) {
                refreshed.add(feature);
                throw new IllegalStateException("journal unreachable");
            }
        };
        FeatureConditionRegistry registry = registry(nf07, stub(Feature.NF02));

        registry.refreshRemote(Feature.NF07_RETENTION);
        registry.refreshRemote(Feature.NF02);

        assertThat(refreshed).containsExactly(Feature.NF07_RETENTION);
    }

    private FeatureConditionRegistry registry(FeatureConditionEvaluator... specific) {
        List<FeatureConditionEvaluator> evaluators = new ArrayList<>(commonEvaluators(new MockEnvironment()));
        evaluators.addAll(List.of(specific));
        return new FeatureConditionRegistry(catalog, evaluators);
    }

    private List<FeatureConditionEvaluator> commonEvaluators(MockEnvironment environment) {
        return List.of(new MasterSwitchConditionEvaluator(), new ParentConditionEvaluator(),
                new SignOffConditionEvaluator(catalog), new SecretPresenceConditionEvaluator(catalog, environment));
    }

    private static FeatureConditionEvaluator stub(Feature feature, FeatureConditionCheck... checks) {
        return new FeatureConditionEvaluator() {
            @Override
            public Set<Feature> features() {
                return EnumSet.of(feature);
            }

            @Override
            public List<FeatureConditionCheck> evaluate(Feature target, FeatureEvaluationContext context) {
                return List.of(checks);
            }
        };
    }

    private static FeatureEvaluationContext context(FeatureEnablementSnapshot snapshot,
                                                    FeatureEvaluationContext.Purpose purpose) {
        return new FeatureEvaluationContext("default", TODAY, CLOCK, snapshot, purpose, Set.of(), true);
    }
}
