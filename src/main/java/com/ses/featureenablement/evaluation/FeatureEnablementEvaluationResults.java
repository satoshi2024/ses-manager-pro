package com.ses.featureenablement.evaluation;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.condition.FeatureEvaluation;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 開放単位ごとの最後の評価の結果（この instance の memory。feature-enablement-console design §7.3・FE-RQ-011）。画面の一覧は定期・
 * 起動時の評価と利用者の「再評価」の結果をここから出し、画面を開くたびに全件を評価しない（Capacity の注意）。DB へは書かない
 * （design §2.9。再起動で消え、次の評価で戻る）。結果は code と成否だけで、値・氏名を含まない。
 */
@Component
public class FeatureEnablementEvaluationResults {

    private final Map<Feature, FeatureEvaluation> latest = new ConcurrentHashMap<>();

    /** 評価の結果を記録する（同じ開放単位の前の結果を置き換える）。 */
    public void record(FeatureEvaluation evaluation) {
        if (evaluation != null && evaluation.feature() != null) {
            latest.put(evaluation.feature(), evaluation);
        }
    }

    /** 最後の評価の結果（まだ評価していなければ空）。 */
    public Optional<FeatureEvaluation> latest(Feature feature) {
        return Optional.ofNullable(feature == null ? null : latest.get(feature));
    }
}
