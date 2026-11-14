package com.ses.config.feature;

/**
 * 設定値が ON でも、実行時の条件を満たさない間は機能を OFF として扱うための条件。
 *
 * <p>例: NF07 は署名済み決定（決定ID束縛）の有効期間外になると、起動を止めずに機能だけを閉じる
 * （{@code PrivacyDecisionWindowMonitor}）。{@link FeatureSwitches#isEnabled} は設定値・親子の論理積に加えて、
 * 対象機能の条件を全て満たす場合だけ ON を返す。filter・service層の guard・sidebar は同じ判定を使うため、
 * 条件を満たさない間の応答は OFF と同じ404になる。条件の評価で例外が出た場合は OFF として扱う（fail-closed）。
 */
public interface FeatureRuntimeCondition {

    /** 条件の対象機能。 */
    Feature feature();

    /** 現在、機能を公開してよいか。呼出しごとに評価する（日付の経過で変わり得る）。 */
    boolean satisfied();
}
