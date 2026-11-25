package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 復審 rb-fe-b-656d71b8 の P3-1（D-4。feature-enablement-console design §5.6・§8.4）: NF02 顧客ポータルの継続の条件「今の公開範囲の
 * 設定が確認済みの公開範囲の決定の範囲の内」。未設定・決定と同じ・決定より後（より狭い）は成立し、決定より前（決定より広い）・決定が
 * 未設定・決定が無い・値を読めない時は不成立（継続の条件のため、registry を通しても開いている顧客ポータルを閉じる根拠になる）。
 */
class PortalVisibleSinceConditionEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private static final String V1 = "2027-04-01T00:00:00+09:00";
    private static final String V2 = "2027-05-01T00:00:00+09:00";

    private final PortalVisibleSinceConditionEvaluator evaluator = new PortalVisibleSinceConditionEvaluator();

    @Test
    void 設定が決定の範囲の内なら成立し決定より広ければ不成立() {
        assertThat(evaluate(null, "{\"visibleSince\":\"" + V1 + "\"}")).isEqualTo("setting.unset");
        assertThat(evaluate(null, null)).as("未設定は portal 起票だけで常に範囲の内").isEqualTo("setting.unset");
        assertThat(evaluate(V1, "{\"visibleSince\":\"" + V1 + "\"}")).isEqualTo("decision.covers");
        assertThat(evaluate("2027-03-31T15:00:00Z", "{\"visibleSince\":\"" + V1 + "\"}"))
                .as("同じ時点の別の offset").isEqualTo("decision.covers");
        assertThat(evaluate(V2, "{\"visibleSince\":\"" + V1 + "\"}")).as("設定のほうが狭い").isEqualTo("decision.covers");

        assertThat(evaluate(V1, "{\"visibleSince\":\"" + V2 + "\"}")).as("より狭い決定へ差し替えた")
                .isEqualTo("decision.narrower");
        assertThat(evaluate(V1, "{\"visibleSince\":\"\"}")).isEqualTo("decision.unset");
        assertThat(evaluate(V1, null)).isEqualTo("decision.unset");
        assertThat(evaluate(V1, "{broken")).isEqualTo("decision.unreadable");
        assertThat(evaluate(V1, "{\"visibleSince\":\"2027-04-01\"}")).isEqualTo("decision.unreadable");
        assertThat(evaluate("2027-04-01T00:00:00", "{\"visibleSince\":\"" + V1 + "\"}")).isEqualTo("setting.unreadable");
        assertThat(evaluateWithoutDecision(V1)).isEqualTo("decision.missing");
    }

    @Test
    void 範囲の外は継続の条件の不成立でregistryを通すと閉鎖の根拠になり値をcodeに出さない() {
        FeatureEvaluation evaluation = new FeatureConditionRegistry(FeatureEnablementCatalog.standard(), List.of(evaluator))
                .evaluate(Feature.NF02_CUSTOMER_PORTAL, context(snapshot(V1, "{\"visibleSince\":\"" + V2 + "\"}", true),
                        FeatureEvaluationContext.Purpose.PERIODIC));
        assertThat(evaluation.closingUnsatisfied()).extracting(FeatureConditionCheck::code)
                .containsExactly(PortalVisibleSinceConditionEvaluator.CODE_OUTSIDE);
        assertThat(evaluation.summary()).doesNotContain("2027");
        assertThat(FeatureEnablementCatalog.isUnverifiableCode(PortalVisibleSinceConditionEvaluator.CODE_OUTSIDE)).isFalse();
        assertThat(evaluator.features()).containsExactly(Feature.NF02_CUSTOMER_PORTAL);

        FeatureEvaluation reopen = new FeatureConditionRegistry(FeatureEnablementCatalog.standard(), List.of(evaluator))
                .evaluate(Feature.NF02_CUSTOMER_PORTAL, context(snapshot(V1, "{\"visibleSince\":\"" + V2 + "\"}", true),
                        FeatureEvaluationContext.Purpose.REQUEST));
        assertThat(reopen.blocking()).extracting(FeatureConditionCheck::code)
                .contains(PortalVisibleSinceConditionEvaluator.CODE_OUTSIDE);
    }

    private String evaluate(String setting, String decisionJson) {
        return detail(snapshot(setting, decisionJson, true));
    }

    private String evaluateWithoutDecision(String setting) {
        return detail(snapshot(setting, null, false));
    }

    private String detail(FeatureEnablementSnapshot snapshot) {
        List<FeatureConditionCheck> checks = evaluator.evaluate(Feature.NF02_CUSTOMER_PORTAL,
                context(snapshot, FeatureEvaluationContext.Purpose.PERIODIC));
        assertThat(checks).hasSize(1);
        FeatureConditionCheck check = checks.get(0);
        boolean within = check.code().equals(PortalVisibleSinceConditionEvaluator.CODE_WITHIN);
        assertThat(check.passing()).isEqualTo(within);
        assertThat(check.scope()).isEqualTo(FeatureConditionCheck.Scope.CONTINUOUS);
        return check.detailCodes().get(0);
    }

    private static FeatureEvaluationContext context(FeatureEnablementSnapshot snapshot,
                                                    FeatureEvaluationContext.Purpose purpose) {
        return new FeatureEvaluationContext("default", LocalDate.of(2026, 11, 23), Clock.fixed(NOW, ZoneOffset.UTC),
                snapshot, purpose, Set.of(), true);
    }

    private static FeatureEnablementSnapshot snapshot(String setting, String decisionJson, boolean withDecision) {
        Map<String, String> settings = new HashMap<>();
        if (setting != null) {
            settings.put(FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE, setting);
        }
        Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs = new HashMap<>();
        if (withDecision) {
            signOffs.put(PortalVisibleSinceConditionEvaluator.KIND_DECISION, new FeatureEnablementSnapshot.ConfirmedSignOff(
                    1L, 0, PortalVisibleSinceConditionEvaluator.KIND_DECISION, "CS-2026-1101", "CS_OWNER",
                    LocalDate.of(2026, 11, 1), LocalDate.of(2027, 10, 1), decisionJson, "DECISION"));
        }
        return new FeatureEnablementSnapshot(1L, NOW, NOW, true, Map.of(), settings, signOffs, Map.of());
    }
}
