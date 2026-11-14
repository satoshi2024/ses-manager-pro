package com.ses.privacy.production;

import com.ses.config.OidcSecurityProperties;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyReadonlyQueryPort;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.production.retention.policy.ConfiguredPolicyTrustRegistry;
import com.ses.privacy.production.retention.policy.DbCurrentPolicySource;
import com.ses.privacy.production.retention.policy.DbProductionPolicyCatalog;
import com.ses.privacy.production.retention.policy.UnavailablePolicyReadonlyQueryPort;
import com.ses.privacy.production.retention.policy.UnknownRevocationStatusProvider;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.LegalEntityContextService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.time.Clock;

/**
 * NF07 policy 照会 port の Spring 登録（production-bridge-phased-plan.md §3.1.1、W-4）。
 *
 * <ul>
 *   <li>prod: foundation の {@link ProductionPrivacyComponents} が生成する production の読み取り専用照会。
 *       catalog・source は application DB の F1 表（{@code m_pii_data_element}・{@code m_retention_policy}）、
 *       trust registry は空、失効状態は常に UNKNOWN。公開スイッチ（{@code privacy.retention.enabled}）は照会のたびに読み、
 *       OFF なら source を読まない。Bean の構築時に DB へ触れない。</li>
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
            Clock clock) {
        String authorizationTenantId = StringUtils.hasText(oidcProperties.getTenantId())
                ? oidcProperties.getTenantId() : "default";
        return ProductionPrivacyComponents.productionPolicyReadonlyQuery(
                environment,
                () -> privacyFeatureProperties.getRetention() != null
                        && privacyFeatureProperties.getRetention().isEnabled(),
                legalEntityContextService,
                sysUserMapper,
                authorizationServiceProvider::getIfAvailable,
                authorizationTenantId,
                new DbProductionPolicyCatalog(piiDataElementMapper, retentionPolicyMapper),
                new DbCurrentPolicySource(retentionPolicyMapper),
                ConfiguredPolicyTrustRegistry.empty(),
                new UnknownRevocationStatusProvider(),
                clock);
    }

    @Bean
    @Profile("!prod")
    public PolicyReadonlyQueryPort unavailablePolicyReadonlyQueryPort() {
        return new UnavailablePolicyReadonlyQueryPort();
    }
}
