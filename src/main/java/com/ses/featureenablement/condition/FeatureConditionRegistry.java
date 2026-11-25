package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.UnitDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 全ての評価器を集め、開放単位ごとに共通の評価器（総スイッチ・親・署名・鍵）と固有の評価器の結果を並べる（design §4.2）。
 *
 * <ul>
 *   <li>評価器の例外は、その評価器の全ての check を {@code UNVERIFIABLE}（code {@code evaluator.unverifiable[<評価器の名前>]}）にする
 *       （例外の本文は log にも出さない）。</li>
 *   <li>固有の評価器を要する開放単位（目録の {@code specificEvaluatorRequired}）に固有の評価器が無ければ {@code evaluator.missing} の
 *       {@code UNVERIFIABLE} にする（申請・承認は 409。開いていれば起動時の評価で閉じる）。</li>
 *   <li>評価器が {@code UNSATISFIED} で返した check でも、code が「評価できない類」（{@link FeatureEnablementCatalog#isUnverifiableCode}）
 *       なら {@code UNVERIFIABLE} に直す（一度の通信の失敗で自動閉鎖しない。review P2-1）。</li>
 *   <li>scope は目録の宣言で決め直す: 目録が開放の時だけの条件・開放の遷移の前提と宣言した code だけがその scope になり、他は全て
 *       {@code CONTINUOUS}（評価器が scope を誤っても自動閉鎖を逃れない）。</li>
 *   <li>開放の遷移の前提は、申請・承認・{@code BOOT_PENDING} の目的でだけ結果に含める（他の目的では評価もしない。review P1-1）。</li>
 * </ul>
 */
@Slf4j
@Component
public class FeatureConditionRegistry {

    public static final String CODE_EVALUATOR_MISSING = "evaluator.missing";

    private final FeatureEnablementCatalog catalog;
    private final List<FeatureConditionEvaluator> common;
    private final List<FeatureConditionEvaluator> specific;

    public FeatureConditionRegistry(FeatureEnablementCatalog catalog, List<FeatureConditionEvaluator> evaluators) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        List<FeatureConditionEvaluator> commonList = new ArrayList<>();
        List<FeatureConditionEvaluator> specificList = new ArrayList<>();
        for (FeatureConditionEvaluator evaluator : evaluators == null ? List.<FeatureConditionEvaluator>of() : evaluators) {
            if (evaluator instanceof CommonFeatureConditionEvaluator) {
                commonList.add(evaluator);
            } else {
                specificList.add(evaluator);
            }
        }
        Comparator<FeatureConditionEvaluator> byName = Comparator.comparing(FeatureConditionEvaluator::evaluatorName);
        commonList.sort(byName);
        specificList.sort(byName);
        this.common = List.copyOf(commonList);
        this.specific = List.copyOf(specificList);
    }

    /** 目録（評価器と同じもの）。 */
    public FeatureEnablementCatalog catalog() {
        return catalog;
    }

    /** 固有の評価器を持つか（画面と起動時の評価の補助）。 */
    public boolean hasSpecificEvaluator(Feature feature) {
        return specific.stream().anyMatch(evaluator -> evaluator.features().contains(feature));
    }

    /**
     * 開放単位の固有の評価器の外部への検査の結果を新しくする（申請・承認の lock の前。{@link FeatureConditionEvaluator#refreshRemote}。
     * review rb-fe-c P2-6）。例外は log に出して続ける（評価は最後の結果で判定する）。
     */
    public void refreshRemote(Feature feature) {
        for (FeatureConditionEvaluator evaluator : specific) {
            if (!evaluator.features().contains(feature)) {
                continue;
            }
            try {
                evaluator.refreshRemote(feature);
            } catch (RuntimeException e) {
                log.warn("[機能の開放] 外部への検査を新しくできません（最後の結果で評価します）: feature={} evaluator={} "
                        + "exceptionClass={}", feature.name(), evaluator.evaluatorName(), e.getClass().getSimpleName());
            }
        }
    }

    /** 1つの開放単位を評価する。DB へは書かない。 */
    public FeatureEvaluation evaluate(Feature feature, FeatureEvaluationContext context) {
        UnitDefinition unit = catalog.unit(feature);
        List<FeatureConditionCheck> checks = new ArrayList<>();
        for (FeatureConditionEvaluator evaluator : common) {
            checks.addAll(run(evaluator, feature, context));
        }
        boolean anySpecific = false;
        for (FeatureConditionEvaluator evaluator : specific) {
            if (evaluator.features().contains(feature)) {
                anySpecific = true;
                checks.addAll(run(evaluator, feature, context));
            }
        }
        if (unit.specificEvaluatorRequired() && !anySpecific) {
            checks.add(FeatureConditionCheck.unverifiable(CODE_EVALUATOR_MISSING, FeatureConditionCheck.Category.AUTO));
        }
        List<FeatureConditionCheck> normalized = new ArrayList<>();
        for (FeatureConditionCheck check : checks) {
            FeatureConditionCheck scoped = check.withScope(scopeOf(unit, check.code()));
            if (scoped.scope() == FeatureConditionCheck.Scope.OPEN_TRANSITION
                    && !context.purpose().judgesOpenTransition()) {
                continue;
            }
            if (scoped.status() == FeatureConditionCheck.Status.UNSATISFIED
                    && FeatureEnablementCatalog.isUnverifiableCode(scoped.code())) {
                scoped = scoped.withStatus(FeatureConditionCheck.Status.UNVERIFIABLE);
            }
            normalized.add(scoped);
        }
        return new FeatureEvaluation(feature, context.purpose(), normalized, context.clock().instant());
    }

    private List<FeatureConditionCheck> run(FeatureConditionEvaluator evaluator, Feature feature,
                                            FeatureEvaluationContext context) {
        try {
            List<FeatureConditionCheck> result = evaluator.evaluate(feature, context);
            return result == null ? List.of(unverifiable(evaluator)) : result;
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 条件の評価器が例外を投げたため評価できない状態として扱います: feature={} evaluator={} exceptionClass={}",
                    feature.name(), evaluator.evaluatorName(), e.getClass().getSimpleName());
            return List.of(unverifiable(evaluator));
        }
    }

    private static FeatureConditionCheck unverifiable(FeatureConditionEvaluator evaluator) {
        return FeatureConditionCheck.unverifiable("evaluator.unverifiable[" + evaluator.evaluatorName() + "]",
                FeatureConditionCheck.Category.AUTO);
    }

    private static FeatureConditionCheck.Scope scopeOf(UnitDefinition unit, String code) {
        if (unit.openOnlyCodes().contains(code)) {
            return FeatureConditionCheck.Scope.OPEN_ONLY;
        }
        if (unit.openTransitionCodes().contains(code)) {
            return FeatureConditionCheck.Scope.OPEN_TRANSITION;
        }
        return FeatureConditionCheck.Scope.CONTINUOUS;
    }
}
