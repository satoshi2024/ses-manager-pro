package com.ses.privacy.production.dsar;

import com.ses.config.OidcSecurityProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.mapper.privacy.PrivacyActionMapper;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.mapper.privacy.PrivacyRequestSubjectLinkMapper;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.PrivacyFeatureProperties;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.service.AuditLogService;
import com.ses.service.NotificationService;
import com.ses.service.security.AuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * NF07 DSAR 受付〜SCOPED（NF07-A2a）の Spring 登録。
 *
 * <p>登録は profile と機能スイッチに依存しない（入口は {@link PrivacyAccessGuard} が flag と action で閉じ、scheduler は入口で
 * {@code NF07_DSAR} を確認する）。状態遷移は application の transaction manager の {@code REQUIRED} の transaction で行い、
 * 監査も同じ transaction に入れる（監査に失敗すれば遷移ごと巻き戻す）。
 */
@Configuration
public class PrivacyDsarConfiguration {

    @Bean
    public PrivacyDsarDecisions privacyDsarDecisions(PrivacyApprovalProperties approvalProperties,
                                                     PrivacyFeatureProperties featureProperties,
                                                     ObjectProvider<Clock> clock) {
        return new PrivacyDsarDecisions(approvalProperties, featureProperties, clockOf(clock));
    }

    @Bean
    public PrivacyDsarAudit privacyDsarAudit(AuditLogService auditLogService) {
        return new PrivacyDsarAudit(auditLogService);
    }

    @Bean
    public PrivacyDsarRecipientResolver privacyDsarRecipientResolver(
            JdbcTemplate jdbcTemplate, ObjectProvider<AuthorizationService> authorizationService) {
        return new PrivacyDsarRecipientResolver(jdbcTemplate, authorizationService);
    }

    @Bean
    public PrivacyRequestService privacyRequestService(PrivacyAccessGuard accessGuard,
                                                       ObjectProvider<OidcSecurityProperties> oidcProperties,
                                                       PrivacyDsarDecisions privacyDsarDecisions,
                                                       PrivacyRequestMapper requestMapper,
                                                       PrivacyRequestSubjectLinkMapper linkMapper,
                                                       PrivacyActionMapper actionMapper,
                                                       ProductionPrivacyProviderRegistry registry,
                                                       PrivacyDsarAudit privacyDsarAudit,
                                                       PlatformTransactionManager transactionManager,
                                                       ObjectProvider<Clock> clock) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return new PrivacyRequestService(accessGuard, oidcProperties.getIfAvailable(), privacyDsarDecisions,
                requestMapper, linkMapper, actionMapper, registry, privacyDsarAudit, transactions, clockOf(clock), null);
    }

    @Bean
    public PrivacyDsarDeadlineScheduler privacyDsarDeadlineScheduler(PrivacyRequestMapper requestMapper,
                                                                     PrivacyDsarRecipientResolver recipientResolver,
                                                                     NotificationService notificationService,
                                                                     PrivacyDsarDecisions privacyDsarDecisions,
                                                                     ObjectProvider<OidcSecurityProperties> oidcProperties,
                                                                     ObjectProvider<Clock> clock,
                                                                     FeatureSwitches featureSwitches) {
        return new PrivacyDsarDeadlineScheduler(requestMapper, recipientResolver, notificationService,
                privacyDsarDecisions, oidcProperties.getIfAvailable(), clockOf(clock), featureSwitches);
    }

    private static Clock clockOf(ObjectProvider<Clock> clock) {
        Clock resolved = clock == null ? null : clock.getIfAvailable();
        return resolved == null ? Clock.systemDefaultZone() : resolved;
    }
}
