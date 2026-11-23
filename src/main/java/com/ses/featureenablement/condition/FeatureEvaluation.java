package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 1つの開放単位の評価の結果（registry が共通の評価器と固有の評価器の結果を並べたもの。design §4.2・§7.4）。
 *
 * @param feature     開放単位
 * @param purpose     評価の目的
 * @param checks      条件の結果（開放の遷移の前提は、判定する目的の時だけ含む）
 * @param evaluatedAt 評価の時刻
 */
public record FeatureEvaluation(Feature feature, FeatureEvaluationContext.Purpose purpose,
                                List<FeatureConditionCheck> checks, Instant evaluatedAt) {

    /** 条件の要約の長さの上限（{@code condition_summary} の列幅）。 */
    public static final int SUMMARY_MAX_LENGTH = 2000;

    public FeatureEvaluation {
        checks = List.copyOf(checks);
    }

    /** 開く遷移を妨げる条件（ADVISORY を除き、成り立たない・評価できない条件。全ての scope を含む）。 */
    public List<FeatureConditionCheck> blocking() {
        return checks.stream()
                .filter(check -> check.category() != FeatureConditionCheck.Category.ADVISORY)
                .filter(check -> !check.passing())
                .toList();
    }

    /** 申請・承認で開いてよいか（全ての条件が成り立つ。ADVISORY を除く）。 */
    public boolean openingAllowed() {
        return blocking().isEmpty();
    }

    /** 開いている間の自動閉鎖の根拠（継続の条件の不成立。ADVISORY を除く）。 */
    public List<FeatureConditionCheck> closingUnsatisfied() {
        return continuous(FeatureConditionCheck.Status.UNSATISFIED);
    }

    /** 一時の閉鎖の根拠（継続の条件の評価できない状態。ADVISORY を除く）。 */
    public List<FeatureConditionCheck> closingUnverifiable() {
        return continuous(FeatureConditionCheck.Status.UNVERIFIABLE);
    }

    /** 開放の時だけの条件の不成立・評価できない状態（開いた後は警告だけ。design §7.4）。 */
    public List<FeatureConditionCheck> openOnlyFailures() {
        return checks.stream()
                .filter(check -> check.scope() == FeatureConditionCheck.Scope.OPEN_ONLY && !check.passing())
                .toList();
    }

    /** 警告だけの条件（ADVISORY）の不成立。 */
    public List<FeatureConditionCheck> advisories() {
        return checks.stream()
                .filter(check -> check.category() == FeatureConditionCheck.Category.ADVISORY && !check.passing())
                .toList();
    }

    /** 最初の閉鎖の理由の code（自動閉鎖の {@code closed_reason}）。 */
    public Optional<String> firstClosingReason() {
        return closingUnsatisfied().stream().map(FeatureConditionCheck::code).findFirst();
    }

    /** 成り立つ最後の日の翌日（署名の最も早い失効日。無ければ空）。 */
    public Optional<LocalDate> validUntil() {
        return checks.stream().map(FeatureConditionCheck::validUntil).filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo);
    }

    /** 条件の code と成否だけの要約（値・氏名を含めない。{@value #SUMMARY_MAX_LENGTH} 字まで）。 */
    public String summary() {
        String joined = checks.stream()
                .map(check -> check.code() + "=" + check.status().name())
                .collect(Collectors.joining(";"));
        return joined.length() <= SUMMARY_MAX_LENGTH ? joined : joined.substring(0, SUMMARY_MAX_LENGTH);
    }

    private List<FeatureConditionCheck> continuous(FeatureConditionCheck.Status status) {
        return checks.stream()
                .filter(check -> check.scope() == FeatureConditionCheck.Scope.CONTINUOUS)
                .filter(check -> check.category() != FeatureConditionCheck.Category.ADVISORY)
                .filter(check -> check.status() == status)
                .toList();
    }
}
