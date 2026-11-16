package com.ses.privacy.production.retention.hold;

import com.ses.config.OidcSecurityProperties;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.service.AuditLogService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * NF07 hold（開始・gate・reconciler）の Spring 登録（NF07-A1H-1）。
 *
 * <p>登録は profile と機能スイッチに依存しない（gate と reconciler は flag を OFF に戻しても掛けた hold を守るため。hold 開始の
 * 入口は {@link PrivacyAccessGuard} が flag と action で閉じる）。Journal の adapter は {@link PrivacyHoldJournal} の中だけで扱い、
 * {@code PrivacyJournalPort}・{@code DataSource} 型の Bean は作らない。prod 以外では adapter を生成できないため、gate は
 * hold を使った tenant について UNAVAILABLE、hold 開始は 503 になる（未使用の tenant の gate は「hold なし」のまま）。
 * application DB の独立 transaction は application の transaction manager の {@code REQUIRES_NEW} で作る。
 */
@Configuration
@EnableConfigurationProperties({PrivacyHoldProperties.class, PrivacyClockProperties.class})
public class PrivacyHoldConfiguration {

    @Bean
    public PrivacyHoldDecisions privacyHoldDecisions(PrivacyApprovalProperties approvalProperties,
                                                     PrivacyHoldProperties holdProperties,
                                                     PrivacyClockProperties clockProperties,
                                                     ObjectProvider<Clock> clock) {
        return new PrivacyHoldDecisions(approvalProperties, holdProperties, clockProperties, clockOf(clock));
    }

    @Bean
    public PrivacyHoldJournal privacyHoldJournal(PrivacyJournalConnections privacyJournalConnections,
                                                 PrivacyJournalStartupVerifier privacyJournalStartupVerifier,
                                                 PrivacyJournalProperties privacyJournalProperties,
                                                 Environment environment, ObjectProvider<Clock> clock) {
        return PrivacyHoldJournal.create(privacyJournalConnections, privacyJournalStartupVerifier,
                privacyJournalProperties, environment, clockOf(clock));
    }

    @Bean
    public HoldSubjectResolver holdSubjectResolver(JdbcTemplate jdbcTemplate,
                                                   ProductionPrivacyProviderRegistry registry) {
        return new HoldSubjectResolver(jdbcTemplate, registry);
    }

    @Bean
    public PrivacyHoldGate privacyHoldGate(JdbcTemplate jdbcTemplate, PrivacyHoldJournal privacyHoldJournal,
                                           HoldSubjectResolver holdSubjectResolver,
                                           ObjectProvider<MeterRegistry> meterRegistry) {
        return new PrivacyHoldGate(new PrivacyHoldProjectionStore(jdbcTemplate), privacyHoldJournal,
                holdSubjectResolver, meterRegistry);
    }

    @Bean
    public PrivacyHoldService privacyHoldService(PrivacyAccessGuard accessGuard,
                                                 ObjectProvider<OidcSecurityProperties> oidcProperties,
                                                 PrivacyHoldDecisions privacyHoldDecisions,
                                                 PrivacyHoldJournal privacyHoldJournal,
                                                 JdbcTemplate jdbcTemplate,
                                                 HoldSubjectResolver holdSubjectResolver,
                                                 PrivacyHoldGate privacyHoldGate,
                                                 AuditLogService auditLogService,
                                                 PlatformTransactionManager transactionManager,
                                                 ObjectProvider<Clock> clock) {
        PrivacyHoldProjectionStore store = new PrivacyHoldProjectionStore(jdbcTemplate);
        TransactionTemplate requiresNew = requiresNew(transactionManager);
        PrivacyHoldAudit audit = new PrivacyHoldAudit(auditLogService);
        Clock resolvedClock = clockOf(clock);
        return new PrivacyHoldService(accessGuard, oidcProperties.getIfAvailable(), privacyHoldDecisions,
                privacyHoldJournal, store, holdSubjectResolver, privacyHoldGate,
                new PrivacyHoldProjector(store, privacyHoldJournal, requiresNew, audit, resolvedClock), audit,
                requiresNew, resolvedClock, null);
    }

    @Bean
    public PrivacyHoldProjectionReconciler privacyHoldProjectionReconciler(PrivacyHoldJournal privacyHoldJournal,
                                                                           JdbcTemplate jdbcTemplate,
                                                                           PrivacyHoldDecisions privacyHoldDecisions,
                                                                           AuditLogService auditLogService,
                                                                           PlatformTransactionManager transactionManager,
                                                                           ObjectProvider<Clock> clock,
                                                                           ObjectProvider<MeterRegistry> meterRegistry) {
        PrivacyHoldProjectionStore store = new PrivacyHoldProjectionStore(jdbcTemplate);
        TransactionTemplate requiresNew = requiresNew(transactionManager);
        PrivacyHoldAudit audit = new PrivacyHoldAudit(auditLogService);
        Clock resolvedClock = clockOf(clock);
        return new PrivacyHoldProjectionReconciler(privacyHoldJournal, store,
                new PrivacyHoldProjector(store, privacyHoldJournal, requiresNew, audit, resolvedClock),
                privacyHoldDecisions, audit, requiresNew, resolvedClock, meterRegistry);
    }

    static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static Clock clockOf(ObjectProvider<Clock> clock) {
        Clock resolved = clock == null ? null : clock.getIfAvailable();
        return resolved == null ? Clock.systemDefaultZone() : resolved;
    }
}
