package com.ses.service.lifecycle.dual;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.signoff.SignOffReferenceRules;

import java.util.regex.Pattern;

/**
 * 二者確認の子スイッチ（{@link Feature#NF01_DUAL_CONFIRMATION}）を開く前の Privacy 署名の参照の規則（NF01細粒度epoch計画 §7.8
 * 開始条件・§10 Privacy・§12 O-8、統括の判断）。
 *
 * <p>二者確認は提出・確認の業務comment（{@code t_lifecycle_dual_submission.business_comment}・
 * {@code t_lifecycle_dual_confirmation.decision_comment}）を追記専用表へ保存する。これらの保存期間と削除・匿名化の経路は
 * Privacyの署名で決まる（O-8）ため、commentを実際に書き込む＝二者確認を開くのは署名の後とする。</p>
 *
 * <p>以前は prod profile の起動時検査（{@code @PostConstruct}）で、子スイッチの実効値がONなのに参照が無い・不正・placeholderなら
 * 起動を止めていた。機能の開放（feature-enablement-console design §8.2、requirements §7 の「機能を閉じる」。FE-B01）で、機能の条件
 * では起動を止めず、その開放単位だけを閉じる形に改めた: 同じ判定（{@link #acceptable}）を profile に依らない評価器
 * {@link LifecycleDualConfirmationEnablementConditions} が使い、起動時・定期・申請と承認の評価で条件が成り立たなければ二者確認を開か
 * ない・自動閉鎖する（{@code FeatureEnablementStartupEvaluator}）。本classは Spring の bean ではなく、規則だけを持つ。</p>
 */
public final class LifecycleDualConfirmationReleaseGate {

    /**
     * 署名の参照の形式（英数字で始まる4〜128文字。review文書のID・ticket番号等）。placeholder の語の規則とともに、機能の開放の
     * 署名の参照と共通の部品（{@link SignOffReferenceRules}）にある。
     */
    static final Pattern SIGN_OFF_REF = SignOffReferenceRules.REFERENCE;

    private LifecycleDualConfirmationReleaseGate() {
    }

    /**
     * 署名の参照として受け付ける値か。null・空・前後の空白・形式不正（{@link #SIGN_OFF_REF}）、英字と数字の両方を含まない値、
     * placeholderの語を一部に含む値・placeholderの語で始まる値は不可。
     */
    static boolean acceptable(String reference) {
        return SignOffReferenceRules.withLetterAndDigit(reference);
    }
}
