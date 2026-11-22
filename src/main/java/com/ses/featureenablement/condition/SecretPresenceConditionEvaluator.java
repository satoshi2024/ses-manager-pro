package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.KeyRequirement;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 開放単位の条件になる配備の設定の鍵の有無（design §4.3）。値は読んで有無を見るだけで、返さない・log に出さない。鍵の形式の判定は
 * 各段階の固有の評価器が既存の部品を呼んで行う（段階 B〜D）。読めない（例外）時は評価できない類の code にする。
 */
@Component
public class SecretPresenceConditionEvaluator implements CommonFeatureConditionEvaluator {

    private final FeatureEnablementCatalog catalog;
    private final Environment environment;

    public SecretPresenceConditionEvaluator(FeatureEnablementCatalog catalog, Environment environment) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
        List<FeatureConditionCheck> checks = new ArrayList<>();
        for (KeyRequirement key : catalog.unit(feature).keys()) {
            String prefix = "secret." + key.name();
            boolean present;
            try {
                String value = environment.getProperty(key.property());
                present = value != null && !value.isBlank();
            } catch (RuntimeException e) {
                checks.add(FeatureConditionCheck.unverifiable(prefix + ".unverifiable",
                        FeatureConditionCheck.Category.SECRET));
                continue;
            }
            checks.add(present
                    ? FeatureConditionCheck.satisfied(prefix + ".present", FeatureConditionCheck.Category.SECRET, null)
                    : FeatureConditionCheck.unsatisfied(prefix + ".missing", FeatureConditionCheck.Category.SECRET, false));
        }
        return checks;
    }
}
