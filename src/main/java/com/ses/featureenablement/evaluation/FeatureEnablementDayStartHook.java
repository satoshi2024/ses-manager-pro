package com.ses.featureenablement.evaluation;

import com.ses.featureenablement.state.FeatureEnablementSnapshot;

import java.time.LocalDate;

/**
 * 日の始まりの処理の拡張点（feature-enablement-console design §7.5）。
 *
 * <p>DSAR が閉じている（自動閉鎖を含む）間に未完了の請求があれば privacy-admin へ件数と最も近い期限日だけを通知する処理は、
 * 段階 C（NF07 の DB 管理）でこの interface の実装として足す（期限の通知の scheduler {@code PrivacyDsarDeadlineScheduler} は
 * 閉じている間止まるため。review P3-21）。例外は日の始まりの他の処理を止めない（呼ぶ側が log に出して続ける）。</p>
 */
public interface FeatureEnablementDayStartHook {

    /**
     * 日の始まりの評価の後に呼ぶ。
     *
     * @param snapshot 評価の後の snapshot
     * @param today    JST の業務日
     */
    void onBusinessDayStart(FeatureEnablementSnapshot snapshot, LocalDate today);
}
