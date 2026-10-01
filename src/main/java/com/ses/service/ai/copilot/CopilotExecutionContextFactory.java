package com.ses.service.ai.copilot;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.service.security.LegalEntityContextService;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** query開始時に1回だけ、security/org contextから法人を確定する。 */
@Component
public class CopilotExecutionContextFactory {
    private final Clock clock;
    private final LegalEntityContextService legalEntityContextService;
    private final com.ses.service.security.LegalEntityReadinessService legalEntityReadinessService;
    @Autowired
    private EffectiveScopeSnapshotFactory effectiveScopeSnapshotFactory;

    @Autowired
    public CopilotExecutionContextFactory(Clock clock, LegalEntityContextService legalEntityContextService,
                                          com.ses.service.security.LegalEntityReadinessService legalEntityReadinessService) {
        this.clock = clock;
        this.legalEntityContextService = legalEntityContextService;
        this.legalEntityReadinessService = legalEntityReadinessService;
    }

    /** テストでsnapshot計算器を明示的に差し替えるためのconstructor。 */
    CopilotExecutionContextFactory(Clock clock, LegalEntityContextService legalEntityContextService,
                                   com.ses.service.security.LegalEntityReadinessService readiness,
                                   EffectiveScopeSnapshotFactory snapshotFactory) {
        this(clock, legalEntityContextService, readiness);
        this.effectiveScopeSnapshotFactory = snapshotFactory;
    }

    /** 既存の単体テスト/adapter構築用。通常実行はSpring注入のcontext serviceを使う。 */
    CopilotExecutionContextFactory(Clock clock, com.ses.mapper.AttendanceScopeMapper attendanceScopeMapper,
                                          OidcSecurityProperties oidcSecurityProperties,
                                          com.ses.service.accounting.AccountingTimezoneResolver timezoneResolver) {
        this(clock, new LegalEntityContextService(clock, attendanceScopeMapper, timezoneResolver,
                oidcSecurityProperties), null);
    }

    CopilotExecutionContextFactory(Clock clock, com.ses.mapper.AttendanceScopeMapper attendanceScopeMapper,
                                   OidcSecurityProperties oidcSecurityProperties,
                                   com.ses.service.accounting.AccountingTimezoneResolver timezoneResolver,
                                   com.ses.service.security.LegalEntityReadinessService legalEntityReadinessService) {
        this(clock, new LegalEntityContextService(clock, attendanceScopeMapper, timezoneResolver,
                oidcSecurityProperties), legalEntityReadinessService);
    }

    public CopilotExecutionContext create() {
        if (legalEntityReadinessService == null) {
            throw BusinessException.of(503, "LEGAL_ENTITY_READINESS_UNAVAILABLE");
        }
        legalEntityReadinessService.assertReady();
        java.time.Instant asOf = clock.instant();
        String configuredTenantId = legalEntityContextService.requireTenantId();
        if (configuredTenantId == null || configuredTenantId.isBlank() || SecurityUtils.currentRole() == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        // queryごとに一度だけ解決し、以降はcontext.zoneIdを正本として使う。
        ZoneId tenantZone = legalEntityContextService.resolveTenantZone(configuredTenantId);
        Long legalEntityId = legalEntityContextService.requireCurrentLegalEntityId(asOf, tenantZone);
        CopilotExecutionContext context = new CopilotExecutionContext(configuredTenantId, legalEntityId, asOf, tenantZone);
        if (effectiveScopeSnapshotFactory != null) {
            EffectiveScopeSnapshot snapshot = effectiveScopeSnapshotFactory.create(
                    configuredTenantId, legalEntityId, asOf.atZone(tenantZone).toLocalDate());
            context.bindSnapshot(snapshot);
        }
        return context;
    }

    /**
     * response直前の再認可用contextを作る。新しい現在時刻は生成せず、元queryのasOf/tenant/legal
     * entity/timezoneを再利用したうえで、権限サービスだけを現在値で再評価する。
     */
    public CopilotExecutionContext createReauthorization(CopilotExecutionContext original) {
        if (original == null || original.effectiveScopeSnapshot() == null) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }
        if (original.scope() != original.effectiveScopeSnapshot().scope()
                || !original.effectiveScopeSnapshot().scopeHash().equals(original.scopeHash())) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_SCOPE_MISMATCH");
        }
        if (legalEntityReadinessService == null) {
            throw BusinessException.of(503, "LEGAL_ENTITY_READINESS_UNAVAILABLE");
        }
        legalEntityReadinessService.assertReady();

        String currentTenantId = legalEntityContextService.requireTenantId();
        ZoneId currentZone = legalEntityContextService.resolveTenantZone(currentTenantId);
        Long currentLegalEntityId = legalEntityContextService.requireCurrentLegalEntityId(
                original.asOf(), original.zoneId());
        if (!original.tenantId().equals(currentTenantId)
                || !original.zoneId().equals(currentZone)
                || !original.legalEntityId().equals(currentLegalEntityId)) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REAUTHORIZATION_MISMATCH");
        }

        EffectiveScopeSnapshot currentSnapshot = effectiveScopeSnapshotFactory == null ? null
                : effectiveScopeSnapshotFactory.create(original.tenantId(), original.legalEntityId(),
                original.asOfDate());
        if (currentSnapshot == null) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }
        CopilotExecutionContext reauthorization = new CopilotExecutionContext(
                original.tenantId(), original.legalEntityId(), original.asOf(), original.zoneId());
        reauthorization.bindSnapshot(currentSnapshot);
        return reauthorization;
    }
}
