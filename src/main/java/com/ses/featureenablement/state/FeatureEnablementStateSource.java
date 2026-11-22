package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;

import java.util.List;
import java.util.Optional;

/**
 * 開放状態の源（design §3.1）。hot path（filter・ControllerAdvice）から呼ばれ、DB に往復しない。親との論理積と実行時条件は
 * {@code FeatureSwitches} が掛ける。
 */
public interface FeatureEnablementStateSource {

    /** 開放状態・総スイッチ・有効期限・一時の閉鎖だけで見た開閉。 */
    boolean isOpen(Feature feature);

    /** 承認で変える設定の値（例: {@code nf02-customer-portal.visible-since}）。未設定は空。 */
    Optional<String> setting(String key);

    /** 世代（property の源は 0 固定）。 */
    long generation();

    /** 閉じている理由の code（開いていれば空。画面の実効値の表示に使う。design §15.1）。 */
    default List<String> closedReasons(Feature feature) {
        return isOpen(feature) ? List.of() : List.of("source.closed");
    }

    /** DB の源か（段階の印の付いた開放単位を DB で開閉する）。 */
    default boolean database() {
        return false;
    }

    /** 状態が差し替わった時の通知先を足す（property の源は差し替わらないため何もしない）。 */
    default void addChangeListener(Runnable listener) {
    }
}
