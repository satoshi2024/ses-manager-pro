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

    /**
     * 外部への検査（Journal の権限の再検査・truststore の読取り等）の結果を、世代の行の lock を取る前に新しくする（review rb-fe-c
     * P2-6。design §4.2 の {@code remoteChecksAllowed}）。申請・承認は lock の前にこれを呼び、lock を持つ transaction の中では
     * {@code remoteChecksAllowed=false} で最後の結果だけを読む。外部の検査を持つ評価器（段階 C の NF07、段階 D の NF05 等）は必ず
     * ここで行い、{@link #evaluate} の中で lock を持ったまま外部を待たない。例外は呼出し側が無視する（評価が最後の結果で判定する）。
     */
    default void refreshRemote(Feature feature) {
    }

    /** 理由の code に使う評価器の名前（固定の文字列。既定は class の単純名）。 */
    default String evaluatorName() {
        return getClass().getSimpleName();
    }
}
