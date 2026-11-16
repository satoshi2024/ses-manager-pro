package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.PrivacyDecisionBinding;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * hold 開始の決定・設定の前提（production-bridge-phased-plan.md §3.4.4 の1・§5.2）。状態を持たず、呼出しごとに評価する。
 *
 * <ul>
 *   <li>D-05（{@code privacy.approval.d05.*}）: capability の決定と同じ規則（形式・placeholder・署名日・失効・重複）。</li>
 *   <li>{@code privacy.hold.allowed-target-types}: 1件以上で、全て {@code SUBJECT} / {@code DATA_ELEMENT} のどれか。</li>
 *   <li>{@code privacy.clock.max-skew}: 正の値で上限以下。</li>
 * </ul>
 * どれかが欠ければ {@code DECISION_UNKNOWN}（409）。D-01・D-09 は機能スイッチの実行時条件（{@code PrivacyDecisionWindowMonitor}）
 * と prod の起動時検査が束縛を確かめるため、ここでは見ない（OFF なら入口が404）。理由は固定 code だけを返す。
 */
public class PrivacyHoldDecisions {

    static final String REASON_D05 = "d05";
    static final String REASON_D03 = "d03";
    static final String REASON_TARGET_TYPES = "allowedTargetTypes";
    static final String REASON_MAX_SKEW = "clock.maxSkew";

    private final PrivacyApprovalProperties approvalProperties;
    private final PrivacyHoldProperties holdProperties;
    private final PrivacyClockProperties clockProperties;
    private final Clock clock;

    public PrivacyHoldDecisions(PrivacyApprovalProperties approvalProperties, PrivacyHoldProperties holdProperties,
                                PrivacyClockProperties clockProperties, Clock clock) {
        this.approvalProperties = approvalProperties;
        this.holdProperties = holdProperties;
        this.clockProperties = clockProperties;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 現在の前提の評価。 */
    public Readiness evaluate() {
        List<String> reasons = new ArrayList<>();
        PrivacyDecisionBinding.StandaloneEvaluation d05 = PrivacyDecisionBinding.evaluateStandalone(
                approvalProperties, PrivacyDecisionBinding.Decision.D05, PrivacyDecisionBinding.businessDate(clock));
        if (!d05.valid()) {
            reasons.add(REASON_D05);
        }
        Set<PrivacyHoldTargetType> allowed = allowedTargetTypes();
        if (allowed.isEmpty()) {
            reasons.add(REASON_TARGET_TYPES);
        }
        Duration maxSkew = clockProperties != null && clockProperties.isMaxSkewValid() ? clockProperties.getMaxSkew() : null;
        if (maxSkew == null) {
            reasons.add(REASON_MAX_SKEW);
        }
        return new Readiness(reasons.isEmpty(), List.copyOf(reasons), allowed, d05.decisionId(), maxSkew);
    }

    /**
     * hold 解除（NF07-REL）を開放する決定の前提。D-05（hold の種類と権限者）と D-03（署名鍵）の両方が、capability の決定と
     * 同じ規則（形式・placeholder・署名日・失効・重複）で有効でなければ開放しない。呼出しごとに評価する（実行中の失効を拾う）。
     */
    public ReleaseReadiness releaseReadiness() {
        List<String> reasons = new ArrayList<>();
        java.time.LocalDate today = PrivacyDecisionBinding.businessDate(clock);
        PrivacyDecisionBinding.StandaloneEvaluation d05 = PrivacyDecisionBinding.evaluateStandalone(
                approvalProperties, PrivacyDecisionBinding.Decision.D05, today);
        if (!d05.valid()) {
            reasons.add(REASON_D05);
        }
        PrivacyDecisionBinding.StandaloneEvaluation d03 = PrivacyDecisionBinding.evaluateStandalone(
                approvalProperties, PrivacyDecisionBinding.Decision.D03, today);
        if (!d03.valid()) {
            reasons.add(REASON_D03);
        }
        return new ReleaseReadiness(reasons.isEmpty(), List.copyOf(reasons), d03.decisionId(), d05.decisionId());
    }

    /**
     * 解除 proof の判定規則（D-05「解除の承認者規則」と時計の許容ずれ）。呼出しごとに設定から作る。設定が無い・解釈できない
     * 場合は最も厳しい側（開始者と分離、開始者が不明なら拒否、許容ずれ 0）。
     */
    public com.ses.privacy.foundation.HoldReleaseRules releaseRules() {
        PrivacyHoldProperties.Release release = holdProperties == null ? null : holdProperties.getRelease();
        boolean separation = release == null || release.starterSeparationRequired();
        boolean rejectUnknown = release == null || release.rejectUnknownStarter();
        return new com.ses.privacy.foundation.HoldReleaseRules(separation, rejectUnknown, maxSkew());
    }

    /**
     * 解除の開放の前提の評価結果。
     *
     * @param ready         D-03・D-05 がどちらも有効か
     * @param missing       欠けている決定の固定 code（{@code d03} / {@code d05}）
     * @param d03DecisionId 有効な D-03 の決定ID（無効なら null）
     * @param d05DecisionId 有効な D-05 の決定ID（無効なら null）
     */
    public record ReleaseReadiness(boolean ready, List<String> missing, String d03DecisionId, String d05DecisionId) {
        public ReleaseReadiness {
            missing = List.copyOf(missing);
        }
    }

    /** 設定の許可種別。1件でも解釈できない値があれば空（全体を不正として扱う）。 */
    Set<PrivacyHoldTargetType> allowedTargetTypes() {
        if (holdProperties == null || holdProperties.getAllowedTargetTypes() == null
                || holdProperties.getAllowedTargetTypes().isEmpty()) {
            return Set.of();
        }
        Set<PrivacyHoldTargetType> types = EnumSet.noneOf(PrivacyHoldTargetType.class);
        for (String value : holdProperties.getAllowedTargetTypes()) {
            var parsed = PrivacyHoldTargetType.parse(value == null ? null : value.trim());
            if (parsed.isEmpty()) {
                return Set.of();
            }
            types.add(parsed.get());
        }
        return Collections.unmodifiableSet(types);
    }

    /** 時計の許容ずれ（有効な設定値だけ。無ければ null）。 */
    Duration maxSkew() {
        return clockProperties != null && clockProperties.isMaxSkewValid() ? clockProperties.getMaxSkew() : null;
    }

    /**
     * 前提の評価結果。
     *
     * @param ready              全て揃っているか
     * @param missing            欠けている前提の固定 code（{@code d05} / {@code allowedTargetTypes} / {@code clock.maxSkew}）
     * @param allowedTargetTypes 許可された対象種別
     * @param d05DecisionId      有効な D-05 の決定ID（無効なら null）
     * @param maxSkew            時計の許容ずれ（無効なら null）
     */
    public record Readiness(boolean ready, List<String> missing, Set<PrivacyHoldTargetType> allowedTargetTypes,
                            String d05DecisionId, Duration maxSkew) {
        public Readiness {
            missing = List.copyOf(missing);
            allowedTargetTypes = allowedTargetTypes == null ? Set.of() : Set.copyOf(allowedTargetTypes);
        }
    }
}
