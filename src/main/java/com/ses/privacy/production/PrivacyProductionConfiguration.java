package com.ses.privacy.production;

import com.ses.config.OidcSecurityProperties;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyReadonlyQueryPort;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.production.retention.policy.DbCurrentPolicySource;
import com.ses.privacy.production.retention.policy.DbProductionPolicyCatalog;
import com.ses.privacy.production.retention.policy.UnavailablePolicyReadonlyQueryPort;
import com.ses.privacy.production.retention.trust.PrivacyTrustSource;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.LegalEntityContextService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.function.BooleanSupplier;

/**
 * NF07 policy 照会 port の Spring 登録（production-bridge-phased-plan.md §3.1.1、W-4）。
 *
 * <ul>
 *   <li>prod: foundation の {@link ProductionPrivacyComponents} が生成する production の読み取り専用照会。
 *       catalog・source は application DB の F1 表（{@code m_pii_data_element}・{@code m_retention_policy}）、
 *       trust anchor と失効状態は {@link PrivacyTrustSource}（第1期は anchor 0件・失効状態は常に UNKNOWN。D-03 の方式の
 *       adapter は同じ Bean を差し替える）。公開スイッチ（{@code privacy.retention.enabled}）と決定ID束縛の
 *       有効期間（{@link PrivacyDecisionWindowMonitor}）は照会のたびに読み、OFF・期間外なら source を読まない。
 *       Bean の構築時に DB へ触れない。</li>
 *   <li>prod 以外: 常に {@code POLICY_UNKNOWN} を返す {@link UnavailablePolicyReadonlyQueryPort}（DB を読まない）。</li>
 * </ul>
 * catalog・source の部品は Bean として公開しない（他の service から直接使わせない）。
 */
@Configuration
public class PrivacyProductionConfiguration {

    @Bean
    @Profile("prod")
    public PolicyReadonlyQueryPort productionPolicyReadonlyQueryPort(
            Environment environment,
            PrivacyFeatureProperties privacyFeatureProperties,
            LegalEntityContextService legalEntityContextService,
            SysUserMapper sysUserMapper,
            ObjectProvider<AuthorizationService> authorizationServiceProvider,
            OidcSecurityProperties oidcProperties,
            PiiDataElementMapper piiDataElementMapper,
            RetentionPolicyMapper retentionPolicyMapper,
            ObjectProvider<PrivacyDecisionWindowMonitor> decisionWindowProvider,
            PrivacyTrustSource privacyTrustSource,
            Clock clock) {
        String authorizationTenantId = StringUtils.hasText(oidcProperties.getTenantId())
                ? oidcProperties.getTenantId() : "default";
        return ProductionPrivacyComponents.productionPolicyReadonlyQuery(
                environment,
                retentionSourceEnabled(privacyFeatureProperties, decisionWindowProvider),
                legalEntityContextService,
                sysUserMapper,
                authorizationServiceProvider::getIfAvailable,
                authorizationTenantId,
                new DbProductionPolicyCatalog(piiDataElementMapper, retentionPolicyMapper),
                new DbCurrentPolicySource(retentionPolicyMapper),
                privacyTrustSource.policyAnchors(),
                privacyTrustSource.revocations(),
                clock);
    }

    /**
     * policy source を読んでよいか（照会のたびに評価する）。公開スイッチが OFF なら決定ID束縛を見ずに false。
     * ON でも決定ID束縛が有効期間外（署名日前・失効）の間は OFF と同じく source を読まない（W3-14b）。
     * 判定部品が取れない・例外なら false（fail-closed）。
     */
    static BooleanSupplier retentionSourceEnabled(PrivacyFeatureProperties privacyFeatureProperties,
                                                  ObjectProvider<PrivacyDecisionWindowMonitor> decisionWindowProvider) {
        return () -> privacyFeatureProperties != null
                && privacyFeatureProperties.getRetention() != null
                && privacyFeatureProperties.getRetention().isEnabled()
                && decisionWindowSatisfied(decisionWindowProvider);
    }

    private static boolean decisionWindowSatisfied(ObjectProvider<PrivacyDecisionWindowMonitor> provider) {
        try {
            PrivacyDecisionWindowMonitor monitor = provider == null ? null : provider.getIfAvailable();
            return monitor != null && monitor.satisfied();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Bean
    @Profile("!prod")
    public PolicyReadonlyQueryPort unavailablePolicyReadonlyQueryPort() {
        return new UnavailablePolicyReadonlyQueryPort();
    }
}
