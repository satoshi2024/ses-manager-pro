package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * NF10 の scheduler の状態（feature-enablement-console design §8.1。FE-A11）。{@code app.scheduling.enabled} が true でなければ
 * 警告だけ（{@code ADVISORY}。開閉を左右しない。定期管理レポートの配信が止まっていることを画面に出す）。property が無い時は
 * {@code SchedulingConfig} の {@code matchIfMissing = true} と同じく有効とみなす。読めない値は警告にする（開閉は変えない）。
 */
@Component
public class SchedulingConditionEvaluator implements FeatureConditionEvaluator {

    public static final String PROPERTY = "app.scheduling.enabled";
    public static final String CODE_ENABLED = "scheduling.enabled";
    public static final String CODE_DISABLED = "scheduling.disabled";

    private final Environment environment;

    public SchedulingConditionEvaluator(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public Set<Feature> features() {
        return Set.of(Feature.NF10);
    }

    @Override
    public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
        String value = environment.getProperty(PROPERTY);
        boolean enabled = value == null || "true".equalsIgnoreCase(value.trim());
        return List.of(enabled
                ? FeatureConditionCheck.satisfied(CODE_ENABLED, FeatureConditionCheck.Category.ADVISORY, null)
                : FeatureConditionCheck.unsatisfied(CODE_DISABLED, FeatureConditionCheck.Category.ADVISORY, false));
    }
}
