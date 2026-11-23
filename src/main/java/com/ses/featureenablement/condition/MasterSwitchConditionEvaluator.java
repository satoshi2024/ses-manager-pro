package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 総スイッチ（{@code FEATURE_ENABLEMENT_ALLOWED}。design §4.3・§10）。閉なら全ての開放単位の条件が成り立たない（申請・承認は 409、
 * 起動時・定期の評価で {@code AUTO_CLOSED}）。値は snapshot の {@code masterAllowed}（配備の設定から読み、DB からは読まない）。
 */
@Component
public class MasterSwitchConditionEvaluator implements CommonFeatureConditionEvaluator {

    public static final String CODE_ALLOWED = "master.allowed";
    public static final String CODE_CLOSED = "master.closed";

    @Override
    public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
        return List.of(context.snapshot().masterAllowed()
                ? FeatureConditionCheck.satisfied(CODE_ALLOWED, FeatureConditionCheck.Category.MASTER, null)
                : FeatureConditionCheck.unsatisfied(CODE_CLOSED, FeatureConditionCheck.Category.MASTER, false));
    }
}
