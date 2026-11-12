package com.ses.config.feature;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 段階公開する機能の機能スイッチ（app.features.*）。
 *
 * <p>既定は全てOFF。本番は {@code FEATURE_NF01_ENABLED} 等の環境変数で1機能ずつONにする。
 * 値は起動時に一度だけ読み込むため、切替にはアプリの再起動が必要である。
 * 運用手順は {@code .kiro/specs/production-launch/feature-switches.md} を参照。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.features")
public class FeatureSwitchProperties {

    /** NF-01 ライフサイクル管理・退社ゲート。 */
    private Toggle nf01 = new Toggle();

    /** NF-02 カスタマーサクセス・サービスデスク。 */
    private Toggle nf02 = new Toggle();

    /** NF-03 資格・学習・スキルギャップ。 */
    private Toggle nf03 = new Toggle();

    /** NF-04 要員PWAシェル・オフライン下書き復元。 */
    private Toggle nf04 = new Toggle();

    /** NF-10 定期管理レポート。 */
    private Toggle nf10 = new Toggle();

    @Data
    public static class Toggle {
        /** 既定OFF。明示的にtrueを設定した機能だけを公開する。 */
        private boolean enabled = false;
    }
}
