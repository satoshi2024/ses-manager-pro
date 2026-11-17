package com.ses.privacy.production.retention.hold;

import com.ses.config.OidcSecurityProperties;
import com.ses.privacy.foundation.HoldReleaseTrustRegistry;
import com.ses.privacy.foundation.HoldReleaseVerifier;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.retention.policy.UnknownRevocationStatusProvider;
import com.ses.privacy.production.retention.trust.PrivacyTrustSource;
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
                                                 Environment environment, ObjectProvider<Clock> clock,
                                                 JdbcTemplate jdbcTemplate) {
        // application DB の @@server_uuid（Journal と同じ instance への誤接続を実行中に検出する。Review P3-8）。
        // Journal が使える前提が揃ったときだけ呼ばれる（未設定・prod 以外では application DB に照会しない）。
        // query timeout と socket の上限を置き、比較の lock を持つ thread が戻らなくならないようにする（RB-NF07-HOLD review P3-1）
        return PrivacyHoldJournal.create(privacyJournalConnections, privacyJournalStartupVerifier,
                privacyJournalProperties, environment, clockOf(clock), new ApplicationServerUuidQuery(jdbcTemplate));
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

    /** 破壊的経路（D1〜D7・D9）の入口（A1H-2）。gate と同じく profile・機能スイッチに依存しない。 */
    @Bean
    public PrivacyHoldPathGuard privacyHoldPathGuard(PrivacyHoldGate privacyHoldGate,
                                                     HoldSubjectResolver holdSubjectResolver,
                                                     ObjectProvider<MeterRegistry> meterRegistry) {
        return new PrivacyHoldPathGuard(privacyHoldGate, holdSubjectResolver, meterRegistry);
    }

    /**
     * D10: 孤児 file の清掃から hold の対象の実体を守る provider（A1H-2）。{@code FileCleanupServiceImpl} と
     * {@code LegacyUploadMigrationService} が {@code List<FileReferenceProvider>} として受け取る（参照を足すだけで減らさない）。
     */
    @Bean
    public PrivacyHoldFileReferenceProvider privacyHoldFileReferenceProvider(PrivacyHoldGate privacyHoldGate,
                                                                             HoldSubjectResolver holdSubjectResolver,
                                                                             ObjectProvider<MeterRegistry> meterRegistry) {
        return new PrivacyHoldFileReferenceProvider(privacyHoldGate, holdSubjectResolver, meterRegistry);
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
                                                 ObjectProvider<Clock> clock,
                                                 ObjectProvider<PrivacyHoldReleaseService> releaseService) {
        PrivacyHoldProjectionStore store = new PrivacyHoldProjectionStore(jdbcTemplate);
        TransactionTemplate requiresNew = requiresNew(transactionManager);
        PrivacyHoldAudit audit = new PrivacyHoldAudit(auditLogService);
        Clock resolvedClock = clockOf(clock);
        return new PrivacyHoldService(accessGuard, oidcProperties.getIfAvailable(), privacyHoldDecisions,
                privacyHoldJournal, store, holdSubjectResolver, privacyHoldGate,
                new PrivacyHoldProjector(store, privacyHoldJournal, requiresNew, audit, resolvedClock), audit,
                requiresNew, resolvedClock, null, () -> {
                    PrivacyHoldReleaseService service = releaseService.getIfAvailable();
                    return service != null && service.isOpen();
                });
    }

    /**
     * hold 解除 authority の trust anchor（NF07-REL）。{@link PrivacyTrustSource} から取る（第1期は空。D-03 の方式の adapter は
     * {@code PrivacyTrustSource} の Bean を差し替える）。
     */
    @Bean
    public HoldReleaseTrustRegistry privacyHoldReleaseTrustRegistry(PrivacyTrustSource privacyTrustSource) {
        return privacyTrustSource.holdReleaseAnchors();
    }

    /**
     * hold の解除（NF07-REL）。verifier は prod profile でだけ生成し（{@code ProductionPrivacyComponents.holdReleaseVerifier}）、
     * 失効状態は {@link PrivacyTrustSource} の provider（第1期は常に UNKNOWN の {@link UnknownRevocationStatusProvider}）。prod 以外・
     * D-03 と D-05 の署名前・trust anchor 未登録・失効状態を ACTIVE と確認できない間は、解除の入口は存在しない URL と同じ404になる
     * （{@link PrivacyHoldReleaseService#isOpen()}）。
     */
    @Bean
    public PrivacyHoldReleaseService privacyHoldReleaseService(PrivacyAccessGuard accessGuard,
                                                               ObjectProvider<OidcSecurityProperties> oidcProperties,
                                                               PrivacyHoldDecisions privacyHoldDecisions,
                                                               PrivacyHoldJournal privacyHoldJournal,
                                                               JdbcTemplate jdbcTemplate,
                                                               HoldSubjectResolver holdSubjectResolver,
                                                               AuditLogService auditLogService,
                                                               PlatformTransactionManager transactionManager,
                                                               ObjectProvider<Clock> clock,
                                                               HoldReleaseTrustRegistry privacyHoldReleaseTrustRegistry,
                                                               Environment environment,
                                                               PrivacyTrustSource privacyTrustSource) {
        PrivacyHoldProjectionStore store = new PrivacyHoldProjectionStore(jdbcTemplate);
        TransactionTemplate requiresNew = requiresNew(transactionManager);
        PrivacyHoldAudit audit = new PrivacyHoldAudit(auditLogService);
        Clock resolvedClock = clockOf(clock);
        // foundation の guard と同じく active profile だけで判定する（default profile の prod では生成しない）
        boolean activeProd = java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod");
        HoldReleaseVerifier verifier = activeProd
                ? ProductionPrivacyComponents.holdReleaseVerifier(environment, privacyHoldReleaseTrustRegistry,
                privacyTrustSource.revocations(), resolvedClock)
                : null;
        return new PrivacyHoldReleaseService(accessGuard, oidcProperties.getIfAvailable(), privacyHoldDecisions,
                privacyHoldJournal, store, holdSubjectResolver,
                new PrivacyHoldProjector(store, privacyHoldJournal, requiresNew, audit, resolvedClock), audit,
                requiresNew, resolvedClock, null, verifier, privacyHoldReleaseTrustRegistry);
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
        // 復元した行の開始者を開始要求の監査（t_audit_log）と sys_user から引く（NF07-REL delta review P2-1）
        return new PrivacyHoldProjectionReconciler(privacyHoldJournal, store,
                new PrivacyHoldProjector(store, privacyHoldJournal, requiresNew, audit, resolvedClock),
                privacyHoldDecisions, audit, requiresNew, resolvedClock, meterRegistry,
                new PrivacyHoldStarterLookup(jdbcTemplate));
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
