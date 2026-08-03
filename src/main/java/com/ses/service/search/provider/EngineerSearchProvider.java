package com.ses.service.search.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.entity.Engineer;
import com.ses.mapper.EngineerMapper;
import com.ses.service.search.GlobalSearchProvider;
import com.ses.service.security.DataScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EngineerSearchProvider implements GlobalSearchProvider {

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private DataScopeService dataScopeService;

    @Override
    public String getType() {
        return "ENGINEER";
    }

    @Override
    public String getRequiredActionKey() {
        return "engineer.view";
    }

    @Override
    public List<GlobalSearchResultDTO> search(String query, int maxResults) {
        if (dataScopeService.isScoped()) {
            Set<Long> allowedIds = dataScopeService.allowedEngineerIds();
            if (allowedIds == null || allowedIds.isEmpty()) {
                return List.of();
            }
        }

        LambdaQueryWrapper<Engineer> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(Engineer::getFullName, query)
                .or().like(Engineer::getFullNameKana, query));

        if (dataScopeService.isScoped()) {
            wrapper.in(Engineer::getId, dataScopeService.allowedEngineerIds());
        }

        wrapper.orderByDesc(Engineer::getUpdatedAt);

        Page<Engineer> page = engineerMapper.selectPage(new Page<>(1, maxResults), wrapper);
        return page.getRecords().stream().map(e -> GlobalSearchResultDTO.builder()
                .type(getType())
                .id(e.getId())
                .title(e.getFullName())
                .subtitle(e.getEmploymentType() != null ? e.getEmploymentType() : "要員")
                .status(e.getStatus())
                .url("/engineer/detail?id=" + e.getId())
                .updatedAt(e.getUpdatedAt())
                .build()).collect(Collectors.toList());
    }
}
