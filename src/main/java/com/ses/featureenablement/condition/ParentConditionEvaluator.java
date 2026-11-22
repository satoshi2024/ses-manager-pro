package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.state.EnablementState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 親の開放単位が開いていること（design §4.3）。親の開放状態が {@code OPEN}、または変更の後に開いている開放単位
 * （{@code openAfterChange}）に親を含む時に成り立つ。親を持たない開放単位には条件を出さない。
 */
@Component
public class ParentConditionEvaluator implements CommonFeatureConditionEvaluator {

    @Override
    public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
        return feature.parent().map(parent -> {
            boolean open = context.snapshot().unit(parent).state() == EnablementState.OPEN
                    || context.openAfterChange().contains(parent);
            return List.of(open
                    ? FeatureConditionCheck.satisfied("parent." + parent.name() + ".open",
                            FeatureConditionCheck.Category.PARENT, null)
                    : FeatureConditionCheck.unsatisfied("parent." + parent.name() + ".closed",
                            FeatureConditionCheck.Category.PARENT, false));
        }).orElse(List.of());
    }
}
