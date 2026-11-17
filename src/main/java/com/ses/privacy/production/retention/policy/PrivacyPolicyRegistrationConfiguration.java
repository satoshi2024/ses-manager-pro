package com.ses.privacy.production.retention.policy;

import com.ses.config.OidcSecurityProperties;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyRegistrationVerifier;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.retention.trust.PrivacyTrustSource;
import com.ses.service.AuditLogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Arrays;

/**
 * NF07 policy の登録・有効化（NF07-REG）の Spring 登録。
 *
 * <p>verifier は prod profile でだけ生成する（{@code ProductionPrivacyComponents.policyRegistrationVerifier}）。trust anchor と
 * 失効状態は検証のたびに {@link PrivacyTrustSource}（第1期は anchor 0件・失効状態は常に UNKNOWN）から取り、
 * 説明照会（{@code PrivacyProductionConfiguration}）と同じ値を使う。prod 以外・D-01・D-02・D-03 の署名前・trust anchor の
 * 未登録・失効状態を確認できない間は、登録・有効化の入口は存在しない URL と同じ404になる
 * （{@link PrivacyPolicyRegistrationService#isOpen()}）。登録は profile と機能スイッチに依存しない（入口は
 * {@link PrivacyAccessGuard} が flag と action で閉じる）。
 */
@Configuration
public class PrivacyPolicyRegistrationConfiguration {

    @Bean
    public PrivacyPolicyRegistrationService privacyPolicyRegistrationService(
            PrivacyAccessGuard accessGuard,
            ObjectProvider<OidcSecurityProperties> oidcProperties,
            PrivacyApprovalProperties approvalProperties,
            RetentionPolicyMapper retentionPolicyMapper,
            PiiDataElementMapper piiDataElementMapper,
            AuditLogService auditLogService,
            PlatformTransactionManager transactionManager,
            ObjectProvider<Clock> clock,
            Environment environment,
            PrivacyTrustSource privacyTrustSource) {
        Clock resolvedClock = clock.getIfAvailable(Clock::systemDefaultZone);
        // foundation の guard と同じく active profile だけで判定する（default profile の prod では生成しない）
        boolean activeProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        PolicyRegistrationVerifier verifier = activeProd
                ? ProductionPrivacyComponents.policyRegistrationVerifier(environment,
                PrivacyTrustSource.policyAnchorsOf(privacyTrustSource),
                PrivacyTrustSource.revocationsOf(privacyTrustSource), resolvedClock)
                : null;
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return new PrivacyPolicyRegistrationService(accessGuard, oidcProperties.getIfAvailable(), approvalProperties,
                verifier, retentionPolicyMapper, piiDataElementMapper, new PrivacyPolicyAudit(auditLogService),
                transaction, resolvedClock, null);
    }
}
