package com.ses.service.ai.copilot.citation;

import com.ses.common.util.SecurityUtils;
import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.service.RoleMenuService;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
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

    @Override
    public ResolvedCitationDto authorize(String citationKey) {
        if (citationKey == null || citationKey.isBlank()) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        CitationRoute route = ROUTES.get(citationKey);
        if (route == null) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        SemanticCatalogEntry entry = SemanticCatalogRegistry.find(citationKey).orElse(null);
        if (entry == null || !entry.enabled()) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        String role = SecurityUtils.currentRole();
        if (role == null || !entry.allowedRoles().contains(role)) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        if ("HR".equals(role) || "要員".equals(role)) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        if (!hasMenu(role, route.menuKey())) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        return new ResolvedCitationDto(citationKey, route.label(), route.path(), true);
    }

    @Override
    public List<ResolvedCitationDto> authorizeAll(List<String> citationKeys) {
        if (citationKeys == null || citationKeys.isEmpty()) {
            return List.of();
        }
        return citationKeys.stream().map(this::authorize).toList();
    }

    private boolean hasMenu(String role, String menuKey) {
        if ("管理者".equals(role)) {
            return true;
        }
        List<String> menus = roleMenuService.getMenuKeysByRole(role);
        return menus != null && menus.contains(menuKey);
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
