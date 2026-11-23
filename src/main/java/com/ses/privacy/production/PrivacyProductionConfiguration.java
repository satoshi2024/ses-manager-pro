package com.ses.privacy.production;

import com.ses.config.OidcSecurityProperties;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
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
 *       trust anchor と失効状態は照会のたびに {@link PrivacyTrustSource} から取る（第1期は anchor 0件・失効状態は常に UNKNOWN。D-03 の方式の
 *       adapter は同じ Bean を差し替える）。NF07 保持・保全の実効値（{@link FeatureSwitches#isEnabled}。開放状態と決定ID束縛の
 *       有効期間 {@link PrivacyDecisionWindowMonitor} を含む）は照会のたびに読み、閉・期間外なら source を読まない。
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
            ObjectProvider<FeatureSwitches> featureSwitches,
            LegalEntityContextService legalEntityContextService,
            SysUserMapper sysUserMapper,
            ObjectProvider<AuthorizationService> authorizationServiceProvider,
            OidcSecurityProperties oidcProperties,
            PiiDataElementMapper piiDataElementMapper,
            RetentionPolicyMapper retentionPolicyMapper,
            PrivacyTrustSource privacyTrustSource,
            Clock clock) {
        String authorizationTenantId = StringUtils.hasText(oidcProperties.getTenantId())
                ? oidcProperties.getTenantId() : "default";
        return ProductionPrivacyComponents.productionPolicyReadonlyQuery(
                environment,
                retentionSourceEnabled(featureSwitches),
                legalEntityContextService,
                sysUserMapper,
                authorizationServiceProvider::getIfAvailable,
                authorizationTenantId,
                new DbProductionPolicyCatalog(piiDataElementMapper, retentionPolicyMapper),
                new DbCurrentPolicySource(retentionPolicyMapper),
                PrivacyTrustSource.policyAnchorsOf(privacyTrustSource),
                PrivacyTrustSource.revocationsOf(privacyTrustSource),
                clock);
    }

    /**
     * policy source を読んでよいか（照会のたびに評価する）。NF07 保持・保全の実効値（{@link FeatureSwitches#isEnabled}）で判定する
     * （feature-enablement-console design §8.5 の 2。以前は生の flag と {@link PrivacyDecisionWindowMonitor} を自前で掛けており、
     * {@code FeatureSwitches} と同じ判定の写しだった）。開放状態が閉・決定ID束縛が有効期間外（署名日前・失効）の間は source を読まない
     * （W3-14b）。判定部品が取れない・例外なら false（fail-closed）。
     */
    static BooleanSupplier retentionSourceEnabled(ObjectProvider<FeatureSwitches> featureSwitches) {
        return () -> {
            try {
                FeatureSwitches switches = featureSwitches == null ? null : featureSwitches.getIfAvailable();
                return switches != null && switches.isEnabled(Feature.NF07_RETENTION);
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    @Bean
    @Profile("!prod")
    public PolicyReadonlyQueryPort unavailablePolicyReadonlyQueryPort() {
        return new UnavailablePolicyReadonlyQueryPort();
    }
}
