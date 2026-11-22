package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementTestSnapshots;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A11（feature-enablement-console design §8.1）: NF10 の scheduler が無効なら警告だけ（{@code ADVISORY}）で、申請・承認・継続の
 * どれでも開閉を左右しない。property が無ければ {@code SchedulingConfig} と同じく有効とみなす。
 */
class SchedulingConditionEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");

    @Test
    void schedulerの有効無効を警告だけの条件で返す() {
        assertThat(evaluate(null)).singleElement().satisfies(check -> {
            assertThat(check.code()).isEqualTo(SchedulingConditionEvaluator.CODE_ENABLED);
            assertThat(check.status()).isEqualTo(FeatureConditionCheck.Status.SATISFIED);
            assertThat(check.category()).isEqualTo(FeatureConditionCheck.Category.ADVISORY);
        });
        assertThat(evaluate("true")).singleElement().extracting(FeatureConditionCheck::status)
                .isEqualTo(FeatureConditionCheck.Status.SATISFIED);
        for (String disabled : List.of("false", "no")) {
            assertThat(evaluate(disabled)).singleElement().satisfies(check -> {
                assertThat(check.code()).isEqualTo(SchedulingConditionEvaluator.CODE_DISABLED);
                assertThat(check.status()).isEqualTo(FeatureConditionCheck.Status.UNSATISFIED);
                assertThat(check.category()).isEqualTo(FeatureConditionCheck.Category.ADVISORY);
            });
        }
    }

    @Test
    void scheduler無効は申請を妨げず自動閉鎖の根拠にもならない() {
        MockEnvironment environment = new MockEnvironment().withProperty(SchedulingConditionEvaluator.PROPERTY, "false");
        FeatureConditionRegistry registry = new FeatureConditionRegistry(FeatureEnablementCatalog.standard(),
                List.of(new SchedulingConditionEvaluator(environment)));

        for (FeatureEvaluationContext.Purpose purpose : FeatureEvaluationContext.Purpose.values()) {
            FeatureEvaluation evaluation = registry.evaluate(Feature.NF10, context(purpose));
            assertThat(evaluation.blocking()).extracting(FeatureConditionCheck::code)
                    .doesNotContain(SchedulingConditionEvaluator.CODE_DISABLED);
            assertThat(evaluation.closingUnsatisfied()).isEmpty();
            assertThat(evaluation.advisories()).extracting(FeatureConditionCheck::code)
                    .containsExactly(SchedulingConditionEvaluator.CODE_DISABLED);
        }
        assertThat(new SchedulingConditionEvaluator(environment).features()).isEqualTo(Set.of(Feature.NF10));
    }

    private static List<FeatureConditionCheck> evaluate(String value) {
        MockEnvironment environment = new MockEnvironment();
        if (value != null) {
            environment.setProperty(SchedulingConditionEvaluator.PROPERTY, value);
        }
        return new SchedulingConditionEvaluator(environment).evaluate(Feature.NF10,
                context(FeatureEvaluationContext.Purpose.PERIODIC));
    }

    private static FeatureEvaluationContext context(FeatureEvaluationContext.Purpose purpose) {
        return new FeatureEvaluationContext("default", LocalDate.of(2026, 11, 23), Clock.fixed(NOW, ZoneOffset.UTC),
                FeatureEnablementTestSnapshots.builder(NOW).build(), purpose, Set.of(Feature.NF10), true);
    }
}
