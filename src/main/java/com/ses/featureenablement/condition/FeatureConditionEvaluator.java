package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;

import java.util.List;
import java.util.Set;

/**
 * 開放単位の条件の評価器（design §4.2）。起動時検査の class に実装させ、{@code validate} が使っている検査の method をそのまま呼ぶ
 * （条件の写しを作らない。design §4.4 の PR #130 の形）。DB へは書かない。例外は {@link FeatureConditionRegistry} が
 * {@code UNVERIFIABLE}（{@code evaluator.unverifiable[<名前>]}）にする。
 */
public interface FeatureConditionEvaluator {

    /** 受け持つ開放単位。 */
    Set<Feature> features();

    /** 条件を評価する。 */
    List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context);

    /** 理由の code に使う評価器の名前（固定の文字列。既定は class の単純名）。 */
    default String evaluatorName() {
        return getClass().getSimpleName();
    }
}
