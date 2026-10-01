package com.ses.service.ai.copilot.citation;

import com.ses.config.LoginUser;
import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.entity.SysUser;
import com.ses.service.RoleMenuService;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CitationAuthorizationServiceTest {

    @Mock
    private RoleMenuService roleMenuService;

    @Mock
    private CopilotScopeResolver scopeResolver;

    @Mock
    private CopilotFeatureGate featureGate;

    @Mock
    private com.ses.service.ai.copilot.CopilotExecutionContextFactory contextFactory;

    @InjectMocks
    private CitationAuthorizationServiceImpl citationAuthorizationService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void keyOnlyCitationは管理者でもrouteを取得できない() {
        loginAs("admin", "管理者");
        ResolvedCitationDto citation = citationAuthorizationService.authorize("dashboard.summary");
        assertFalse(citation.available());
        assertTrue(citation.route() == null || citation.route().isBlank());
    }

    @Test
    void 営業は管理会計Citationを拒否する() {
        loginAs("sales", "営業");

        ResolvedCitationDto citation = citationAuthorizationService.authorize("management-accounting.summary");
        assertFalse(citation.available());
    }

    @Test
    void HRは全Citationを拒否する() {
        loginAs("hr", "HR");
        ResolvedCitationDto citation = citationAuthorizationService.authorize("dashboard.summary");
        assertFalse(citation.available());
    }

    @Test
    void salesPerformanceはdisabledで拒否() {
        loginAs("admin", "管理者");
        ResolvedCitationDto citation = citationAuthorizationService.authorize("sales-performance.monthly");
        assertFalse(citation.available());
    }

    @Test
    void response直前のscopeHash変更はrouteを完全に除外する() {
        loginAs("admin", "管理者");
        SemanticCatalogEntry entry = SemanticCatalogRegistry.find("dashboard.summary").orElseThrow();
        CopilotQueryParameters parameters = CopilotQueryParameters.ofQuery(entry.queryId());
        CopilotExecutionContext context = new CopilotExecutionContext(
                "tenant-a", 10L, Instant.parse("2026-08-31T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        EffectiveScopeSnapshot originalSnapshot = snapshot("tenant-a", 10L,
                java.time.LocalDate.of(2026, 8, 31), null);
        CopilotScopeContext original = originalSnapshot.scope();
        context.bindSnapshot(originalSnapshot);
        context.bind(entry.queryId(), parameters, original);

        CopilotExecutionContext reauthorizationContext = new CopilotExecutionContext(
                "tenant-a", 10L, Instant.parse("2026-08-31T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        reauthorizationContext.bindSnapshot(snapshot("tenant-a", 10L,
                java.time.LocalDate.of(2026, 8, 31), Set.of(999L)));
        when(contextFactory.createReauthorization(context)).thenReturn(reauthorizationContext);

        ResolvedCitationDto citation = citationAuthorizationService.authorize(
                "dashboard.summary", context, parameters, original, original.scopeHash());

        assertFalse(citation.available());
        assertFalse(citation.route() != null && !citation.route().isBlank());
    }

    @Test
    void provisionalCatalogはscope再計算が同一でもcitationを返さない() {
        loginAs("admin", "管理者");
        SemanticCatalogEntry entry = SemanticCatalogRegistry.find("dashboard.summary").orElseThrow();
        CopilotExecutionContext context = boundContext("tenant-a", 10L, ZoneId.of("Asia/Tokyo"));
        CopilotQueryParameters parameters = context.parameters();
        CopilotExecutionContext reauthorization = snapshotContext(
                "tenant-a", 10L, ZoneId.of("Asia/Tokyo"));
        when(contextFactory.createReauthorization(context)).thenReturn(reauthorization);

        ResolvedCitationDto citation = citationAuthorizationService.authorize(
                "dashboard.summary", context, parameters, context.scope(), context.scopeHash());

        assertFalse(citation.available());
    }

    @Test
    void 再認可でtenant法人timezoneが変わればcitationを返さない() {
        loginAs("admin", "管理者");
        SemanticCatalogEntry entry = SemanticCatalogRegistry.find("dashboard.summary").orElseThrow();
        CopilotExecutionContext context = boundContext("tenant-a", 10L, ZoneId.of("Asia/Tokyo"));
        CopilotQueryParameters parameters = context.parameters();

        for (CopilotExecutionContext changed : List.of(
                snapshotContext("tenant-b", 10L, ZoneId.of("Asia/Tokyo")),
                snapshotContext("tenant-a", 20L, ZoneId.of("Asia/Tokyo")),
                snapshotContext("tenant-a", 10L, ZoneId.of("UTC")))) {
            when(contextFactory.createReauthorization(context)).thenReturn(changed);
            ResolvedCitationDto citation = citationAuthorizationService.authorize(
                    "dashboard.summary", context, parameters, context.scope(), context.scopeHash());
            assertFalse(citation.available());
        }
    }

    @Test
    void contextなしtypedParametersなしはfailClosedする() {
        loginAs("admin", "管理者");
        ResolvedCitationDto citation = citationAuthorizationService.authorize(
                "dashboard.summary", null, null, null, null);
        assertFalse(citation.available());
        assertTrue(citation.route() == null || citation.route().isBlank());
    }

    @Test
    void query期間がcontextと異なるparametersはfailClosedする() {
        loginAs("admin", "管理者");
        SemanticCatalogEntry entry = SemanticCatalogRegistry.find("dashboard.summary").orElseThrow();
        CopilotQueryParameters bound = CopilotQueryParameters.ofQuery(entry.queryId());
        CopilotQueryParameters changed = new CopilotQueryParameters(
                entry.queryId(), null, null, null, java.time.YearMonth.of(2026, 9));
        CopilotExecutionContext context = boundContext("tenant-a", 10L, ZoneId.of("Asia/Tokyo"));
        CopilotScopeContext scope = context.scope();

        ResolvedCitationDto citation = citationAuthorizationService.authorize(
                "dashboard.summary", context, changed, scope, context.scopeHash());

        assertFalse(citation.available());
    }

    private void loginAs(String username, String role) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername(username);
        user.setRole(role);
        LoginUser loginUser = new LoginUser(user, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private EffectiveScopeSnapshot snapshot(String tenantId, Long legalEntityId,
                                            java.time.LocalDate asOf, Set<Long> engineerIds) {
        boolean scoped = engineerIds != null;
        String scopeType = scoped ? "DATA_SCOPED" : "COMPANY_WIDE";
        String members = scoped
                ? "organizations=ALL;directUsers=ALL;users=ALL;engineers=" + canonical(engineerIds)
                + ";projects=ALL;contracts=ALL;customers=ALL;invoices=ALL;proposals=ALL"
                : "ALL";
        String hash = sha256("tenant=" + tenantId + "|legalEntity=" + legalEntityId
                + "|asOf=" + asOf + "|scopeType=" + scopeType
                + "|policy=" + EffectiveScopeSnapshotFactory.POLICY_VERSION
                + "|members=" + members);
        return new EffectiveScopeSnapshot(
                tenantId, legalEntityId, asOf, scopeType,
                true, scoped, false,
                null, null, null, engineerIds, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, members, hash);
    }

    private CopilotExecutionContext boundContext(String tenantId, Long legalEntityId,
                                                 ZoneId zone) {
        CopilotExecutionContext context = snapshotContext(tenantId, legalEntityId, zone);
        context.bind("dashboard.summary", CopilotQueryParameters.ofQuery("dashboard.summary"), context.scope());
        return context;
    }

    private CopilotExecutionContext snapshotContext(String tenantId, Long legalEntityId,
                                                    ZoneId zone) {
        CopilotExecutionContext context = new CopilotExecutionContext(
                tenantId, legalEntityId, Instant.parse("2026-08-31T00:00:00Z"), zone);
        EffectiveScopeSnapshot snapshot = snapshot(tenantId, legalEntityId, context.asOfDate(), null);
        context.bindSnapshot(snapshot);
        return context;
    }

    private static String canonical(Set<Long> values) {
        return values == null ? "ALL" : new TreeSet<>(values).toString();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
