package com.ses.privacy.production.retention.trust;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * NF07 の trust（{@link PrivacyTrustSource}）の Spring 登録。profile と機能スイッチに依存しない（prod 以外では verifier が
 * 作られないため、この Bean は照会・登録・解除のどれにも使われない）。
 *
 * <p>選択は設定の有無だけで決まる（NF07-TRUST。D-03 = AWS KMS 非対称署名、2026-11-18 決定）:
 * <ul>
 *   <li>{@code privacy.trust.kms.anchor-file}（{@code PRIVACY_TRUST_KMS_ANCHOR_FILE}）が空（既定）: 第1期と同じ
 *       {@link UnconfiguredPrivacyTrustSource}（anchor 0件、失効状態は常に UNKNOWN。登録・有効化と解除の入口は404）。</li>
 *   <li>設定あり: {@link KmsPrivacyTrustSource}（trust 文書を判定のたびに読む）。構築時には file を読まない。文書・設定の
 *       構造的な不足は、NF07 の flag が ON の prod の起動で {@link PrivacyTrustStartupValidator} が起動を止める。</li>
 * </ul>
 * 説明照会の {@code PrivacyProductionConfiguration}、登録の {@code PrivacyPolicyRegistrationConfiguration}、解除の
 * {@code PrivacyHoldConfiguration}、NF08 の内部判定の {@code PrivacyPolicyEnforcementConfiguration} は本 Bean だけから anchor と
 * 失効 provider を受け取る。
 */
@Configuration
@EnableConfigurationProperties(KmsTrustProperties.class)
public class PrivacyTrustConfiguration {

    @Bean
    public PrivacyTrustSource privacyTrustSource(KmsTrustProperties kmsTrustProperties, ObjectProvider<Clock> clock) {
        if (kmsTrustProperties == null || !kmsTrustProperties.isConfigured()) {
            return new UnconfiguredPrivacyTrustSource();
        }
        Clock resolved = clock == null ? null : clock.getIfAvailable();
        return new KmsPrivacyTrustSource(kmsTrustProperties, resolved == null ? Clock.systemDefaultZone() : resolved);
    }
}
