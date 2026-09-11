package com.ses.service.search.provider;

import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.entity.Project;
import com.ses.mapper.ProjectMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.search.GlobalSearchProvider;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.TenantOwnershipResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ProjectSearchProvider implements GlobalSearchProvider {

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private DataScopeService dataScopeService;

    @Autowired
    private TenantOwnershipResolver tenantOwnershipResolver;

    @Override
    public String getType() {
        return "PROJECT";
    }

    @Override
    public String getRequiredActionKey() {
        return "project.view";
    }

    @Override
    public List<GlobalSearchResultDTO> search(String query, int maxResults) {
        // tenant欠落は fail-closed（暗黙defaultへ落とさない）。
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        // fullAccessでも組織scopeのみbypass。tenant ownershipは常に交差する。
        Set<Long> ownedIds = new HashSet<>(tenantOwnershipResolver.resolveProjectIds(tenantId));
        if (ownedIds.isEmpty()) {
            return List.of();
        }
        if (dataScopeService.isScoped()) {
            Set<Long> allowedIds = dataScopeService.allowedProjectIds();
            if (allowedIds == null || allowedIds.isEmpty()) {
                return List.of();
            }
            ownedIds.retainAll(allowedIds);
            if (ownedIds.isEmpty()) {
                return List.of();
            }
        }

        // tenant-aware mapperのみ使用。NULL customer tenant / mismatch は SQL 側で除外済み。
        List<Project> projects = projectMapper.selectByIdsForTenant(tenantId, ownedIds);
        if (projects == null || projects.isEmpty()) {
            return List.of();
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return projects.stream()
                .filter(p -> p.getProjectName() != null
                        && p.getProjectName().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(Project::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(0, maxResults))
                .map(p -> GlobalSearchResultDTO.builder()
                        .type(getType())
                        .id(p.getId())
                        .title(p.getProjectName())
                        .subtitle(p.getWorkLocation())
                        .status(p.getStatus())
                        .url("/project/list?id=" + p.getId())
                        .updatedAt(p.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());
    }
}
