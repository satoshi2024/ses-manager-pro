package com.ses.privacy.production.dsar;

import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.PrivacyDecisionBinding;
import com.ses.privacy.production.PrivacyFeatureProperties;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * DSAR の決定・設定の前提（production-bridge-phased-plan.md §5.2 の「DSAR の本人確認記録以降」）。状態を持たず、呼出しごとに評価する。
 *
 * <ul>
 *   <li>D-01（{@code privacy.approval.d01.*}）と D-06（{@code privacy.approval.d06.*}）: capability の決定と同じ規則
 *       （形式・placeholder・署名日・失効・他の決定との重複）。どちらかが欠ければ本人確認以降へ進めない
 *       （{@code DECISION_UNKNOWN}、409）。受付（REQUESTED）と終了（BLOCKED / CANCELLED）は前提にしない。</li>
 *   <li>D-06 の期限規則 {@code privacy.dsar.due-days}: 1〜{@value #MAX_DUE_DAYS} の暦日数で、D-01・D-06 が有効な場合だけ使う。
 *       それ以外は期限日を計算しない（法的な値の既定値を持たない。I-1）。</li>
 * </ul>
 * D-09 と承認 digest は機能スイッチの実行時条件と prod の起動時検査が確かめるため、ここでは見ない（OFF なら入口が404）。
 */
public class PrivacyDsarDecisions {

    static final String REASON_D01 = "d01";
    static final String REASON_D06 = "d06";
    static final int MAX_DUE_DAYS = 366;

    private final PrivacyApprovalProperties approvalProperties;
    private final PrivacyFeatureProperties featureProperties;
    private final Clock clock;

    public PrivacyDsarDecisions(PrivacyApprovalProperties approvalProperties, PrivacyFeatureProperties featureProperties,
                                Clock clock) {
        this.approvalProperties = approvalProperties;
        this.featureProperties = featureProperties;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 現在の前提。 */
    public Readiness evaluate() {
        var today = PrivacyDecisionBinding.businessDate(clock);
        List<String> missing = new ArrayList<>();
        PrivacyDecisionBinding.StandaloneEvaluation d01 = PrivacyDecisionBinding.evaluateStandalone(
                approvalProperties, PrivacyDecisionBinding.Decision.D01, today);
        if (!d01.valid()) {
            missing.add(REASON_D01);
        }
        PrivacyDecisionBinding.StandaloneEvaluation d06 = PrivacyDecisionBinding.evaluateStandalone(
                approvalProperties, PrivacyDecisionBinding.Decision.D06, today);
        if (!d06.valid()) {
            missing.add(REASON_D06);
        }
        boolean ready = missing.isEmpty();
        return new Readiness(ready, List.copyOf(missing), ready ? d06.decisionId() : null,
                ready ? configuredDueDays() : null);
    }

    /** 期限の通知を出し始める日数（1〜90 に丸める）。 */
    public int deadlineWarningDays() {
        int configured = featureProperties == null || featureProperties.getDsar() == null
                ? PrivacyFeatureProperties.Dsar.DEFAULT_DEADLINE_WARNING_DAYS
                : featureProperties.getDsar().getDeadlineWarningDays();
        return Math.max(1, Math.min(PrivacyFeatureProperties.Dsar.MAX_DEADLINE_WARNING_DAYS, configured));
    }

    /** 設定された期限規則の日数（範囲外・未設定は null）。 */
    Integer configuredDueDays() {
        Integer dueDays = featureProperties == null || featureProperties.getDsar() == null
                ? null : featureProperties.getDsar().getDueDays();
        return dueDays != null && dueDays >= 1 && dueDays <= MAX_DUE_DAYS ? dueDays : null;
    }

    /**
     * 前提の評価結果。
     *
     * @param ready         D-01・D-06 が有効
     * @param missing       欠けている決定の固定 code（{@code d01} / {@code d06}）
     * @param d06DecisionId 有効な D-06 の決定ID（本人確認に適用した基準として記録する。無効なら null）
     * @param dueDays       期限規則の日数（{@code ready} でない・未設定・範囲外なら null）
     */
    public record Readiness(boolean ready, List<String> missing, String d06DecisionId, Integer dueDays) {
        public Readiness {
            missing = missing == null ? List.of() : List.copyOf(missing);
        }

        PrivacyDsarViews.Readiness toView() {
            return new PrivacyDsarViews.Readiness(ready, missing, dueDays != null, dueDays);
        }
    }
}
