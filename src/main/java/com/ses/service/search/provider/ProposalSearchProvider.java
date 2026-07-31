package com.ses.service.search.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.entity.Proposal;
import com.ses.mapper.ProposalMapper;
import com.ses.service.search.GlobalSearchProvider;
import com.ses.service.security.DataScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ProposalSearchProvider implements GlobalSearchProvider {

    @Autowired
    private ProposalMapper proposalMapper;

    @Autowired
    private DataScopeService dataScopeService;

    @Override
    public String getType() {
        return "PROPOSAL";
    }

    @Override
    public List<GlobalSearchResultDTO> search(String query, int maxResults) {
        if (dataScopeService.isScoped()) {
            Set<Long> allowedIds = dataScopeService.allowedProposalIds();
            if (allowedIds == null || allowedIds.isEmpty()) {
                return List.of();
            }
        }

        LambdaQueryWrapper<Proposal> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(Proposal::getRemarks, query)
                .or().like(Proposal::getProposalEmailText, query));

        if (dataScopeService.isScoped()) {
            wrapper.in(Proposal::getId, dataScopeService.allowedProposalIds());
        }

        wrapper.orderByDesc(Proposal::getUpdatedAt);

        Page<Proposal> page = proposalMapper.selectPage(new Page<>(1, maxResults), wrapper);
        return page.getRecords().stream().map(p -> GlobalSearchResultDTO.builder()
                .type(getType())
                .id(p.getId())
                .title("提案 ID:" + p.getId())
                .subtitle(p.getRemarks())
                .status(p.getStatus())
                .url("/proposal/kanban")
                .updatedAt(p.getUpdatedAt())
                .build()).collect(Collectors.toList());
    }
}
