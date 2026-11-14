package com.ses.config.feature;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 段階公開する機能の機能スイッチ（app.features.*）。
 *
 * <p>既定は全てOFF。本番は {@code FEATURE_NF01_ENABLED} 等の環境変数で1機能ずつONにする。
 * 値は起動時に一度だけ読み込むため、切替にはアプリの再起動が必要である。
 * 真偽値として解釈できない値（例: {@code ture}）はbind時に失敗し、アプリは起動しない（OFFへは倒さない）。
 * 子スイッチ（{@code nf02-customer-portal} 等）は設定値であり、実効値は親との論理積で
 * {@link FeatureSwitches#isEnabled} が決める。
 * 運用手順は {@code .kiro/specs/production-launch/feature-switches.md} を参照。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.features")
public class FeatureSwitchProperties {

    /** NF-01 ライフサイクル管理・退社ゲート。 */
    private Toggle nf01 = new Toggle();

    /** NF-02 カスタマーサクセス・サービスデスク（内部画面）。 */
    private Toggle nf02 = new Toggle();

    /**
     * NF-02の子: 顧客ポータルの問い合わせ（{@code app.features.nf02-customer-portal.enabled}）。
     * 公開開始時刻 {@code app.features.nf02-customer-portal.visible-since} も持つ。
     */
    private CustomerPortalToggle nf02CustomerPortal = new CustomerPortalToggle();

    /** NF-03 資格・学習・スキルギャップ。 */
    private Toggle nf03 = new Toggle();

    /** NF-03の子: 資格番号の入力（{@code app.features.nf03-certification-pii.enabled}）。 */
    private Toggle nf03CertificationPii = new Toggle();

    /** NF-04 要員PWAシェル（Service Worker・manifest）。日次入力queueと下書き復元は対象外。 */
    private Toggle nf04 = new Toggle();

    /** NF-06 データ移行・取込センター（API。画面はW3-12で追加）。 */
    private Toggle nf06 = new Toggle();

    /** NF-10 定期管理レポート。 */
    private Toggle nf10 = new Toggle();

    /**
     * 機能に対応する設定値（親子の論理積を取る前の値）。機能を追加したらここへcaseを追加する
     * （switch式が網羅的でないとcompileできないため、登録漏れはbuildで検出される）。
     */
    public Toggle toggle(Feature feature) {
        return switch (feature) {
            case NF01 -> nf01;
            case NF02 -> nf02;
            case NF02_CUSTOMER_PORTAL -> nf02CustomerPortal;
            case NF03 -> nf03;
            case NF03_CERTIFICATION_PII -> nf03CertificationPii;
            case NF04 -> nf04;
            case NF06 -> nf06;
            case NF10 -> nf10;
        };
    }

    @Data
    public static class Toggle {
        /** 既定OFF。明示的にtrueを設定した機能だけを公開する。 */
        private boolean enabled = false;
    }

    /**
     * 顧客ポータルの問い合わせの子スイッチ。ON/OFFに加えて、portalへ出してよい問い合わせの範囲を持つ。
     *
     * <p>{@code visibleSince} はoffset付きISO-8601（例: {@code 2027-04-01T00:00:00+09:00}）。未設定・空なら
     * portalから起票された問い合わせだけをportalへ出す（初回リリース中に社内で登録した問い合わせを出さないfail-closed）。
     * 解釈は {@code com.ses.service.servicedesk.PortalServiceRequestVisibilityPolicy} が行い、不正な値は起動失敗になる。
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @ToString(callSuper = true)
    public static class CustomerPortalToggle extends Toggle {
        /** 公開開始時刻。この時刻以降に作成された問い合わせは、起票経路にかかわらずportalへ出す。 */
        private String visibleSince;
    }
}
