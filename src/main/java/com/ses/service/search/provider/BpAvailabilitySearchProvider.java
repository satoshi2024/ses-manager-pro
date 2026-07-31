package com.ses.service.search.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.entity.BpAvailability;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.service.search.GlobalSearchProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class BpAvailabilitySearchProvider implements GlobalSearchProvider {

    @Autowired
    private BpAvailabilityMapper bpAvailabilityMapper;

    @Override
    public String getType() {
        return "BP_COMPANY";
    }

    @Override
    public List<GlobalSearchResultDTO> search(String query, int maxResults) {
        LambdaQueryWrapper<BpAvailability> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(BpAvailability::getBpCompany, query)
                .or().like(BpAvailability::getInitialName, query)
                .or().like(BpAvailability::getSkillsJson, query));

        wrapper.orderByDesc(BpAvailability::getUpdatedAt);

        Page<BpAvailability> page = bpAvailabilityMapper.selectPage(new Page<>(1, maxResults), wrapper);
        return page.getRecords().stream().map(b -> GlobalSearchResultDTO.builder()
                .type(getType())
                .id(b.getId())
                .title(b.getBpCompany() + " (" + (b.getInitialName() != null ? b.getInitialName() : "") + ")")
                .subtitle(b.getSkillsJson())
                .status(b.getStatus())
                .url("/bp-availability/list")
                .updatedAt(b.getUpdatedAt())
                .build()).collect(Collectors.toList());
    }
}
