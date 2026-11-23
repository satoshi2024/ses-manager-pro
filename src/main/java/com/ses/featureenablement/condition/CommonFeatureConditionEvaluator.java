package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;

import java.util.EnumSet;
import java.util.Set;

/**
 * 全ての開放単位に掛ける共通の評価器（総スイッチ・親・署名・鍵。design §4.3）。固有の評価器と分けて数え、固有の評価器を要する開放単位に
 * 固有の評価器が無いことを {@code evaluator.missing} で検出する。
 */
public interface CommonFeatureConditionEvaluator extends FeatureConditionEvaluator {

    @Override
    default Set<Feature> features() {
        return EnumSet.allOf(Feature.class);
    }
}
