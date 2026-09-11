package com.ses.service.search.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.entity.Project;
import com.ses.mapper.ProjectMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.search.GlobalSearchProvider;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.TenantOwnershipResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
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
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        // fullAccessでもtenant ownership母集合を超えない（null=全表検索は禁止）。
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

        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Project::getId, ownedIds);
        wrapper.and(w -> w.like(Project::getProjectName, query));
        wrapper.orderByDesc(Project::getUpdatedAt);

        Page<Project> page = projectMapper.selectPage(new Page<>(1, maxResults), wrapper);
        return page.getRecords().stream().map(p -> GlobalSearchResultDTO.builder()
                .type(getType())
                .id(p.getId())
                .title(p.getProjectName())
                .subtitle(p.getWorkLocation())
                .status(p.getStatus())
                .url("/project/list?id=" + p.getId())
                .updatedAt(p.getUpdatedAt())
                .build()).collect(Collectors.toList());
    }
}
