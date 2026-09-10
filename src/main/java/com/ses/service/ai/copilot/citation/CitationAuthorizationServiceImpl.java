package com.ses.service.ai.copilot.citation;

import com.ses.common.util.SecurityUtils;
import com.ses.common.exception.BusinessException;
import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.service.RoleMenuService;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotExecutionContextFactory;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CitationAuthorizationServiceImpl implements CitationAuthorizationService {

    private static final Map<String, CitationRoute> ROUTES = buildRoutes();

    private final RoleMenuService roleMenuService;
    private final CopilotScopeResolver scopeResolver;
    private final CopilotFeatureGate featureGate;
    private final CopilotExecutionContextFactory contextFactory;

    @Override
    public ResolvedCitationDto authorize(String citationKey) {
        // 旧来のkey-only入口では、期間・法人・scopeを再構成できない。
        // route/pathを返すとcitation keyだけで認可を迂回できるため、常にfail-closedする。
        return ResolvedCitationDto.unavailable(citationKey);
    }

    @Override
    public List<ResolvedCitationDto> authorizeAll(List<String> citationKeys) {
        if (citationKeys == null || citationKeys.isEmpty()) {
            return List.of();
        }
        return citationKeys.stream().map(this::authorize).toList();
    }

    @Override
    public ResolvedCitationDto authorize(String citationKey, CopilotExecutionContext context,
                                         CopilotQueryParameters typedParameters,
                                         CopilotScopeContext resolvedScope, String scopeHash) {
        if (context == null || typedParameters == null || resolvedScope == null || scopeHash == null
                || !scopeHash.equals(resolvedScope.scopeHash()) || context.scope() != resolvedScope
                || context.effectiveScopeSnapshot() == null
                || context.scope() != context.effectiveScopeSnapshot().scope()
                || context.parameters() != typedParameters || !scopeHash.equals(context.scopeHash())
                || context.queryId() == null || !context.queryId().equals(typedParameters.queryId())
                || !context.tenantId().equals(resolvedScope.tenantId())
                || !context.legalEntityId().equals(resolvedScope.legalEntityId())) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        try {
            featureGate.assertCitationAllowed();
            SemanticCatalogEntry entry = SemanticCatalogRegistry.find(context.queryId()).orElse(null);
            if (entry == null || !context.queryId().equals(typedParameters.queryId())) {
                return ResolvedCitationDto.unavailable(citationKey);
            }
            // response直前の再認可は、元queryのasOf/tenant/legal/timezoneを維持して
            // 新しい権限snapshotだけを計算する。
            CopilotExecutionContext reauthorizationContext = contextFactory.createReauthorization(context);
            if (reauthorizationContext == null
                    || !java.util.Objects.equals(context.tenantId(), reauthorizationContext.tenantId())
                    || !java.util.Objects.equals(context.legalEntityId(), reauthorizationContext.legalEntityId())
                    // createReauthorizationは元queryのasOfを再利用するため、ここは新しい現在時刻との
                    // 比較ではなく、context identityの整合性検証である。
                    || !java.util.Objects.equals(context.asOf(), reauthorizationContext.asOf())
                    || !java.util.Objects.equals(context.zoneId(), reauthorizationContext.zoneId())
                    || reauthorizationContext.effectiveScopeSnapshot() == null) {
                return ResolvedCitationDto.unavailable(citationKey);
            }
            reauthorizationContext.bind(context.queryId(), typedParameters,
                    reauthorizationContext.effectiveScopeSnapshot().scope());
            CopilotScopeContext current = reauthorizationContext.scope();
            if (current == null || !scopeHash.equals(current.scopeHash())) {
                return ResolvedCitationDto.unavailable(citationKey);
            }
            ResolvedCitationDto citation = authorizeMenu(citationKey, entry);
            if (!citation.available()) {
                return citation;
            }
            if (!sameCanonicalScope(resolvedScope, current)) {
                return ResolvedCitationDto.unavailable(citationKey);
            }
            // ルートはcatalogのallow-listからのみ解決し、detail/exportの任意pathは受け付けない。
            return entry.citationKeys().contains(citationKey) ? citation
                    : ResolvedCitationDto.unavailable(citationKey);
        } catch (RuntimeException ex) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
    }

    @Override
    public List<ResolvedCitationDto> authorizeAll(List<String> citationKeys, CopilotExecutionContext context,
                                                  CopilotQueryParameters typedParameters,
                                                  CopilotScopeContext resolvedScope, String scopeHash) {
        if (citationKeys == null || citationKeys.isEmpty()) {
            return List.of();
        }
        return citationKeys.stream()
                .map(key -> authorize(key, context, typedParameters, resolvedScope, scopeHash))
                .filter(ResolvedCitationDto::available)
                .toList();
    }

    private boolean hasMenu(String role, String menuKey) {
        if ("管理者".equals(role)) {
            return true;
        }
        List<String> menus = roleMenuService.getMenuKeysByRole(role);
        return menus != null && menus.contains(menuKey);
    }

    private ResolvedCitationDto authorizeMenu(String citationKey, SemanticCatalogEntry entry) {
        if (citationKey == null || citationKey.isBlank() || entry == null || !entry.enabled()
                || !entry.citationKeys().contains(citationKey)) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        CitationRoute route = ROUTES.get(citationKey);
        String role = SecurityUtils.currentRole();
        if (route == null || role == null || !entry.allowedRoles().contains(role)
                || "HR".equals(role) || "要員".equals(role)
                || !hasMenu(role, route.menuKey())) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        return new ResolvedCitationDto(citationKey, route.label(), route.path(), true);
    }

    private boolean sameCanonicalScope(CopilotScopeContext expected, CopilotScopeContext actual) {
        return expected != null && actual != null
                && java.util.Objects.equals(expected.scopeType(), actual.scopeType())
                && java.util.Objects.equals(expected.policyVersion(), actual.policyVersion())
                && java.util.Objects.equals(expected.tenantId(), actual.tenantId())
                && java.util.Objects.equals(expected.legalEntityId(), actual.legalEntityId())
                && java.util.Objects.equals(expected.canonicalMembers(), actual.canonicalMembers())
                && java.util.Objects.equals(expected.scopeHash(), actual.scopeHash());
    }

    private record CitationRoute(String menuKey, String path, String label) {
    }

    private static Map<String, CitationRoute> buildRoutes() {
        Map<String, CitationRoute> map = new LinkedHashMap<>();
        map.put("dashboard.summary", new CitationRoute("dashboard", "/dashboard", "ダッシュボード"));
        map.put("dashboard.profit-analysis", new CitationRoute("dashboard", "/dashboard/profit", "粗利分析"));
        map.put("dashboard.utilization-forecast", new CitationRoute("dashboard", "/dashboard", "稼働率予測"));
        map.put("management-accounting.summary", new CitationRoute("management-accounting", "/management-accounting", "管理会計"));
        map.put("cashflow.forecast", new CitationRoute("dashboard", "/dashboard", "資金繰り予測"));
        map.put("sales-performance.monthly", new CitationRoute("sales-performance", "/sales-performance", "営業成績"));
        return Map.copyOf(map);
    }
}
