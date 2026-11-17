package com.ses.privacy.production;

import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyEnforcementPort;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.production.retention.policy.DbCurrentPolicySource;
import com.ses.privacy.production.retention.policy.DbProductionPolicyCatalog;
import com.ses.privacy.production.retention.policy.UnavailablePolicyEnforcementPort;
import com.ses.privacy.production.retention.trust.PrivacyTrustSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.time.Clock;

/**
 * NF07 policy の内部判定 port の Spring 登録（NF07-NF08-LINK、production-bridge-phased-plan.md §4 の LINK 行・§8）。
 *
 * <ul>
 *   <li>prod: foundation の {@link ProductionPrivacyComponents#productionPolicyEnforcement} が生成する内部判定。
 *       catalog・source・trust anchor・失効状態と公開スイッチ（決定ID束縛の有効期間を含む）は、説明照会
 *       （{@link PrivacyProductionConfiguration}）と同じ部品・同じ判定を使う（policy source は読み取りだけ）。
 *       Bean の構築時に DB へ触れない。</li>
 *   <li>prod 以外: 常に拒否する {@link UnavailablePolicyEnforcementPort}（DB を読まない）。</li>
 * </ul>
 * 呼出し元は NF08 の外部送信 gate（{@code AiProductionApprovalGate}）だけ。
 */
@Configuration
public class PrivacyPolicyEnforcementConfiguration {

    @Bean
    @Profile("prod")
    public PolicyEnforcementPort productionPolicyEnforcementPort(
            Environment environment,
            PrivacyFeatureProperties privacyFeatureProperties,
            PiiDataElementMapper piiDataElementMapper,
            RetentionPolicyMapper retentionPolicyMapper,
            ObjectProvider<PrivacyDecisionWindowMonitor> decisionWindowProvider,
            PrivacyTrustSource privacyTrustSource,
            Clock clock) {
        return ProductionPrivacyComponents.productionPolicyEnforcement(
                environment,
                PrivacyProductionConfiguration.retentionSourceEnabled(privacyFeatureProperties, decisionWindowProvider),
                new DbProductionPolicyCatalog(piiDataElementMapper, retentionPolicyMapper),
                new DbCurrentPolicySource(retentionPolicyMapper),
                privacyTrustSource.policyAnchors(),
                privacyTrustSource.revocations(),
                clock);
    }

    @Bean
    @Profile("!prod")
    public PolicyEnforcementPort unavailablePolicyEnforcementPort() {
        return new UnavailablePolicyEnforcementPort();
    }
}
