package com.ses.privacy.production.retention.trust;

import com.ses.privacy.production.PrivacyFeatureProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * NF07 の trust（D-03 = AWS KMS）の起動時の検査（NF07-TRUST）。prod でだけ動く。
 *
 * <p>規則は NF07 の既存の起動検査（{@code PrivacyProductionConfigurationValidator}、2026-11-14 の決定）と同じ:
 * <ul>
 *   <li>{@code privacy.retention.enabled} が OFF: 何も検査せず、起動を止めない（OFF の間は入口が404で、trust を読む経路が無い）。</li>
 *   <li>trust が未設定（{@link UnconfiguredPrivacyTrustSource}。第1期）: 違反ではない（登録・有効化と解除の入口が404のまま）。</li>
 *   <li>ON で KMS の trust を設定した: 設定（{@code anchor-file} が絶対 path、{@code max-document-age} が5分〜31日、
 *       {@code reload-interval} が1秒〜5分。時間の値は解釈できなければ {@code *.invalid}。OFF の起動では bind も失敗しない）と文書（読める・1MiB 以下・{@link KmsTrustDocument} の規則）の<b>構造的な不足</b>が
 *       1つでもあれば、固定の違反コードだけを列挙して起動を止める（Tomcat の port を開く前）。値・公開鍵・ARN は出さない。</li>
 *   <li><b>時間要因</b>（文書が鮮度の上限を過ぎた・作成日時が未来、有効期間内で ACTIVE の anchor が無い）だけなら起動は止めず、
 *       WARN（{@value #LOG_CODE_NOT_READY}）を出す。入口は閉じたまま（gate が anchor・失効状態を確認できない）で、文書を
 *       置き直せば再起動せずに開く（決定の失効日と同じ扱い）。</li>
 * </ul>
 * 実行中に文書が壊れた・消えた場合は止めず、anchor 0件・失効状態 UNKNOWN（fail-closed）として扱い WARN を出す
 * （{@link KmsPrivacyTrustSource#LOG_CODE_STATE}）。trust の anchor・失効状態は {@link PrivacyTrustSource} の契約の呼出し
 * （{@code policyAnchors()} 等）では読まず、adapter の検査用の入口だけを使う。
 */
@Component
@Profile("prod")
public class PrivacyTrustStartupValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PrivacyTrustStartupValidator.class);

    /** 時間要因で trust を使えないまま起動したことを示す固定コード（監視の log 検索用）。 */
    public static final String LOG_CODE_NOT_READY = "NF07_TRUST_NOT_READY_AT_STARTUP";

    private final PrivacyFeatureProperties featureProperties;
    private final PrivacyTrustSource privacyTrustSource;

    public PrivacyTrustStartupValidator(PrivacyFeatureProperties featureProperties,
                                        PrivacyTrustSource privacyTrustSource) {
        this.featureProperties = featureProperties;
        this.privacyTrustSource = privacyTrustSource;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    /**
     * 前提を検査し、構造的な不足があれば {@link IllegalStateException} で起動を止める。時間要因だけなら WARN を出して続ける。
     */
    public void validate() {
        if (!(privacyTrustSource instanceof KmsPrivacyTrustSource kms)) {
            log.info("[privacy-trust] NF07のtrustは未設定です（D-03の方式のtrust文書なし。anchor 0件・失効状態UNKNOWN）");
            return;
        }
        boolean retention = featureProperties != null && featureProperties.getRetention() != null
                && featureProperties.getRetention().isEnabled();
        if (!retention) {
            log.info("[privacy-trust] NF07のflagがOFFのため、trust文書の起動検査を行いません");
            return;
        }
        KmsPrivacyTrustSource.StartupReport report = kms.inspect();
        if (!report.structural().isEmpty()) {
            throw new IllegalStateException("NF07のtrust（D-03: AWS KMS）の設定・文書に構造的な不足があるため起動を中止します: "
                    + report.structural());
        }
        if (!report.fresh() || (report.usablePolicyAnchors() == 0 && report.usableHoldReleaseAnchors() == 0)) {
            log.warn("{}: NF07のtrust文書は読めましたが、鮮度の上限の外か有効なanchorが無いため、hold解除とpolicyの"
                            + "登録・有効化の入口は閉じたままです: fresh={} policyAnchors={} holdReleaseAnchors={}",
                    LOG_CODE_NOT_READY, report.fresh(), report.usablePolicyAnchors(), report.usableHoldReleaseAnchors());
            return;
        }
        log.info("[privacy-trust] NF07のtrust文書を確認しました: policyAnchors={} holdReleaseAnchors={}",
                report.usablePolicyAnchors(), report.usableHoldReleaseAnchors());
    }
}
