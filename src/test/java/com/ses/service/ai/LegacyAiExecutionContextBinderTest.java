package com.ses.service.ai;

import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** NF08: legacy認可scopeはquery開始時の一回のsnapshotとして固定する。 */
class LegacyAiExecutionContextBinderTest {
    @Test
    void bindはtenant法人asOfとscopeHashを同じcontextへ固定する() {
        DataScopeService dataScope = mock(DataScopeService.class);
        OrganizationScopeService organizationScope = mock(OrganizationScopeService.class);
        LocalDate asOf = LocalDate.of(2026, 2, 28);
        when(dataScope.isScoped()).thenReturn(true);
        when(dataScope.allowedEngineerIds(asOf)).thenReturn(Set.of(10L));
        when(dataScope.allowedProjectIds(asOf)).thenReturn(Set.of(20L));
        when(organizationScope.hasFullAccess()).thenReturn(true);

        CopilotExecutionContext context = new CopilotExecutionContext("tenant-e1", 77L,
                Instant.parse("2026-03-01T07:59:59Z"), ZoneId.of("America/Los_Angeles"));
        EffectiveScopeSnapshot snapshot = new EffectiveScopeSnapshotFactory(dataScope, organizationScope)
                .create("tenant-e1", 77L, asOf);
        context.bindSnapshot(snapshot);
        LegacyAiExecutionContextBinder binder = new LegacyAiExecutionContextBinder();
        binder.bind(context);

        assertNotNull(context.scope());
        assertEquals("tenant-e1", context.scope().tenantId());
        assertEquals(77L, context.scope().legalEntityId());
        assertEquals(asOf, context.asOfDate());
        assertEquals(context.scopeHash(), context.scope().scopeHash());
        String snapshotHash = context.scopeHash();

        when(dataScope.allowedEngineerIds(asOf)).thenReturn(Set.of(99L));
        assertEquals(snapshotHash, context.scopeHash(), "binding後に認可service再評価でsnapshotを変えない");
    }

    @Test
    void nullContextはfailClosedする() {
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> new LegacyAiExecutionContextBinder().bind(null));
    }
}
