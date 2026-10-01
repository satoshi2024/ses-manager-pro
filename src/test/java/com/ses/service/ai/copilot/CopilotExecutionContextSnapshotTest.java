package com.ses.service.ai.copilot;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.ai.LegacyAiExecutionContextBinder;
import com.ses.service.ai.AiMatchingScopeGuard;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.LegalEntityContextService;
import com.ses.service.security.LegalEntityReadinessService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** NF08: queryごとの有効scopeは一つのsnapshot identityだけを使う。 */
@ExtendWith(MockitoExtension.class)
class CopilotExecutionContextSnapshotTest {
    @Mock LegalEntityContextService legalEntityContextService;
    @Mock LegalEntityReadinessService readinessService;
    @Mock EffectiveScopeSnapshotFactory snapshotFactory;
    @Mock DataScopeService dataScopeService;
    @Mock OrganizationScopeService organizationScopeService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void contextFactoryはsnapshotを一度だけ作成し同一identityを保持する() {
        loginAsAdmin("tenant-a");
        Instant asOf = Instant.parse("2026-09-08T00:00:00Z");
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        EffectiveScopeSnapshot snapshot = snapshot("scope-a", LocalDate.of(2026, 9, 8));
        when(legalEntityContextService.requireTenantId()).thenReturn("tenant-a");
        when(legalEntityContextService.resolveTenantZone("tenant-a")).thenReturn(zone);
        when(legalEntityContextService.requireCurrentLegalEntityId(asOf, zone)).thenReturn(10L);
        when(snapshotFactory.create("tenant-a", 10L, LocalDate.of(2026, 9, 8))).thenReturn(snapshot);

        CopilotExecutionContext context = new CopilotExecutionContextFactory(
                Clock.fixed(asOf, ZoneId.of("UTC")), legalEntityContextService,
                readinessService, snapshotFactory).create();

        assertSame(snapshot, context.effectiveScopeSnapshot());
        assertSame(snapshot.scope(), context.scope());
        verify(snapshotFactory, times(1)).create("tenant-a", 10L, LocalDate.of(2026, 9, 8));
    }

    @Test
    void boundSnapshot後のlegacyGuardはDataScopeとOrganizationScopeを再計算しない() {
        EffectiveScopeSnapshot snapshot = snapshot("scope-a", LocalDate.of(2026, 9, 8));
        CopilotExecutionContext context = new CopilotExecutionContext(
                "tenant-a", 10L, Instant.parse("2026-09-08T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        context.bindSnapshot(snapshot);
        context.bind("legacy.ai", CopilotQueryParameters.ofQuery("legacy.ai"), snapshot.scope());

        clearInvocations(dataScopeService, organizationScopeService);
        AiMatchingScopeGuard guard = new AiMatchingScopeGuard(
                dataScopeService, organizationScopeService, null, new LegacyAiExecutionContextBinder());

        assertTrue(guard.allowsEngineer(123L, context));
        verifyNoMoreInteractions(dataScopeService, organizationScopeService);
    }

    @Test
    void 再認可は進行したClockを読まず元のasOfとtimezoneでsnapshotだけ再計算する() {
        loginAsAdmin("tenant-a");
        Clock clock = mock(Clock.class);
        Instant originalAsOf = Instant.parse("2026-09-08T23:59:59Z");
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        CopilotExecutionContext original = new CopilotExecutionContext(
                "tenant-a", 10L, originalAsOf, zone);
        original.bindSnapshot(snapshot("scope-before", original.asOfDate()));
        EffectiveScopeSnapshot current = snapshot("scope-after", original.asOfDate());

        when(legalEntityContextService.requireTenantId()).thenReturn("tenant-a");
        when(legalEntityContextService.resolveTenantZone("tenant-a")).thenReturn(zone);
        when(legalEntityContextService.requireCurrentLegalEntityId(originalAsOf, zone)).thenReturn(10L);
        when(snapshotFactory.create("tenant-a", 10L, original.asOfDate())).thenReturn(current);

        CopilotExecutionContext reauthorization = new CopilotExecutionContextFactory(
                clock, legalEntityContextService, readinessService, snapshotFactory)
                .createReauthorization(original);

        assertEquals(original.asOf(), reauthorization.asOf());
        assertEquals(original.zoneId(), reauthorization.zoneId());
        assertSame(current, reauthorization.effectiveScopeSnapshot());
        verify(clock, never()).instant();
        verify(snapshotFactory, times(1)).create("tenant-a", 10L, original.asOfDate());
    }

    private static EffectiveScopeSnapshot snapshot(String hash, LocalDate asOf) {
        return new EffectiveScopeSnapshot(
                "tenant-a", 10L, asOf, "COMPANY_WIDE",
                true, false, false,
                null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                hashFor(asOf));
    }

    private static String hashFor(LocalDate asOf) {
        String canonical = "tenant=tenant-a|legalEntity=10|asOf=" + asOf
                + "|scopeType=COMPANY_WIDE|policy=" + EffectiveScopeSnapshotFactory.POLICY_VERSION
                + "|members=ALL";
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void loginAsAdmin(String tenantId) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("管理者");
        LoginUser loginUser = new LoginUser(user, java.util.List.of(), tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }
}
