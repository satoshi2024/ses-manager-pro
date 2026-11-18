package com.ses.privacy.production.retention.trust;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * NF07 の trust（{@link PrivacyTrustSource}）の Spring 登録。profile と機能スイッチに依存しない（prod 以外では verifier が
 * 作られないため、この Bean は照会・登録・解除のどれにも使われない）。
 *
 * <p>第1期は {@link UnconfiguredPrivacyTrustSource}。D-03 の方式（KMS / HSM・CRL / OCSP 等）が決まったら、その adapter を返すよう
 * 本 Bean を差し替える（説明照会の {@code PrivacyProductionConfiguration}、登録の {@code PrivacyPolicyRegistrationConfiguration}、
 * 解除の {@code PrivacyHoldConfiguration} は本 Bean だけから anchor と失効 provider を受け取る）。
 */
@Configuration
public class PrivacyTrustConfiguration {

    @Bean
    public PrivacyTrustSource privacyTrustSource() {
        return new UnconfiguredPrivacyTrustSource();
    }
}
