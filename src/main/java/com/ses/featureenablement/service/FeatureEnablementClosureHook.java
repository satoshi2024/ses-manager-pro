package com.ses.featureenablement.service;

import com.ses.config.feature.Feature;

/**
 * 開放単位を閉じる transaction の中で呼ぶ拡張点（design §5.5・§9.3。review P3-21）。手動の閉鎖・自動閉鎖・総スイッチの閉鎖・親の閉鎖で
 * 子を閉じる時に、開放状態の行を書いた後・commit の前に同じ transaction の中で呼ぶ。例外は遷移ごと rollback する。
 *
 * <p>段階 D（FE-D04）が NF05 outbound の barrier の行の書き直し（{@code v1|OFF|0|<指紋>} と {@code NF05_OUTBOUND_OFF} の監査）を
 * この hook で足す。段階 A では実装が無い。</p>
 */
public interface FeatureEnablementClosureHook {

    /**
     * 開放単位を閉じた。
     *
     * @param tenantId   設定の tenant
     * @param feature    閉じた開放単位
     * @param newState   閉じた後の状態（{@code MANUALLY_CLOSED} / {@code AUTO_CLOSED}）
     * @param reasonCode 理由の code
     */
    void onClosed(String tenantId, Feature feature, String newState, String reasonCode);
}
