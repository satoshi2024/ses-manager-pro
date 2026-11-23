package com.ses.featureenablement.condition;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 1つの条件の評価の結果（feature-enablement-console design §4.2）。
 *
 * <p>{@code code} と {@code detailCodes} は固定の文字列だけ（既存の違反 code をそのまま使う。例:
 * {@code decisionBinding.d01.expiresOn.expired}、{@code privacyAdmins.insufficient[current=1;required=2]}）。値・digest・
 * username・例外の本文を含めない（{@code PrivacyProductionConfigurationValidator} の message の規則と同じ）。</p>
 *
 * @param code        条件の code
 * @param category    条件の種類
 * @param scope       開閉を左右する時（registry が目録の宣言で決め直す）
 * @param status      成否
 * @param temporal    時間の経過だけで生じる不足（署名日前・失効）か
 * @param validUntil  成り立つ最後の日の翌日（排他。署名の失効日。無ければ null）
 * @param detailCodes 補足の code
 */
public record FeatureConditionCheck(String code, Category category, Scope scope, Status status, boolean temporal,
                                    LocalDate validUntil, List<String> detailCodes) {

    public FeatureConditionCheck {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(category, "category");
        scope = scope == null ? Scope.CONTINUOUS : scope;
        Objects.requireNonNull(status, "status");
        detailCodes = detailCodes == null ? List.of() : List.copyOf(detailCodes);
    }

    /** 条件の種類。 */
    public enum Category { MASTER, PARENT, AUTO, SIGN_OFF, SECRET, SETTING, ADVISORY }

    /**
     * CONTINUOUS: 開いている間も評価し、外れたら自動閉鎖する。
     * OPEN_ONLY: 申請・承認の時だけ開閉を左右し、その後は警告だけ（業務の条件。D-9 の NF02 の暦）。
     * OPEN_TRANSITION: 開く遷移（申請・承認と、OPEN_PENDING_RESTART を反映する起動）でだけ判定する技術上の前提
     * （NF05 outbound の barrier の OFF の記録）。その後は判定も警告もしない。
     */
    public enum Scope { CONTINUOUS, OPEN_ONLY, OPEN_TRANSITION }

    /** 成否。{@code NOT_APPLICABLE} は成り立つ側に数える。 */
    public enum Status { SATISFIED, UNSATISFIED, UNVERIFIABLE, NOT_APPLICABLE }

    /** 成り立つ条件。 */
    public static FeatureConditionCheck satisfied(String code, Category category, LocalDate validUntil) {
        return new FeatureConditionCheck(code, category, Scope.CONTINUOUS, Status.SATISFIED, false, validUntil, List.of());
    }

    /** 成り立たない条件。 */
    public static FeatureConditionCheck unsatisfied(String code, Category category, boolean temporal) {
        return new FeatureConditionCheck(code, category, Scope.CONTINUOUS, Status.UNSATISFIED, temporal, null, List.of());
    }

    /** 評価できない条件。 */
    public static FeatureConditionCheck unverifiable(String code, Category category) {
        return new FeatureConditionCheck(code, category, Scope.CONTINUOUS, Status.UNVERIFIABLE, false, null, List.of());
    }

    /** 当てはまらない条件（成り立つ側に数える）。 */
    public static FeatureConditionCheck notApplicable(String code, Category category) {
        return new FeatureConditionCheck(code, category, Scope.CONTINUOUS, Status.NOT_APPLICABLE, false, null, List.of());
    }

    /** scope を変えた写し。 */
    public FeatureConditionCheck withScope(Scope newScope) {
        return new FeatureConditionCheck(code, category, newScope, status, temporal, validUntil, detailCodes);
    }

    /** 成否を変えた写し。 */
    public FeatureConditionCheck withStatus(Status newStatus) {
        return new FeatureConditionCheck(code, category, scope, newStatus, temporal, validUntil, detailCodes);
    }

    /** 成り立つ側（SATISFIED・NOT_APPLICABLE）か。 */
    public boolean passing() {
        return status == Status.SATISFIED || status == Status.NOT_APPLICABLE;
    }
}
