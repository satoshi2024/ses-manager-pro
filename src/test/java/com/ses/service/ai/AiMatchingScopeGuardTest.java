package com.ses.service.ai;

import com.ses.entity.BpAvailability;
import com.ses.entity.Project;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotExecutionContextFactory;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** NF08: Rule/Gemini共通の法人・組織・DataScopeがBPへも適用されることを確認する。 */
class AiMatchingScopeGuardTest {
    @Test
    void E1要求ではE2とNULL法人のBPを返さない() {
        DataScopeService dataScope = mock(DataScopeService.class);
        OrganizationScopeService organizationScope = mock(OrganizationScopeService.class);
        when(dataScope.isScoped()).thenReturn(false);
        when(organizationScope.hasFullAccess()).thenReturn(true);
        AiMatchingScopeGuard guard = new AiMatchingScopeGuard(dataScope, organizationScope,
                mock(CopilotExecutionContextFactory.class), mock(LegacyAiExecutionContextBinder.class));
        CopilotExecutionContext context = context(1001L);
        context.bindSnapshot(new EffectiveScopeSnapshot(
                "tenant-e1", 1001L, java.time.LocalDate.of(2026, 3, 1), "COMPANY_WIDE",
                true, false, false, null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                "c6cb16ca1754d2f6fdf5cbf9e367eaa74c771e56210badf4734994f2e35783cc"));
        Project project = new Project();
        project.setId(10L);
        project.setLegalEntityId(1001L);

        BpAvailability e1 = bp(1L, 1001L);
        BpAvailability e2 = bp(2L, 1002L);
        BpAvailability unbound = bp(3L, null);

        assertTrue(guard.allowsBp(e1, project, context));
        assertFalse(guard.allowsBp(e2, project, context));
        assertFalse(guard.allowsBp(unbound, project, context));
    }

    @Test
    void 営業DataScopeと組織Scopeはプロジェクトと要員へ同じcontext日付で適用する() {
        DataScopeService dataScope = mock(DataScopeService.class);
        OrganizationScopeService organizationScope = mock(OrganizationScopeService.class);
        when(dataScope.isScoped()).thenReturn(true);
        when(dataScope.allowedProjectIds(java.time.LocalDate.of(2026, 3, 1))).thenReturn(Set.of(10L));
        when(dataScope.allowedEngineerIds(java.time.LocalDate.of(2026, 3, 1))).thenReturn(Set.of(20L));
        when(organizationScope.hasFullAccess()).thenReturn(false);
        when(organizationScope.allowedProjectIds(java.time.LocalDate.of(2026, 3, 1))).thenReturn(Set.of(10L, 11L));
        when(organizationScope.allowedEngineerIds(java.time.LocalDate.of(2026, 3, 1))).thenReturn(Set.of(20L, 21L));
        AiMatchingScopeGuard guard = new AiMatchingScopeGuard(dataScope, organizationScope,
                mock(CopilotExecutionContextFactory.class), mock(LegacyAiExecutionContextBinder.class));
        CopilotExecutionContext context = context(1001L);
        context.bindSnapshot(new EffectiveScopeSnapshotFactory(dataScope, organizationScope)
                .create("tenant-e1", 1001L, java.time.LocalDate.of(2026, 3, 1)));

        assertTrue(guard.allowsProject(10L, context));
        assertFalse(guard.allowsProject(11L, context));
        assertTrue(guard.allowsEngineer(20L, context));
        assertFalse(guard.allowsEngineer(21L, context));
    }

    private static CopilotExecutionContext context(Long legalEntityId) {
        return new CopilotExecutionContext("tenant-e1", legalEntityId,
                Instant.parse("2026-03-01T00:00:00Z"), ZoneId.of("UTC"));
    }

    private static BpAvailability bp(Long id, Long legalEntityId) {
        BpAvailability bp = new BpAvailability();
        bp.setId(id);
        bp.setLegalEntityId(legalEntityId);
        bp.setStatus("提案可能");
        return bp;
    }
}
